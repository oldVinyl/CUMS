CREATE SEQUENCE payment_reference_seq;
CREATE TABLE payment_methods (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name varchar(120) NOT NULL,
 instructions text NOT NULL, active boolean NOT NULL DEFAULT true,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE attachments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), member_id uuid NOT NULL REFERENCES members(id),
 uploaded_by uuid NOT NULL REFERENCES users(id), storage_key uuid NOT NULL UNIQUE,
 sha256 char(64) NOT NULL, content_type varchar(60) NOT NULL,
 byte_size bigint NOT NULL CHECK (byte_size > 0 AND byte_size <= 10485760),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX attachments_hash_idx ON attachments(sha256);
ALTER TABLE payments ADD COLUMN payment_method_id uuid REFERENCES payment_methods(id);
ALTER TABLE payments ADD COLUMN proof_id uuid REFERENCES attachments(id);
ALTER TABLE payments ADD COLUMN paid_date date;
ALTER TABLE payments ADD COLUMN verified_amount numeric(19,2) CHECK (verified_amount > 0);
ALTER TABLE payments ADD COLUMN effective_date date;
ALTER TABLE payments ADD COLUMN verified_by uuid REFERENCES users(id);
ALTER TABLE payments ADD COLUMN approval_request_id uuid UNIQUE REFERENCES approval_requests(id);
ALTER TABLE payments ADD COLUMN ledger_entry_id uuid UNIQUE REFERENCES ledger_entries(id);
ALTER TABLE payments ADD COLUMN submitted_by uuid REFERENCES users(id);
ALTER TABLE payments ADD COLUMN idempotency_key uuid;
CREATE UNIQUE INDEX payments_request_unique ON payments(submitted_by,idempotency_key);
CREATE UNIQUE INDEX ledger_payment_once ON ledger_entries(payment_id) WHERE payment_id IS NOT NULL AND type <> 'REVERSAL';
CREATE TABLE payment_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), payment_id uuid NOT NULL REFERENCES payments(id),
 actor_id uuid NOT NULL REFERENCES users(id), action varchar(60) NOT NULL, details jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER payment_events_append_only BEFORE UPDATE OR DELETE ON payment_events FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
