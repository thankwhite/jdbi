/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.sqlobject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import com.google.common.collect.ImmutableSet;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.Something;
import org.jdbi.v3.core.transaction.TransactionException;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;
import org.jdbi.v3.core.transaction.UnableToManipulateTransactionIsolationLevelException;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jdbi.v3.sqlobject.transaction.Transactional;
import org.jdbi.v3.testing.junit5.JdbiExtension;
import org.jdbi.v3.testing.junit5.JdbiH2Extension;
import org.jdbi.v3.testing.junit5.internal.TestingInitializers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTransactional {

    @RegisterExtension
    public JdbiExtension h2Extension = new JdbiH2Extension() {
        @Override
        protected DataSource createDataSource() {
            final JdbcDataSource ds = new JdbcDataSource() {
                private static final long serialVersionUID = 1L;

                @Override
                public Connection getConnection() throws SQLException {
                    return createProxyHandler(super.getConnection());
                }
            };
            ds.setURL(getUrl());

            return ds;
        }
    }.withInitializer(TestingInitializers.something()).withPlugin(new SqlObjectPlugin());

    private Jdbi jdbi;

    @BeforeEach
    public void setUp() {
        this.jdbi = h2Extension.getJdbi();
    }

    private final AtomicBoolean inTransaction = new AtomicBoolean();

    public interface TheBasics extends Transactional<TheBasics> {

        @SqlUpdate("insert into something (id, name) values (:id, :name)")
        @Transaction(TransactionIsolationLevel.SERIALIZABLE)
        int insert(@BindBean Something something);
    }

    @Transaction
    public interface AlwaysTransactional extends SqlObject {
        default boolean isInTransaction() {
            return getHandle().isInTransaction();
        }
    }

    @Test
    public void testDoublyTransactional() {
        final TheBasics dao = jdbi.onDemand(TheBasics.class);
        dao.inTransaction(TransactionIsolationLevel.SERIALIZABLE, transactional -> {
            transactional.insert(new Something(1, "2"));
            inTransaction.set(true);
            transactional.insert(new Something(2, "3"));
            inTransaction.set(false);
            return null;
        });
    }

    @Test
    public void testCrashWithHandler() {
        final TheBasics dao = jdbi.onDemand(TheBasics.class);

        UnableToManipulateTransactionIsolationLevelException e = Assertions.assertThrows(UnableToManipulateTransactionIsolationLevelException.class,
            () -> dao.inTransaction(TransactionIsolationLevel.SERIALIZABLE, transactional -> {
            inTransaction.set(true);
            transactional.getHandle().setTransactionIsolation(TransactionIsolationLevel.READ_COMMITTED);
            transactional.insert(new Something(2, "3"));
            inTransaction.set(false);
            return null;
        }));

        assertThat(e.getCause()).isInstanceOf(SQLException.class);
        assertThat(e.getCause().getMessage()).isEqualTo("PostgreSQL would not let you set the transaction isolation here");
    }

    @Test
    public void testOnDemandBeginTransaction() {
        // Calling methods like begin() on an on-demand Transactional SQL object makes no sense--the transaction would
        // begin and the connection would just close.
        // Jdbi should identify this scenario and throw an exception informing the user that they're not managing their
        // transactions correctly.
        assertThatThrownBy(jdbi.onDemand(Transactional.class)::begin).isInstanceOf(TransactionException.class);
    }

    @Test
    public void testTypeDecorator() {
        assertThat(jdbi.onDemand(AlwaysTransactional.class).isInTransaction()).isTrue();
    }

    private static final Set<Method> CHECKED_METHODS;

    static {
        try {
            CHECKED_METHODS = ImmutableSet.of(Connection.class.getMethod("setTransactionIsolation", int.class));
        } catch (final Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Connection createProxyHandler(Connection real) {
        return (Connection) Proxy.newProxyInstance(real.getClass().getClassLoader(),
            new Class<?>[]{Connection.class},
            new TxnIsolationCheckingInvocationHandler(real));
    }

    private class TxnIsolationCheckingInvocationHandler implements InvocationHandler {

        private final Connection real;

        TxnIsolationCheckingInvocationHandler(Connection real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (CHECKED_METHODS.contains(method) && inTransaction.get()) {
                throw new SQLException("PostgreSQL would not let you set the transaction isolation here");
            }
            return method.invoke(real, args);
        }
    }
}
