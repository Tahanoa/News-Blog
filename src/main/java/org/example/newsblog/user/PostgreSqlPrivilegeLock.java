package org.example.newsblog.user;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction-scoped PostgreSQL lock for bootstrap and last-administrator protection. */
@Component
class PostgreSqlPrivilegeLock {
    private final JdbcTemplate jdbc;
    PostgreSqlPrivilegeLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Privilege changes require a database transaction");
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, 7642311001L);
                statement.execute();
            }
            return null;
        });
    }
}
