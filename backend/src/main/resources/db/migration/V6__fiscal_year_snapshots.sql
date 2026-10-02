CREATE TABLE fiscal_year_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fiscal_year integer NOT NULL REFERENCES fiscal_years(year),
    member_id uuid NOT NULL REFERENCES members(id),
    principal numeric(19,4) NOT NULL,
    income numeric(19,4) NOT NULL,
    equity numeric(19,4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL REFERENCES users(id),
    UNIQUE (fiscal_year, member_id),
    CHECK (equity = principal + income)
);
CREATE INDEX fiscal_snapshots_member_year_idx
    ON fiscal_year_snapshots (member_id, fiscal_year DESC);
