CREATE TABLE passkey_credentials (
    credential_id bytea PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    public_key_cose bytea NOT NULL,
    signature_count bigint NOT NULL DEFAULT 0,
    backup_eligible boolean NOT NULL DEFAULT false,
    backed_up boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);
CREATE INDEX passkey_credentials_user_idx ON passkey_credentials (user_id);

CREATE TABLE passkey_ceremonies (
    token_hash bytea PRIMARY KEY,
    kind varchar(20) NOT NULL CHECK (kind IN ('REGISTER', 'ASSERT')),
    user_id uuid REFERENCES users(id),
    request_json text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX passkey_ceremonies_expiry_idx ON passkey_ceremonies (expires_at);
