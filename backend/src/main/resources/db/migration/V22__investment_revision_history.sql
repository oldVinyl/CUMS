ALTER TABLE investments ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0);
ALTER TABLE investment_maturity_allocations ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0);
ALTER TABLE investment_maturity_allocations DROP CONSTRAINT investment_maturity_allocations_pkey;
ALTER TABLE investment_maturity_allocations ADD PRIMARY KEY (investment_id, revision, member_id);

CREATE TABLE investment_revision_history (
    investment_id uuid NOT NULL REFERENCES investments(id),
    revision integer NOT NULL,
    snapshot jsonb NOT NULL,
    reason text NOT NULL CHECK (nullif(btrim(reason), '') IS NOT NULL),
    approval_request_id uuid NOT NULL REFERENCES approval_requests(id),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (investment_id, revision)
);
CREATE TRIGGER investment_revision_history_append_only BEFORE UPDATE OR DELETE
    ON investment_revision_history FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();

-- Loss reversals are positive. Require an exact inverse rather than assuming a sign.
DO $$ DECLARE constraint_name text; BEGIN
    SELECT conname INTO constraint_name FROM pg_constraint
    WHERE conrelid = 'ledger_entries'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%reversal_of%';
    EXECUTE format('ALTER TABLE ledger_entries DROP CONSTRAINT %I', constraint_name);
END $$;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_reversal_reason
    CHECK (type <> 'REVERSAL' OR (reversal_of IS NOT NULL AND nullif(btrim(narration), '') IS NOT NULL));

CREATE FUNCTION validate_ledger_compensation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original ledger_entries%ROWTYPE;
BEGIN
    IF NEW.type = 'REVERSAL' THEN
        SELECT * INTO original FROM ledger_entries WHERE id = NEW.reversal_of FOR SHARE;
        IF NOT FOUND OR original.type = 'REVERSAL'
           OR NEW.amount <> -original.amount
           OR NEW.member_id <> original.member_id OR NEW.component <> original.component
           OR NEW.investment_id IS DISTINCT FROM original.investment_id
           OR NEW.payment_id IS DISTINCT FROM original.payment_id THEN
            RAISE EXCEPTION 'Reversal must exactly compensate its original entry' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.reversal_of IS NOT NULL THEN
        RAISE EXCEPTION 'Only reversals may reference an original entry' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ledger_exact_compensation BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_compensation();
