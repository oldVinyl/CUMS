ALTER TABLE investments DROP CONSTRAINT investments_status_check;
ALTER TABLE investments ADD CONSTRAINT investments_status_check
    CHECK (status IN ('DRAFT', 'POSTED', 'MATURED', 'ROLLED_OVER',
                     'SETTLED', 'REVERSED', 'DISCARDED'));
DROP INDEX investments_active_rollover_source_uq;
CREATE UNIQUE INDEX investments_active_rollover_source_uq
    ON investments (rollover_source_id)
    WHERE rollover_source_id IS NOT NULL
      AND status NOT IN ('REVERSED', 'DISCARDED');
