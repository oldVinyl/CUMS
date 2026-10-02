ALTER TABLE consumed_refresh_tokens
    ADD COLUMN csrf_token_hash bytea CHECK (csrf_token_hash IS NULL OR octet_length(csrf_token_hash) = 32);
