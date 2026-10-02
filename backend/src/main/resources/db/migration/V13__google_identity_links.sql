-- Google subjects are linked only after account authentication or an onboarding challenge.
ALTER TABLE users ADD COLUMN preferred_sign_in_method varchar(20)
    CHECK (preferred_sign_in_method IN ('TOTP', 'PASSKEY', 'GOOGLE'));

CREATE TABLE google_identity_links (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    google_subject varchar(255) NOT NULL UNIQUE,
    linked_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE google_sign_in_nonces (
    nonce_hash bytea PRIMARY KEY,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX google_sign_in_nonces_expiry_idx ON google_sign_in_nonces (expires_at);
