CREATE TABLE archived_cycle_corrections (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), investment_id uuid NOT NULL,
 revision integer NOT NULL, approval_request_id uuid NOT NULL REFERENCES approval_requests(id),
 ended_on date NOT NULL, actual_proceeds numeric(19,2),
 allocations jsonb NOT NULL CHECK(jsonb_typeof(allocations)='array'),
 maturity_allocations jsonb NOT NULL CHECK(jsonb_typeof(maturity_allocations)='array'),
 made_by uuid NOT NULL REFERENCES users(id),approved_by uuid NOT NULL REFERENCES users(id),
 recorded_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(investment_id,revision) REFERENCES investment_revision_history(investment_id,revision),
 UNIQUE(investment_id,revision,approval_request_id),CHECK(made_by<>approved_by),
 CHECK(actual_proceeds IS NULL OR actual_proceeds>=0)
);
CREATE TRIGGER archived_cycle_corrections_append_only BEFORE UPDATE OR DELETE ON archived_cycle_corrections
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
CREATE TABLE archived_income_adjustments (
 correction_id uuid NOT NULL REFERENCES archived_cycle_corrections(id),member_id uuid NOT NULL REFERENCES members(id),
 earned_entry_id uuid NOT NULL UNIQUE REFERENCES ledger_entries(id),
 compensation_entry_id uuid NOT NULL UNIQUE REFERENCES ledger_entries(id),
 PRIMARY KEY(correction_id,member_id),CHECK(earned_entry_id<>compensation_entry_id)
);
CREATE TRIGGER archived_income_adjustments_append_only BEFORE UPDATE OR DELETE ON archived_income_adjustments
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
