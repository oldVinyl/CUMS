CREATE TABLE payment_settlement_matches (
 id uuid PRIMARY KEY,
 payment_id uuid NOT NULL REFERENCES payments(id),
 settled_amount numeric(19,2) NOT NULL CHECK(settled_amount>=0),
 settled_date date NOT NULL,
 source_fingerprint char(64) NOT NULL,
 encrypted_notes bytea NOT NULL,
 made_by uuid NOT NULL REFERENCES users(id),
 approval_request_id uuid NOT NULL UNIQUE REFERENCES approval_requests(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_settlement_matches_payment_idx ON payment_settlement_matches(payment_id,created_at DESC,id DESC);
CREATE TRIGGER payment_settlement_matches_immutable BEFORE UPDATE OR DELETE ON payment_settlement_matches FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
CREATE TRIGGER payment_settlement_matches_audit AFTER INSERT ON payment_settlement_matches FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('SETTLEMENT','id,payment_id,settled_amount,settled_date,made_by,approval_request_id','id');
