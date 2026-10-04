CREATE TABLE auth_rate_buckets (
    bucket_digest bytea PRIMARY KEY CHECK (octet_length(bucket_digest)=32),
    tokens numeric(16,6) NOT NULL CHECK(tokens>=0),
    updated_at timestamptz NOT NULL
);
CREATE INDEX auth_rate_bucket_expiry_idx ON auth_rate_buckets(updated_at);

-- Row locking and the database clock give all application instances the same greedy bucket.
CREATE FUNCTION consume_auth_rate_token(digest bytea, capacity integer) RETURNS boolean
LANGUAGE plpgsql AS $$
DECLARE available numeric(16,6); previous_time timestamptz; refill_time timestamptz;
BEGIN
    IF capacity < 1 OR capacity > 1000 THEN RAISE EXCEPTION 'Invalid auth bucket capacity'; END IF;
    INSERT INTO auth_rate_buckets(bucket_digest,tokens,updated_at)
        VALUES(digest,capacity,clock_timestamp()) ON CONFLICT DO NOTHING;
    SELECT tokens,updated_at INTO available,previous_time FROM auth_rate_buckets
        WHERE bucket_digest=digest FOR UPDATE;
    refill_time:=clock_timestamp();
    available:=least(capacity,available+greatest(0,extract(epoch FROM refill_time-previous_time))*capacity/60.0);
    IF available>=1 THEN
        UPDATE auth_rate_buckets SET tokens=available-1,updated_at=refill_time WHERE bucket_digest=digest;
        RETURN true;
    END IF;
    UPDATE auth_rate_buckets SET tokens=available,updated_at=refill_time WHERE bucket_digest=digest;
    RETURN false;
END;
$$;
