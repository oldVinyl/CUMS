ALTER TABLE investments ADD COLUMN actual_proceeds numeric(19,2)
    CHECK (actual_proceeds IS NULL OR actual_proceeds >= 0);
ALTER TABLE investments ADD CONSTRAINT investments_actual_proceeds_status
    CHECK ((status IN ('MATURED', 'ROLLED_OVER', 'SETTLED')) = (actual_proceeds IS NOT NULL));

CREATE TABLE investment_maturity_allocations (
    investment_id uuid NOT NULL REFERENCES investments(id),
    member_id uuid NOT NULL REFERENCES members(id),
    actual_maturity_share numeric(19,2) NOT NULL CHECK (actual_maturity_share >= 0),
    actual_income_share numeric(19,2) NOT NULL,
    PRIMARY KEY (investment_id, member_id)
);
CREATE TRIGGER investment_maturity_allocations_append_only
    BEFORE UPDATE OR DELETE ON investment_maturity_allocations
    FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
