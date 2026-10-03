ALTER TABLE passkey_credentials
    ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    ADD COLUMN revoked_at timestamptz;
CREATE INDEX passkey_credentials_active_user_idx ON passkey_credentials(user_id) WHERE revoked_at IS NULL;
