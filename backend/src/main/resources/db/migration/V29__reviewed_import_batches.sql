CREATE TABLE import_batches (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), kind varchar(20) NOT NULL CHECK(kind IN ('MEMBERS','LEDGER')),
 file_hash char(64) NOT NULL, row_count integer NOT NULL CHECK(row_count BETWEEN 1 AND 1000),
 total numeric(19,4) NOT NULL, made_by uuid NOT NULL REFERENCES users(id),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','POSTED','REJECTED','CANCELLED','REVERSED')),
 approval_request_id uuid UNIQUE REFERENCES approval_requests(id), reversal_request_id uuid UNIQUE REFERENCES approval_requests(id),
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 1000), created_at timestamptz NOT NULL DEFAULT now(),
 posted_at timestamptz, reversed_at timestamptz
);
CREATE UNIQUE INDEX import_active_file_uq ON import_batches(kind,file_hash) WHERE status IN ('PENDING','POSTED');
CREATE TABLE import_rows (
 batch_id uuid NOT NULL REFERENCES import_batches(id), row_number integer NOT NULL,
 encrypted_data bytea NOT NULL, PRIMARY KEY(batch_id,row_number)
);
CREATE TRIGGER import_rows_append_only BEFORE UPDATE OR DELETE ON import_rows
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
CREATE TABLE import_member_results (
 batch_id uuid NOT NULL REFERENCES import_batches(id), row_number integer NOT NULL,
 member_id uuid NOT NULL UNIQUE REFERENCES members(id), PRIMARY KEY(batch_id,row_number)
);
CREATE TRIGGER import_member_results_append_only BEFORE UPDATE OR DELETE ON import_member_results
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
CREATE INDEX import_batches_created_idx ON import_batches(created_at DESC,id);
INSERT INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code='TREASURER' AND p.code='MEMBER_IMPORT'
 ON CONFLICT DO NOTHING;
