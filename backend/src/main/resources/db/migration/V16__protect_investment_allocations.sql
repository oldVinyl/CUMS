CREATE OR REPLACE FUNCTION protect_posted_allocations() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE investment_key uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        investment_key := NEW.investment_id;
    ELSE
        investment_key := OLD.investment_id;
    END IF;
    IF EXISTS (SELECT 1 FROM investments WHERE id = investment_key AND status <> 'DRAFT') THEN
        RAISE EXCEPTION 'Posted investment allocations are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER investment_allocations_protect ON investment_allocations;
CREATE TRIGGER investment_allocations_protect BEFORE INSERT OR UPDATE OR DELETE ON investment_allocations
    FOR EACH ROW EXECUTE FUNCTION protect_posted_allocations();
