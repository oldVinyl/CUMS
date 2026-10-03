CREATE TABLE notifications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users(id),
 entity_id uuid NOT NULL, kind varchar(80) NOT NULL, outcome varchar(40) NOT NULL,
 payload jsonb NOT NULL DEFAULT '{}'::jsonb, status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','FAILED')),
 attempts integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(),sent_at timestamptz,next_attempt_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,entity_id,kind,outcome)
);
CREATE INDEX notifications_delivery_idx ON notifications(next_attempt_at) WHERE status='PENDING';
CREATE FUNCTION queue_payment_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status IN ('CONFIRMED','POSTED','FAILED','EXPIRED','ABANDONED','REJECTED') AND NEW.status IS DISTINCT FROM OLD.status THEN
  INSERT INTO notifications(user_id,entity_id,kind,outcome,payload)
  SELECT id,NEW.id,'PAYMENT',NEW.status,jsonb_build_object('reference',NEW.internal_reference)
  FROM users WHERE member_id=NEW.member_id AND status='ACTIVE' ON CONFLICT DO NOTHING;
 END IF; RETURN NEW;
END; $$;
CREATE TRIGGER payments_notify AFTER UPDATE OF status ON payments FOR EACH ROW EXECUTE FUNCTION queue_payment_notification();
CREATE FUNCTION queue_approval_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO notifications(user_id,entity_id,kind,outcome,payload)
  SELECT DISTINCT u.id,NEW.id,'APPROVAL','PENDING',jsonb_build_object('action',NEW.action_type)
  FROM users u JOIN user_role_assignments a ON a.user_id=u.id
  JOIN role_permissions rp ON rp.role_id=a.role_id JOIN permissions p ON p.id=rp.permission_id
  WHERE p.code='APPROVE' AND u.status='ACTIVE' AND u.id<>NEW.made_by AND a.revoked_at IS NULL
   AND a.start_date<=(now() AT TIME ZONE 'Africa/Accra')::date
   AND (a.end_date IS NULL OR a.end_date>=(now() AT TIME ZONE 'Africa/Accra')::date) ON CONFLICT DO NOTHING;
 ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
  INSERT INTO notifications(user_id,entity_id,kind,outcome,payload) VALUES(NEW.made_by,NEW.id,'APPROVAL',NEW.status,jsonb_build_object('action',NEW.action_type)) ON CONFLICT DO NOTHING;
 END IF; RETURN NEW;
END; $$;
CREATE TRIGGER approvals_notify AFTER INSERT OR UPDATE OF status ON approval_requests FOR EACH ROW EXECUTE FUNCTION queue_approval_notification();
