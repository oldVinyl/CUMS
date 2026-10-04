CREATE TABLE payment_receipt_revisions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 payment_id uuid NOT NULL REFERENCES payments(id),
 previous_proof_id uuid NOT NULL REFERENCES attachments(id),
 new_proof_id uuid NOT NULL REFERENCES attachments(id),
 amended_by uuid NOT NULL REFERENCES users(id),
 amended_at timestamptz NOT NULL DEFAULT now(),
 CHECK(previous_proof_id<>new_proof_id),
 UNIQUE(payment_id,previous_proof_id),
 UNIQUE(payment_id,new_proof_id)
);
CREATE INDEX payment_receipt_revision_history_idx ON payment_receipt_revisions(payment_id,amended_at,id);
CREATE TRIGGER payment_receipt_revisions_append_only BEFORE UPDATE OR DELETE ON payment_receipt_revisions
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
