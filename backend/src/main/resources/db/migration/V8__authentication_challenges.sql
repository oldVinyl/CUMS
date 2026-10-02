ALTER TABLE users ADD COLUMN last_totp_step bigint CHECK (last_totp_step IS NULL OR last_totp_step >= 0);

CREATE TABLE auth_challenges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash bytea NOT NULL UNIQUE,
    purpose varchar(30) NOT NULL
        CHECK (purpose IN ('PASSWORD_CHANGE', 'TOTP_SETUP', 'TOTP_VERIFY')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    CHECK (octet_length(token_hash) = 32),
    CHECK (expires_at > created_at)
);
CREATE INDEX auth_challenges_user_active_idx ON auth_challenges (user_id, expires_at)
    WHERE consumed_at IS NULL;
