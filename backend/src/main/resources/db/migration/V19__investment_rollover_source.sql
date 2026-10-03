ALTER TABLE investments ADD COLUMN rollover_source_id uuid REFERENCES investments(id);
CREATE UNIQUE INDEX investments_active_rollover_source_uq
    ON investments (rollover_source_id)
    WHERE rollover_source_id IS NOT NULL AND status <> 'REVERSED';

CREATE VIEW investment_participation AS
SELECT a.member_id, sum(a.participation)::numeric(19,2) AS invested
FROM investment_allocations a
JOIN investments i ON i.id = a.investment_id
WHERE i.status = 'POSTED'
GROUP BY a.member_id;
