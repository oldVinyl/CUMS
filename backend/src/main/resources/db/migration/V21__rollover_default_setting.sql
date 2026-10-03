CREATE TABLE organisation_settings (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    default_rollover_policy varchar(40) NOT NULL DEFAULT 'ROLL_PRINCIPAL_AND_INCOME'
        CHECK (default_rollover_policy IN
            ('ROLL_PRINCIPAL_AND_INCOME', 'ROLL_PRINCIPAL_ONLY', 'NO_ROLLOVER')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES users(id)
);
INSERT INTO organisation_settings (singleton) VALUES (true);
