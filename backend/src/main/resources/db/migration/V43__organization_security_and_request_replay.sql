ALTER TABLE organisation_settings
 ADD COLUMN password_min_length integer NOT NULL DEFAULT 12 CHECK(password_min_length BETWEEN 12 AND 64),
 ADD COLUMN session_idle_minutes integer NOT NULL DEFAULT 30 CHECK(session_idle_minutes BETWEEN 5 AND 120),
 ADD COLUMN primary_colour varchar(7) NOT NULL DEFAULT '#083a4f' CHECK(primary_colour ~ '^#[0-9a-fA-F]{6}$'),
 ADD COLUMN currency_display varchar(8) NOT NULL DEFAULT 'SYMBOL' CHECK(currency_display IN ('SYMBOL','CODE')),
 ADD COLUMN logo_png bytea;
CREATE TABLE financial_request_replays (
 actor_id uuid NOT NULL REFERENCES users(id), key_digest char(64) NOT NULL,
 request_digest char(64) NOT NULL, permission_digest char(64) NOT NULL,
 status integer NOT NULL CHECK(status BETWEEN 200 AND 299),
 result jsonb NOT NULL CHECK(jsonb_typeof(result)='object'),
 recorded_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(actor_id,key_digest)
);
CREATE TRIGGER financial_request_replays_append_only BEFORE UPDATE OR DELETE ON financial_request_replays
 FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
