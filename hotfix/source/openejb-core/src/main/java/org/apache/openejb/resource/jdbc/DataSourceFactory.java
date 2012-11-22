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
package org.apache.openejb.resource.jdbc;

import org.apache.commons.dbcp.ConnectionFactory;
import org.apache.commons.dbcp.DataSourceConnectionFactory;
import org.apache.commons.dbcp.managed.DataSourceXAConnectionFactory;
import org.apache.commons.dbcp.managed.LocalXAConnectionFactory;
import org.apache.commons.dbcp.managed.TransactionRegistry;
import org.apache.commons.dbcp.managed.XAConnectionFactory;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.resource.XAResourceWrapper;
import org.apache.openejb.util.LogCategory;
import org.apache.xbean.recipe.ObjectRecipe;
import org.apache.xbean.recipe.Option;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/**
 * @version $Rev: 1205747 $ $Date: 2011-11-23 23:53:38 -0800 (Wed, 23 Nov 2011) $
 */
public class DataSourceFactory {

    public static DataSource create(final boolean managed, final Class impl, final String definition) throws IllegalAccessException, InstantiationException, IOException {

        final org.apache.commons.dbcp.BasicDataSource ds;

        if (DataSource.class.isAssignableFrom(impl) && !Boolean.parseBoolean(System.getProperty("org.apache.openejb.resource.jdbc.hot.deploy", "false"))) {

            final ObjectRecipe recipe = new ObjectRecipe(impl);
            recipe.allow(Option.CASE_INSENSITIVE_PROPERTIES);
            recipe.allow(Option.IGNORE_MISSING_PROPERTIES);
            recipe.allow(Option.NAMED_PARAMETERS);
            recipe.setAllProperties(asProperties(definition));

            DataSource dataSource = (DataSource) recipe.create();

            if (managed) {
                ds = new DbcpManagedDataSource(dataSource);
            } else {
                ds = new DbcpDataSource(dataSource);
            }
        } else {
            ds = (org.apache.commons.dbcp.BasicDataSource) create(managed);
        }

        // force the driver class to be set
        ds.setDriverClassName(impl.getName());

        return ds;
    }

    private static Map<?, ?> asProperties(String definition) throws IOException {
        final ByteArrayInputStream in = new ByteArrayInputStream(definition.getBytes());
        final Properties properties = new Properties();
        properties.load(in);
        trimNotSupportedDataSourceProperties(properties);
        return properties;
    }

    public static void trimNotSupportedDataSourceProperties(Properties properties) {
        properties.remove("LoginTimeout");
    }

    public static DataSource create(boolean managed) {
        org.apache.commons.dbcp.BasicDataSource ds;
        if (managed) {
            XAResourceWrapper xaResourceWrapper = SystemInstance.get().getComponent(XAResourceWrapper.class);
            if (xaResourceWrapper != null) {
                ds = new ManagedDataSourceWithRecovery(xaResourceWrapper);
            } else {
                ds = new BasicManagedDataSource();
            }
        } else {
            ds = new BasicDataSource();
        }
        return ds;
    }

    public static class DbcpDataSource extends BasicDataSource {

        private final DataSource dataSource;

        public DbcpDataSource(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        protected ConnectionFactory createConnectionFactory() throws SQLException {
            return new DataSourceConnectionFactory(dataSource, username, password);
        }

        @Override
        public void setJdbcUrl(String url) {
            // TODO This is a big whole and we will need to rework this
            try {
                // only works if hsql is available and datasource is an HSQL jdbcDataSource
                final Class<?> hsql = this.getClass().getClassLoader().loadClass("org.hsqldb.jdbc.JDBCDataSource");
                final Method setDatabase = hsql.getMethod("setDatabase", String.class);
                setDatabase.setAccessible(true);
                setDatabase.invoke(dataSource, url);
            } catch (Throwable e) {
                super.setUrl(url);
            }
        }
    }

    public static class DbcpManagedDataSource extends BasicManagedDataSource {

        private final DataSource dataSource;

        public DbcpManagedDataSource(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public void setJdbcUrl(String url) {
            // TODO This is a big whole and we will need to rework this
            try {
                // only works if hsql is available and datasource is an HSQL jdbcDataSource
                final Class<?> hsql = this.getClass().getClassLoader().loadClass("org.hsqldb.jdbc.JDBCDataSource");
                final Method setDatabase = hsql.getMethod("setDatabase", String.class);
                setDatabase.setAccessible(true);
                setDatabase.invoke(dataSource, url);
            } catch (Throwable e) {
                super.setUrl(url);
            }
        }

        @Override
        protected ConnectionFactory createConnectionFactory() throws SQLException {

            if (dataSource instanceof XADataSource) {

                // Create the XAConectionFactory using the XA data source
                XADataSource xaDataSourceInstance = (XADataSource) dataSource;
                XAConnectionFactory xaConnectionFactory = new DataSourceXAConnectionFactory(getTransactionManager(), xaDataSourceInstance, username, password);
                setTransactionRegistry(xaConnectionFactory.getTransactionRegistry());
                return xaConnectionFactory;

            } else {

                // If xa data source is not specified a DriverConnectionFactory is created and wrapped with a LocalXAConnectionFactory
                ConnectionFactory connectionFactory = new DataSourceConnectionFactory(dataSource, username, password);
                XAConnectionFactory xaConnectionFactory = new LocalXAConnectionFactory(getTransactionManager(), connectionFactory);
                setTransactionRegistry(xaConnectionFactory.getTransactionRegistry());
                return xaConnectionFactory;
            }
        }

        public void setTransactionRegistry(TransactionRegistry registry) {
            try {
                final Field field = org.apache.commons.dbcp.managed.BasicManagedDataSource.class.getDeclaredField("transactionRegistry");
                field.setAccessible(true);
                field.set(this, registry);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
