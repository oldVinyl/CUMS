CREATE TABLE reconciliations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), as_at date NOT NULL,
 actual_holdings numeric(19,2) NOT NULL CHECK(actual_holdings>=0),
 actual_cash numeric(19,2) NOT NULL CHECK(actual_cash>=0),
 snapshot jsonb NOT NULL, notes text NOT NULL CHECK(nullif(btrim(notes),'') IS NOT NULL),
 made_by uuid NOT NULL REFERENCES users(id), approval_request_id uuid NOT NULL UNIQUE REFERENCES approval_requests(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reconciliations_date_idx ON reconciliations(as_at DESC,created_at DESC);
CREATE TRIGGER reconciliations_append_only BEFORE UPDATE OR DELETE ON reconciliations FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
