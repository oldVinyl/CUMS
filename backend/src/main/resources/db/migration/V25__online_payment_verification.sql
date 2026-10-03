ALTER TABLE payments ADD COLUMN checkout_url text;
ALTER TABLE payments ADD COLUMN fee_bearer varchar(10) NOT NULL DEFAULT 'UNION' CHECK(fee_bearer IN ('UNION','MEMBER'));
ALTER TABLE payments ADD COLUMN settled_amount numeric(19,2) CHECK(settled_amount>=0);
ALTER TABLE payments ADD COLUMN settled_date date;
CREATE TABLE payment_webhook_jobs (
 provider_reference varchar(120) PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT now(),
 processed_at timestamptz, attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now()
);
