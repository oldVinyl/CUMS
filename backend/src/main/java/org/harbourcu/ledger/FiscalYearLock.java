package org.harbourcu.ledger;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes posting and closing for one fiscal year within their transactions. */
@Service
public class FiscalYearLock {
    private final JdbcTemplate jdbc;

    public FiscalYearLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireOpen(int year) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Fiscal-year lock requires a transaction");
        }
        List<String> rows = jdbc.query("""
            SELECT status FROM fiscal_years WHERE year = ? FOR UPDATE
            """, (row, ignored) -> row.getString("status"), year);
        if (rows.isEmpty() || !"OPEN".equals(rows.getFirst())) {
            throw new IllegalStateException("Fiscal year is not open");
        }
    }
}
