ALTER TABLE organisation_settings ADD COLUMN member_retention_years integer NOT NULL DEFAULT 7 CHECK(member_retention_years BETWEEN 1 AND 30);
ALTER TABLE members ADD COLUMN left_on date;
ALTER TABLE members ADD COLUMN anonymized_at timestamptz;
-- Existing inactive records deliberately have no inferred leaving date.
CREATE TABLE member_anonymizations (
 member_id uuid PRIMARY KEY REFERENCES members(id), approval_request_id uuid NOT NULL UNIQUE REFERENCES approval_requests(id),
 made_by uuid NOT NULL REFERENCES users(id), approved_by uuid NOT NULL REFERENCES users(id),
 left_on date NOT NULL, retention_years integer NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(),
 CHECK(made_by<>approved_by)
);
CREATE TRIGGER member_anonymizations_append_only BEFORE UPDATE OR DELETE ON member_anonymizations
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
CREATE TABLE retention_redactions (
 request_id uuid NOT NULL REFERENCES approval_requests(id), table_name text NOT NULL,
 old_hash bytea NOT NULL, new_hash bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(request_id,table_name,old_hash,new_hash)
);
CREATE TRIGGER retention_redactions_append_only BEFORE UPDATE OR DELETE ON retention_redactions
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
CREATE TABLE retention_receipt_deletions (
 attachment_id uuid PRIMARY KEY REFERENCES attachments(id), member_id uuid NOT NULL REFERENCES member_anonymizations(member_id),
 completed_at timestamptz, attempts integer NOT NULL DEFAULT 0
);
CREATE FUNCTION track_member_departure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.anonymized_at IS NOT NULL THEN RAISE EXCEPTION 'Anonymized membership cannot be edited or reactivated' USING ERRCODE='23514'; END IF;
 IF NEW.status IS DISTINCT FROM OLD.status THEN NEW.left_on := CASE WHEN NEW.status='INACTIVE' THEN current_date ELSE NULL END; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER member_departure BEFORE UPDATE ON members FOR EACH ROW EXECUTE FUNCTION track_member_departure();

CREATE FUNCTION retention_update_authorized(old_row jsonb,new_row jsonb,table_name text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM retention_redactions r JOIN member_anonymizations m ON m.approval_request_id=r.request_id
 JOIN approval_requests a ON a.id=r.request_id
 WHERE r.table_name=$3 AND r.request_id::text=current_setting('cums.retention_request',true)
 AND a.action_type='MEMBER_ANONYMIZE' AND a.status='PENDING'
 AND r.old_hash=sha256(convert_to(old_row::text,'UTF8')) AND r.new_hash=sha256(convert_to(new_row::text,'UTF8')))
$$;
CREATE FUNCTION retention_snapshot_financial(snapshot jsonb) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
 SELECT (snapshot #- '{investment,institution}' #- '{investment,reference_number}') || jsonb_build_object('allocations',
 coalesce((SELECT jsonb_agg(value-'edit_reason' ORDER BY value->>'member_id') FROM jsonb_array_elements(coalesce(snapshot->'allocations','[]'::jsonb))),'[]'::jsonb))
$$;
CREATE OR REPLACE FUNCTION reject_financial_row_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE removable text[]; financial_equal boolean;
BEGIN
 IF TG_OP='UPDATE' AND TG_TABLE_NAME IN ('ledger_entries','audit_log','import_rows','payment_events','investment_revision_history','payment_settlement_matches','reconciliations') THEN
  removable := CASE TG_TABLE_NAME WHEN 'ledger_entries' THEN ARRAY['narration','reference'] WHEN 'audit_log' THEN ARRAY['details','request_ip']
   WHEN 'import_rows' THEN ARRAY['encrypted_data'] WHEN 'payment_events' THEN ARRAY['details']
   WHEN 'investment_revision_history' THEN ARRAY['reason','snapshot'] WHEN 'payment_settlement_matches' THEN ARRAY['encrypted_notes'] WHEN 'reconciliations' THEN ARRAY['notes'] END;
  financial_equal := (to_jsonb(OLD)-removable)=(to_jsonb(NEW)-removable);
  IF TG_TABLE_NAME='investment_revision_history' THEN financial_equal := financial_equal AND retention_snapshot_financial(to_jsonb(OLD)->'snapshot')=retention_snapshot_financial(to_jsonb(NEW)->'snapshot'); END IF;
  IF financial_equal AND retention_update_authorized(to_jsonb(OLD),to_jsonb(NEW),TG_TABLE_NAME) THEN RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'Posted financial records are append-only: %', TG_TABLE_NAME USING ERRCODE='23514';
END $$;
CREATE OR REPLACE FUNCTION protect_posted_allocations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(OLD)-'edit_reason')=(to_jsonb(NEW)-'edit_reason')
  AND retention_update_authorized(to_jsonb(OLD),to_jsonb(NEW),'investment_allocations') THEN RETURN NEW; END IF;
 IF EXISTS(SELECT 1 FROM investments WHERE id=coalesce(OLD.investment_id,NEW.investment_id) AND status<>'DRAFT') THEN
  RAISE EXCEPTION 'Posted investment allocations are immutable' USING ERRCODE='23514';
 END IF;
 RETURN coalesce(NEW,OLD);
END $$;

CREATE FUNCTION prevent_anonymized_account_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='ACTIVE' AND EXISTS(SELECT 1 FROM member_anonymizations WHERE member_id=NEW.member_id) THEN
  RAISE EXCEPTION 'Anonymized membership cannot regain a portal account' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER anonymized_account_activation BEFORE INSERT OR UPDATE ON users
 FOR EACH ROW EXECUTE FUNCTION prevent_anonymized_account_activation();
