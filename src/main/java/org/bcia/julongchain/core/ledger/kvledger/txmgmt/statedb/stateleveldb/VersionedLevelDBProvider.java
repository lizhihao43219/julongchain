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
package org.bcia.julongchain.core.ledger.kvledger.txmgmt.statedb.stateleveldb;

import org.bcia.julongchain.common.exception.LedgerException;
import org.bcia.julongchain.common.ledger.util.IDBProvider;
import org.bcia.julongchain.common.ledger.util.leveldbhelper.LevelDBProvider;
import org.bcia.julongchain.common.log.JulongChainLog;
import org.bcia.julongchain.common.log.JulongChainLogFactory;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.statedb.IVersionedDB;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.statedb.IVersionedDBProvider;
import org.bcia.julongchain.core.ledger.ledgerconfig.LedgerConfig;

/**
 * 提供leveldb实现的VersionDB辅助
 *
 * @author sunzongyu
 * @date 2018/04/13
 * @company Dingxuan
 */
public class VersionedLevelDBProvider implements IVersionedDBProvider {
    private static JulongChainLog log = JulongChainLogFactory.getLog(VersionedLevelDBProvider.class);

    private IDBProvider db;

    public VersionedLevelDBProvider() throws LedgerException {
        String dbPath = LedgerConfig.getStateLevelDBPath();
        this.db = new LevelDBProvider(dbPath);
        log.debug("Create vdb using path " + this.db.getDBPath());
    }

    @Override
    public IVersionedDB getDBHandle(String id) throws LedgerException {
    	this.db = ((LevelDBProvider) db).getDBHandle(id);
        return new VersionedLevelDB(db, db.getDb().getDbName());
    }

    @Override
    public void close() {
        try {
            db.close();
        } catch (LedgerException e) {
            throw new RuntimeException("Got error when close level db");
        }
    }

    public IDBProvider getDb() {
        return db;
    }

    public void setDb(IDBProvider db) {
        this.db = db;
    }
}
