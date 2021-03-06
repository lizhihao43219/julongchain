/*
 * Copyright Dingxuan. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

		 http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package org.bcia.julongchain.core.ledger.kvledger.txmgmt.txmgr.lockbasedtxmgr;

import com.google.protobuf.ByteString;
import org.bcia.julongchain.common.exception.JulongChainException;
import org.bcia.julongchain.common.exception.LedgerException;
import org.bcia.julongchain.common.log.JulongChainLog;
import org.bcia.julongchain.common.log.JulongChainLogFactory;
import org.bcia.julongchain.core.ledger.BlockAndPvtData;
import org.bcia.julongchain.core.ledger.IQueryExecutor;
import org.bcia.julongchain.core.ledger.ITxSimulator;
import org.bcia.julongchain.core.ledger.IStateListener;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.privacyenabledstate.IDB;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.privacyenabledstate.UpdateBatch;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.statedb.stateleveldb.VersionedValue;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.txmgr.ITxManager;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.validator.IValidator;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.validator.valimpl.DefaultValidator;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.version.LedgerHeight;
import org.bcia.julongchain.protos.common.Common;
import org.bcia.julongchain.protos.ledger.rwset.kvrwset.KvRwset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易管理者类
 *
 * @author sunzongyu
 * @date 2018/04/17
 * @company Dingxuan
 */
public class LockBasedTxManager implements ITxManager {
    private static JulongChainLog log = JulongChainLogFactory.getLog(LockBasedTxManager.class);

    private String ledgerID;
    private IDB db;
    private IValidator validator;
    private UpdateBatch batch;
    private Common.Block currentBlock;
    private Map<String, IStateListener> stateListeners;
    private static Map<String, LockBasedTxSimulator> txSimulatorMap = new HashMap<>();

    public LockBasedTxManager(String ledgerID,
                              IDB db,
                              Map<String, IStateListener> stateListeners) throws LedgerException{
        db.open();  //open will do nothing
        this.ledgerID = ledgerID;
        this.db = db;
        this.stateListeners = stateListeners;
        this.validator = new DefaultValidator(this, db);
    }

	/**
	 * 创建新的交易查询器
	 * @param txid 交易id
	 */
    @Override
    public synchronized IQueryExecutor newQueryExecutor(String txid) throws LedgerException {
        return new LockBasedQueryExecutor(this, txid);
    }

	/**
	 * 创建新的交易模拟器
	 * @param txid 交易id
	 */
    @Override
    public synchronized ITxSimulator newTxSimulator(String txid) throws LedgerException {
	    if (txSimulatorMap.containsKey(txid)) {
		    log.debug("Contains tx simulator with txid: " + txid);
		    return txSimulatorMap.get(txid);
	    } else {
		    log.debug("Constructing new tx simulator");
		    LockBasedTxSimulator simulator = new LockBasedTxSimulator(this, txid);
		    txSimulatorMap.put(txid, simulator);
		    return simulator;
	    }
    }

	/**
	 * 验证交易有效性以及准备更新包
	 */
    @Override
    public void validateAndPrepare(BlockAndPvtData blockAndPvtData, boolean doMVCCValidation) throws LedgerException {
        try {
            Common.Block block = blockAndPvtData.getBlock();
			log.debug("Validating new block with num trans = " + block.getData().getDataList().size());
            UpdateBatch b = validator.validateAndPrepareBatch(blockAndPvtData, doMVCCValidation);
            currentBlock = block;
            batch = b;
            invokeNamespaceListeners(batch);
        } catch (Exception e){
            clearCache();
            throw new LedgerException(e);
        }
    }

	/**
	 * 获取最新的保存点
	 */
	@Override
    public LedgerHeight getLastSavepoint() throws LedgerException {
        return db.getLatestSavePoint();
    }

