ALTER TABLE import_batches DROP CONSTRAINT import_batches_kind_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_kind_check CHECK(kind IN ('MEMBERS','LEDGER','INVESTMENTS'));
CREATE TABLE import_investment_results (
 batch_id uuid PRIMARY KEY REFERENCES import_batches(id),
 investment_id uuid NOT NULL UNIQUE REFERENCES investments(id)
);
CREATE TRIGGER import_investment_results_append_only BEFORE UPDATE OR DELETE ON import_investment_results
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
