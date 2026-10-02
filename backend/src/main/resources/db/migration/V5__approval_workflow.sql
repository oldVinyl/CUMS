CREATE TABLE approval_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type varchar(80) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    made_by uuid NOT NULL REFERENCES users(id),
    required_approvals integer NOT NULL DEFAULT 1 CHECK (required_approvals BETWEEN 1 AND 3),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CHECK (btrim(action_type) <> ''),
    CHECK ((status = 'PENDING' AND resolved_at IS NULL)
        OR (status <> 'PENDING' AND resolved_at IS NOT NULL))
);
CREATE INDEX approval_requests_status_created_idx
    ON approval_requests (status, created_at DESC);
CREATE INDEX approval_requests_maker_idx
    ON approval_requests (made_by, created_at DESC);

CREATE TABLE approval_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL REFERENCES approval_requests(id),
    approver_id uuid NOT NULL REFERENCES users(id),
    decision varchar(20) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    comment text,
    decided_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (request_id, approver_id),
    CHECK (decision <> 'REJECT' OR nullif(btrim(comment), '') IS NOT NULL)
);
CREATE INDEX approval_decisions_request_idx ON approval_decisions (request_id, decided_at);

CREATE FUNCTION reject_self_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM approval_requests request
        WHERE request.id = NEW.request_id AND request.made_by = NEW.approver_id
    ) THEN
        RAISE EXCEPTION 'Maker cannot approve own request' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER approval_decisions_no_self_approval
    BEFORE INSERT OR UPDATE ON approval_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_self_approval();