    /**
	 * 判断是否需要恢复
	 * @return blockHeight
     */
    @Override
    public long shouldRecover() throws LedgerException {
        long result = 0;
        LedgerHeight savePoint = getLastSavepoint();
        if(savePoint == null){
            return 0;
        }
        result = savePoint.getBlockNum() + 1;
        return result;
    }

    @Override
    public void commitLostBlock(BlockAndPvtData blockAndPvtData) throws LedgerException {
        Common.Block block = blockAndPvtData.getBlock();
        log.debug("Constructing updateSet for the block " + block.getHeader().getNumber());
        validateAndPrepare(blockAndPvtData, false);
        log.debug(String.format("Committing block %d to state database", block.getHeader().getNumber()));
        commit();
    }

    @Override
    public synchronized void commit() throws LedgerException {
        try{
            log.debug("Committing updates to state db");
            if(batch == null){
                throw new LedgerException("validateAndPrepare() method should have been called before calling commit()");
            }
            db.applyPrivacyAwareUpdates(batch,
                    new LedgerHeight(currentBlock.getHeader().getNumber(), (long) (currentBlock.getData().getDataList().size() - 1)));
            log.debug("Update committed to state db");
        } finally {
            clearCache();
            batch = null;
        }
    }

    @Override
    public void rollback() throws LedgerException {
        batch = null;
        clearCache();
    }

    @Override
    public void shutdown() throws LedgerException {
        db.close();
    }

    private void clearCache(){
        if(db.isBulkOptimizable()){
            db.clearCachedVersions();
        }
    }

    private void invokeNamespaceListeners(UpdateBatch batch) throws JulongChainException {
        List<String> namespaces = batch.getPubUpdateBatch().getBatch().getUpdatedNamespaces();
        for(String ns : namespaces){
        	if(stateListeners == null){
        		break;
	        }
            IStateListener listener = stateListeners.get(ns);
            if(listener == null){
                continue;
            }
            log.debug("Invoking listener for state changes over namespace " + ns);
            Map<String, VersionedValue> updateMap = batch.getPubUpdateBatch().getBatch().getUpdates(ns);
            List<KvRwset.KVWrite> kvWrites = new ArrayList<>();
            for(Map.Entry<String, VersionedValue> entry : updateMap.entrySet()){
                String key = entry.getKey();
                VersionedValue versionedValue = entry.getValue();
                KvRwset.KVWrite kvw = KvRwset.KVWrite.newBuilder()
                        .setKey(key)
                        .setIsDelete(versionedValue.getValue() == null)
                        .setValue(ByteString.copyFrom(versionedValue.getValue()))
                        .build();
                kvWrites.add(kvw);
            }
            listener.handleStateUpdates(ledgerID, kvWrites);
        }
    }

    public String getLedgerID() {
        return ledgerID;
    }

    public void setLedgerID(String ledgerID) {
        this.ledgerID = ledgerID;
    }

    public IDB getDb() {
        return db;
    }

    public void setDb(IDB db) {
        this.db = db;
    }

    public IValidator getValidator() {
        return validator;
    }

    public void setValidator(IValidator validator) {
        this.validator = validator;
    }

    public UpdateBatch getBatch() {
        return batch;
    }

    public void setBatch(UpdateBatch batch) {
        this.batch = batch;
    }

    public Common.Block getCurrentBlock() {
        return currentBlock;
    }

    public void setCurrentBlock(Common.Block currentBlock) {
        this.currentBlock = currentBlock;
    }

    public Map<String, IStateListener> getStateListeners() {
        return stateListeners;
    }

    public void setStateListeners(Map<String, IStateListener> stateListeners) {
        this.stateListeners = stateListeners;
    }

	public static void setTxSimulatorMap(Map<String, LockBasedTxSimulator> txSimulatorMap) {
		LockBasedTxManager.txSimulatorMap = txSimulatorMap;
	}

	public static Map<String, LockBasedTxSimulator> getTxSimulatorMap() {
		return txSimulatorMap;
	}
}
