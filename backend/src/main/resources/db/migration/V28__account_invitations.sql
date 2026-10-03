CREATE TABLE account_invitations (
 token_hash bytea PRIMARY KEY CHECK(octet_length(token_hash)=32),user_id uuid NOT NULL REFERENCES users(id),
 invited_by uuid NOT NULL REFERENCES users(id),expires_at timestamptz NOT NULL,consumed_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX account_invitations_active_idx ON account_invitations(user_id) WHERE consumed_at IS NULL;
