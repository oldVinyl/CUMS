ALTER TABLE fiscal_year_snapshots ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0);
CREATE TABLE fiscal_snapshot_history (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 fiscal_year integer NOT NULL REFERENCES fiscal_years(year),
 member_id uuid NOT NULL REFERENCES members(id),
 principal numeric(19,4) NOT NULL,
 income numeric(19,4) NOT NULL,
 equity numeric(19,4) NOT NULL CHECK (equity = principal + income),
 revision integer NOT NULL,
 original_created_at timestamptz NOT NULL,
 original_created_by uuid NOT NULL REFERENCES users(id),
 archived_at timestamptz NOT NULL DEFAULT now(),
 archived_by uuid NOT NULL REFERENCES users(id),
 approval_request_id uuid NOT NULL REFERENCES approval_requests(id),
 UNIQUE(fiscal_year, member_id, revision)
);
CREATE TRIGGER fiscal_snapshot_history_append_only BEFORE UPDATE OR DELETE ON fiscal_snapshot_history
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
INSERT INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
 WHERE r.code='PRESIDENT' AND p.code='PERIOD_UNLOCK' ON CONFLICT DO NOTHING;
