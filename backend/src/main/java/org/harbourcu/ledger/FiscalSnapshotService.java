package org.harbourcu.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds cumulative balances while retaining every superseded version. */
@Service
public class FiscalSnapshotService {
    private final JdbcTemplate jdbc;
    public FiscalSnapshotService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeFrom(int year, UUID actor, UUID request, boolean includeYear) {
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        List<Integer> years = jdbc.queryForList("""
            SELECT fy.year FROM fiscal_years fy WHERE fy.year >= ? AND
              (status = 'CLOSED' OR EXISTS(SELECT 1 FROM fiscal_year_snapshots s WHERE s.fiscal_year = fy.year)
               OR (? AND fy.year = ?)) ORDER BY fy.year FOR UPDATE
            """, Integer.class, year, includeYear, year);
        for (int current : years) {
            jdbc.update("""
                INSERT INTO fiscal_snapshot_history(fiscal_year,member_id,principal,income,equity,revision,
                  original_created_at,original_created_by,archived_by,approval_request_id)
                SELECT fiscal_year,member_id,principal,income,equity,revision,created_at,created_by,?,?
                  FROM fiscal_year_snapshots WHERE fiscal_year=?
                """, actor, request, current);
            jdbc.update("""
                INSERT INTO fiscal_year_snapshots(fiscal_year,member_id,principal,income,equity,created_by)
                SELECT ?,m.id,coalesce(sum(l.amount) FILTER(WHERE l.component='PRINCIPAL'),0),
                  coalesce(sum(l.amount) FILTER(WHERE l.component='INCOME'),0),coalesce(sum(l.amount),0),?
                FROM members m LEFT JOIN ledger_entries l ON l.member_id=m.id
                  AND l.effective_date<=make_date(?,12,31)
                WHERE m.join_date<=make_date(?,12,31) GROUP BY m.id
                ON CONFLICT(fiscal_year,member_id) DO UPDATE SET principal=excluded.principal,
                  income=excluded.income,equity=excluded.equity,created_by=excluded.created_by,
                  created_at=now(),revision=fiscal_year_snapshots.revision+1
                """, current, actor, current, current);
        }
        jdbc.update("""
            INSERT INTO audit_log(actor_id,action,entity_type,details)
            VALUES(?,'FISCAL_SNAPSHOTS_RECOMPUTED','FISCAL_YEAR',
              jsonb_build_object('fromYear',?,'years',?::jsonb,'approvalRequestId',?::text))
            """, actor, year, years.toString(), request.toString());
    }
}
