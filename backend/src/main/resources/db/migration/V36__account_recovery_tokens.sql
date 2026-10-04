CREATE TABLE account_reset_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash bytea NOT NULL UNIQUE CHECK(octet_length(token_hash)=32),
    email_digest bytea NOT NULL CHECK(octet_length(email_digest)=32),
    mode varchar(20) NOT NULL CHECK(mode IN ('PASSWORD_ONLY','ALL_FACTORS')),
    approval_request_id uuid REFERENCES approval_requests(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK(expires_at>created_at),
    CHECK(mode<>'ALL_FACTORS' OR approval_request_id IS NOT NULL)
);
CREATE INDEX account_reset_active_idx ON account_reset_tokens(user_id,expires_at) WHERE consumed_at IS NULL;
