ALTER TABLE organisation_settings ADD COLUMN required_approvals integer NOT NULL DEFAULT 1 CHECK(required_approvals BETWEEN 1 AND 3);
ALTER TABLE organisation_settings ADD COLUMN fee_bearer varchar(10) NOT NULL DEFAULT 'UNION' CHECK(fee_bearer IN ('UNION','MEMBER'));
ALTER TABLE organisation_settings ADD COLUMN payment_min numeric(19,2) NOT NULL DEFAULT 1.00 CHECK(payment_min>0);
ALTER TABLE organisation_settings ADD COLUMN payment_max numeric(19,2) NOT NULL DEFAULT 1000000.00 CHECK(payment_max>=payment_min);
ALTER TABLE organisation_settings ADD COLUMN organisation_name varchar(200) NOT NULL DEFAULT 'Harbour Credit Union';
ALTER TABLE organisation_settings ADD COLUMN contact_email varchar(320);
ALTER TABLE organisation_settings ADD COLUMN contact_phone varchar(40);
