/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.oodt.commons.database;

import org.apache.commons.dbcp2.ConnectionFactory;
import org.apache.commons.dbcp2.DriverManagerConnectionFactory;
import org.apache.commons.dbcp2.PoolableConnection;
import org.apache.commons.dbcp2.PoolableConnectionFactory;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.commons.pool2.impl.GenericObjectPool;

import javax.sql.DataSource;

/**
 * @author mattmann
 * @version $Revision$
 * 
 * <p>
 * A class to build database connections from JDBC information.
 * </p>.
 */
public final class DatabaseConnectionBuilder {

    public static DataSource buildDataSource(String user, String pass,
            String driver, String url) {

        DataSource ds;

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException ignore) {
        }

        // dbcp2 wires the other way round from dbcp 1.x. There the pool was
        // built first with a null factory and the PoolableConnectionFactory
        // constructor quietly registered itself against it, which is why the
        // factory was assigned to a variable nobody read. Here the factory is
        // built first, the pool is built from it, and the two are linked
        // explicitly.
        ConnectionFactory connectionFactory = new DriverManagerConnectionFactory(
                url, user, pass);
        PoolableConnectionFactory poolableConnectionFactory =
                new PoolableConnectionFactory(connectionFactory, null);
        poolableConnectionFactory.setDefaultReadOnly(false);
        poolableConnectionFactory.setDefaultAutoCommit(true);
        GenericObjectPool<PoolableConnection> connectionPool =
                new GenericObjectPool<PoolableConnection>(
                        poolableConnectionFactory);
        poolableConnectionFactory.setPool(connectionPool);

        return new PoolingDataSource<PoolableConnection>(connectionPool);
    }

}
