-- Explicit field allowlists exclude narrations, references, bank details, encrypted payloads and identity data.
CREATE TABLE financial_audit_changes (
    audit_id uuid PRIMARY KEY REFERENCES audit_log(id),
    operation varchar(6) NOT NULL CHECK(operation IN ('INSERT','UPDATE','DELETE')),
    transaction_id bigint NOT NULL,
    before_values jsonb,
    after_values jsonb,
    CHECK(before_values IS NOT NULL OR after_values IS NOT NULL)
);
CREATE TRIGGER financial_audit_changes_append_only BEFORE UPDATE OR DELETE ON financial_audit_changes
    FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();

CREATE FUNCTION audit_financial_row_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous jsonb; current_row jsonb; before_safe jsonb; after_safe jsonb;
        event_id uuid; entity uuid; actor uuid; allowed text[];
BEGIN
    allowed := string_to_array(TG_ARGV[1], ',');
    IF TG_OP <> 'INSERT' THEN
        previous := to_jsonb(OLD);
        SELECT coalesce(jsonb_object_agg(key, CASE WHEN value='null'::jsonb THEN 'null'::jsonb
            ELSE to_jsonb(value #>> '{}') END), '{}'::jsonb) INTO before_safe
            FROM jsonb_each(previous) WHERE key=ANY(allowed);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        current_row := to_jsonb(NEW);
        SELECT coalesce(jsonb_object_agg(key, CASE WHEN value='null'::jsonb THEN 'null'::jsonb
            ELSE to_jsonb(value #>> '{}') END), '{}'::jsonb) INTO after_safe
            FROM jsonb_each(current_row) WHERE key=ANY(allowed);
    END IF;
    -- Timestamp-only updates do not create misleading financial changes.
    IF TG_OP='UPDATE' AND before_safe IS NOT DISTINCT FROM after_safe THEN RETURN NEW; END IF;
    IF TG_ARGV[2] <> '' THEN entity := (coalesce(current_row,previous)->>TG_ARGV[2])::uuid; END IF;
    actor := nullif(current_setting('cums.actor_id',true),'')::uuid;
    IF actor IS NULL AND TG_TABLE_NAME='ledger_entries' THEN
        actor := coalesce((current_row->>'approved_by')::uuid,(current_row->>'created_by')::uuid);
    ELSIF actor IS NULL AND TG_TABLE_NAME='approval_decisions' THEN
        actor := (current_row->>'approver_id')::uuid;
    ELSIF actor IS NULL AND TG_TABLE_NAME='approval_requests' AND TG_OP='INSERT' THEN
        actor := (current_row->>'made_by')::uuid;
    END IF;
    INSERT INTO audit_log(actor_id,action,entity_type,entity_id)
        VALUES(actor,'FINANCIAL_'||TG_ARGV[0]||'_'||TG_OP,TG_ARGV[0],entity) RETURNING id INTO event_id;
    INSERT INTO financial_audit_changes(audit_id,operation,transaction_id,before_values,after_values)
        VALUES(event_id,TG_OP,txid_current(),before_safe,after_safe);
    RETURN coalesce(NEW,OLD);
END;
$$;

CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('LEDGER','id,member_id,type,component,amount,effective_date,original_effective_date,fiscal_year,investment_id,payment_id,reversal_of,created_by,approved_by,batch_id','id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON investments
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('INVESTMENT','id,instrument,tenor,custom_days,purchase_date,maturity_date,cost,maturity_value,status,rollover_policy,actual_proceeds,rollover_source_id,revision','id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON investment_allocations
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('ALLOCATION','investment_id,member_id,participation,ratio_basis_points,cost_share,maturity_value_share,income_share,closing_balance','investment_id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON investment_maturity_allocations
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('MATURITY_ALLOCATION','investment_id,member_id,actual_maturity_share,actual_income_share,revision','investment_id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON payments
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('PAYMENT','id,member_id,amount,currency,status,purpose,fee_amount,paid_date,verified_amount,effective_date,verified_by,approval_request_id,ledger_entry_id,fee_bearer,settled_amount,settled_date','id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON fiscal_years
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('FISCAL_YEAR','year,start_date,end_date,status,closed_at,closed_by','');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON fiscal_year_snapshots
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('FISCAL_SNAPSHOT','id,fiscal_year,member_id,principal,income,equity,revision','id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON organisation_settings
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('FINANCIAL_POLICY','default_rollover_policy,required_approvals,fee_bearer,payment_min,payment_max','');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON reconciliations
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('RECONCILIATION','id,as_at,actual_holdings,actual_cash,approval_request_id','id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON approval_requests
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('APPROVAL','id,action_type,status,made_by,required_approvals,version','id');
CREATE TRIGGER financial_change_audit AFTER INSERT OR UPDATE OR DELETE ON approval_decisions
 FOR EACH ROW EXECUTE FUNCTION audit_financial_row_change('APPROVAL_DECISION','id,request_id,approver_id,decision','id');
