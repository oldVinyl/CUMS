-- Financial records use UUID identities and NUMERIC(19,4); balances are derived from ledger entries.
CREATE TABLE members (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_number varchar(40) NOT NULL UNIQUE,
    full_name varchar(200) NOT NULL,
    email varchar(320),
    phone varchar(40),
    id_type varchar(60),
    encrypted_id_number bytea,
    date_of_birth date,
    address text,
    next_of_kin_name varchar(200),
    next_of_kin_phone varchar(40),
    next_of_kin_relationship varchar(80),
    join_date date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (btrim(member_number) <> ''),
    CHECK (btrim(full_name) <> '')
);
CREATE INDEX members_name_idx ON members (lower(full_name));

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id uuid UNIQUE REFERENCES members(id),
    email varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    totp_secret_ciphertext bytea,
    email_verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (btrim(email) <> '')
);
CREATE UNIQUE INDEX users_email_ci_uq ON users (lower(email));

CREATE TABLE roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (btrim(code) <> '')
);
CREATE TABLE permissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(80) NOT NULL UNIQUE,
    description text NOT NULL
);
CREATE TABLE role_permissions (
    role_id uuid NOT NULL REFERENCES roles(id),
    permission_id uuid NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);
CREATE TABLE user_role_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    role_id uuid NOT NULL REFERENCES roles(id),
    assigned_by uuid REFERENCES users(id),
    start_date date NOT NULL,
    end_date date,
    revoked_at timestamptz,
    revoked_by uuid REFERENCES users(id),
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date IS NULL OR end_date >= start_date),
    CHECK (revoked_at IS NULL OR (revoked_by IS NOT NULL AND nullif(btrim(reason), '') IS NOT NULL))
);
CREATE INDEX assignments_current_idx ON user_role_assignments (user_id, start_date, end_date) WHERE revoked_at IS NULL;

CREATE TABLE fiscal_years (
    year integer PRIMARY KEY CHECK (year BETWEEN 2000 AND 9999),
    start_date date NOT NULL UNIQUE,
    end_date date NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    closed_at timestamptz,
    closed_by uuid REFERENCES users(id),
    CHECK (start_date = make_date(year, 1, 1)),
    CHECK (end_date = make_date(year, 12, 31)),
    CHECK ((status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL)
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL))
);
INSERT INTO fiscal_years (year, start_date, end_date)
SELECT y, make_date(y, 1, 1), make_date(y, 12, 31)
FROM (SELECT extract(year FROM now() AT TIME ZONE 'Africa/Accra')::integer AS y) current_year;

CREATE TABLE investments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument varchar(40) NOT NULL DEFAULT 'TREASURY_BILL' CHECK (instrument = 'TREASURY_BILL'),
    tenor varchar(20) NOT NULL CHECK (tenor IN ('91_DAY', '182_DAY', '364_DAY', 'CUSTOM')),
    custom_days integer CHECK (custom_days IS NULL OR custom_days > 0),
    institution varchar(200),
    reference_number varchar(100) UNIQUE,
    purchase_date date NOT NULL,
    maturity_date date NOT NULL,
    cost numeric(19,4) NOT NULL CHECK (cost > 0),
    maturity_value numeric(19,4) NOT NULL CHECK (maturity_value >= 0),
    status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'POSTED', 'MATURED', 'ROLLED_OVER', 'SETTLED', 'REVERSED')),
    rollover_policy varchar(40) NOT NULL DEFAULT 'ROLL_PRINCIPAL_AND_INCOME'
        CHECK (rollover_policy IN ('ROLL_PRINCIPAL_AND_INCOME', 'ROLL_PRINCIPAL_ONLY', 'NO_ROLLOVER')),
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (maturity_date > purchase_date),
    CHECK ((tenor = 'CUSTOM') = (custom_days IS NOT NULL))
);

CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id uuid NOT NULL REFERENCES members(id),
    amount numeric(19,4) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL DEFAULT 'GHS' CHECK (currency = 'GHS'),
    method varchar(60) NOT NULL,
    provider varchar(60),
    provider_reference varchar(120) UNIQUE,
    internal_reference varchar(120) NOT NULL UNIQUE,
    status varchar(40) NOT NULL CHECK (status IN ('INITIATED', 'PENDING', 'CONFIRMED', 'FAILED', 'EXPIRED', 'ABANDONED', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED_PENDING_APPROVAL', 'POSTED', 'REJECTED')),
    purpose varchar(20) NOT NULL CHECK (purpose IN ('SEED', 'ADDITION')),
    fee_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    submitted_at timestamptz,
    confirmed_at timestamptz,
    rejection_reason text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payments_member_created_idx ON payments (member_id, created_at DESC);

CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id uuid NOT NULL REFERENCES members(id),
    type varchar(40) NOT NULL CHECK (type IN ('OPENING_BALANCE', 'SEED', 'ADDITION', 'INCOME_ALLOCATION', 'REVERSAL', 'WITHDRAWAL', 'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'FEE')),
    component varchar(20) NOT NULL CHECK (component IN ('PRINCIPAL', 'INCOME')),
    amount numeric(19,4) NOT NULL CHECK (amount <> 0),
    effective_date date NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    fiscal_year integer NOT NULL REFERENCES fiscal_years(year),
    investment_id uuid REFERENCES investments(id),
    payment_id uuid REFERENCES payments(id),
    reference varchar(120) NOT NULL UNIQUE,
    narration text,
    original_effective_date date,
    reversal_of uuid UNIQUE REFERENCES ledger_entries(id),
    created_by uuid REFERENCES users(id),
    approved_by uuid REFERENCES users(id),
    batch_id uuid,
    CHECK (effective_date <= (now() AT TIME ZONE 'Africa/Accra')::date),
    CHECK (fiscal_year = extract(year FROM effective_date)::integer),
    CHECK ((type = 'INCOME_ALLOCATION' AND component = 'INCOME') OR type <> 'INCOME_ALLOCATION'),
    CHECK (type <> 'REVERSAL' OR (amount < 0 AND reversal_of IS NOT NULL AND nullif(btrim(narration), '') IS NOT NULL))
);
CREATE INDEX ledger_member_effective_idx ON ledger_entries (member_id, effective_date, recorded_at);
CREATE INDEX ledger_effective_idx ON ledger_entries (effective_date);
CREATE INDEX ledger_investment_idx ON ledger_entries (investment_id) WHERE investment_id IS NOT NULL;
CREATE INDEX ledger_payment_idx ON ledger_entries (payment_id) WHERE payment_id IS NOT NULL;

CREATE FUNCTION reject_financial_row_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Posted financial records are append-only: %', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();

CREATE VIEW member_balances AS
SELECT member_id,
       coalesce(sum(amount) FILTER (WHERE component = 'PRINCIPAL'), 0)::numeric(19,4) AS principal,
       coalesce(sum(amount) FILTER (WHERE component = 'INCOME'), 0)::numeric(19,4) AS income,
       coalesce(sum(amount), 0)::numeric(19,4) AS equity
FROM ledger_entries GROUP BY member_id;

CREATE TABLE audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid REFERENCES users(id),
    action varchar(100) NOT NULL,
    entity_type varchar(100) NOT NULL,
    entity_id uuid,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    request_id varchar(100),
    CHECK (btrim(action) <> ''),
    CHECK (btrim(entity_type) <> '')
);
CREATE INDEX audit_occurred_idx ON audit_log (occurred_at DESC);
CREATE INDEX audit_entity_idx ON audit_log (entity_type, entity_id);
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_financial_row_change();
