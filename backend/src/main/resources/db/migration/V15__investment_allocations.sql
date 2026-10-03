CREATE TABLE investment_allocations (
    investment_id uuid NOT NULL REFERENCES investments(id),
    member_id uuid NOT NULL REFERENCES members(id),
    participation numeric(19,2) NOT NULL CHECK (participation > 0),
    ratio_basis_points integer NOT NULL CHECK (ratio_basis_points BETWEEN 0 AND 10000),
    cost_share numeric(19,2) NOT NULL CHECK (cost_share >= 0),
    maturity_value_share numeric(19,2) NOT NULL CHECK (maturity_value_share >= 0),
    income_share numeric(19,2) NOT NULL,
    closing_balance numeric(19,2) NOT NULL,
    edit_reason text,
    PRIMARY KEY (investment_id, member_id),
    CHECK (income_share = maturity_value_share - cost_share),
    CHECK (closing_balance = participation + income_share),
    CHECK (edit_reason IS NULL OR nullif(btrim(edit_reason), '') IS NOT NULL)
);
CREATE INDEX investment_allocations_member_idx ON investment_allocations (member_id);

CREATE FUNCTION protect_posted_allocations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM investments WHERE id = coalesce(OLD.investment_id, NEW.investment_id)
               AND status <> 'DRAFT') THEN
        RAISE EXCEPTION 'Posted investment allocations are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN coalesce(NEW, OLD);
END;
$$;
CREATE TRIGGER investment_allocations_protect BEFORE UPDATE OR DELETE ON investment_allocations
    FOR EACH ROW EXECUTE FUNCTION protect_posted_allocations();
