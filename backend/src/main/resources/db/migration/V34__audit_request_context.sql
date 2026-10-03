ALTER TABLE audit_log ADD COLUMN request_ip inet;

CREATE FUNCTION enrich_audit_request_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.request_ip IS NULL THEN
        NEW.request_ip := nullif(current_setting('cums.request_ip', true), '')::inet;
    END IF;
    IF NEW.request_id IS NULL THEN
        NEW.request_id := nullif(current_setting('cums.request_id', true), '');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER audit_request_context BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION enrich_audit_request_context();
