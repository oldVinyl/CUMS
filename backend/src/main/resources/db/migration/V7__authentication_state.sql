ALTER TABLE users
    ADD COLUMN locked_until timestamptz,
    ADD COLUMN password_changed_at timestamptz,
    ADD COLUMN force_password_change boolean NOT NULL DEFAULT true,
    ADD COLUMN totp_enabled_at timestamptz;

CREATE TABLE login_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id),
    account_digest bytea NOT NULL,
    ip_digest bytea NOT NULL,
    success boolean NOT NULL,
    attempted_at timestamptz NOT NULL DEFAULT now(),
    CHECK (octet_length(account_digest) = 32),
    CHECK (octet_length(ip_digest) = 32)
);
CREATE INDEX login_attempts_account_time_idx ON login_attempts (account_digest, attempted_at DESC);
CREATE INDEX login_attempts_ip_time_idx ON login_attempts (ip_digest, attempted_at DESC);
CREATE INDEX login_attempts_user_time_idx ON login_attempts (user_id, attempted_at DESC)
    WHERE user_id IS NOT NULL;

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    access_token_hash bytea NOT NULL UNIQUE,
    refresh_token_hash bytea NOT NULL UNIQUE,
    csrf_token_hash bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    access_expires_at timestamptz NOT NULL,
    refresh_expires_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CHECK (octet_length(access_token_hash) = 32),
    CHECK (octet_length(refresh_token_hash) = 32),
    CHECK (octet_length(csrf_token_hash) = 32),
    CHECK (access_expires_at > created_at),
    CHECK (refresh_expires_at > access_expires_at)
);
CREATE INDEX auth_sessions_user_active_idx ON auth_sessions (user_id, refresh_expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE consumed_refresh_tokens (
    token_hash bytea PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES auth_sessions(id),
    consumed_at timestamptz NOT NULL DEFAULT now(),
    CHECK (octet_length(token_hash) = 32)
);
CREATE INDEX consumed_refresh_session_idx ON consumed_refresh_tokens (session_id);

CREATE TABLE totp_recovery_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    code_hash bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    used_at timestamptz,
    UNIQUE (user_id, code_hash),
    CHECK (octet_length(code_hash) = 32)
);
