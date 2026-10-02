CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE user_role_assignments ADD CONSTRAINT assignments_no_active_overlap
    EXCLUDE USING gist (
        user_id WITH =,
        role_id WITH =,
        daterange(start_date, coalesce(end_date, 'infinity'::date), '[]') WITH &&
    ) WHERE (revoked_at IS NULL);

INSERT INTO permissions (code, description) VALUES
    ('MEMBER_READ', 'View member records'),
    ('MEMBER_WRITE', 'Create and edit member records'),
    ('MEMBER_IMPORT', 'Import member records'),
    ('LEDGER_READ', 'View all ledger entries'),
    ('LEDGER_POST', 'Submit ledger entries for approval'),
    ('LEDGER_BACKDATE', 'Submit backdated entries'),
    ('LEDGER_REVERSE', 'Reverse posted entries'),
    ('PAYMENT_VERIFY', 'Verify manual payment evidence'),
    ('INVESTMENT_READ', 'View investment cycles'),
    ('INVESTMENT_WRITE', 'Create and submit investment cycles'),
    ('CYCLE_REPOST', 'Reverse and repost investment cycles'),
    ('DASHBOARD_READ_ALL', 'View all-member dashboard'),
    ('REPORT_EXPORT', 'Export union reports'),
    ('RECONCILE', 'Reconcile gateway and bank payments'),
    ('FISCAL_YEAR_CLOSE', 'Submit fiscal-year close'),
    ('PERIOD_UNLOCK', 'Unlock a closed fiscal period'),
    ('APPROVE', 'Approve another user’s eligible request'),
    ('ROLE_ASSIGN', 'Assign and revoke roles'),
    ('USER_ADMIN', 'Administer user accounts'),
    ('SETTINGS_WRITE', 'Edit organisation settings'),
    ('AUDIT_READ', 'View audit records'),
    ('OWN_DATA_READ', 'View own member data');

INSERT INTO roles (code, name, description) VALUES
    ('ADMIN', 'Administrator', 'System and user administration; no money approval by default'),
    ('PRESIDENT', 'President', 'Union leadership and approval'),
    ('VICE_PRESIDENT', 'Vice President', 'Union leadership and approval'),
    ('TREASURER', 'Treasurer', 'Financial operations and approval of another maker'),
    ('AUDITOR', 'Auditor', 'Read-only union-wide oversight'),
    ('MEMBER', 'Member', 'Own records and payments only');

WITH grants(role_code, permission_code) AS (VALUES
    ('ADMIN', 'MEMBER_READ'), ('ADMIN', 'MEMBER_WRITE'), ('ADMIN', 'MEMBER_IMPORT'),
    ('ADMIN', 'DASHBOARD_READ_ALL'), ('ADMIN', 'REPORT_EXPORT'),
    ('ADMIN', 'ROLE_ASSIGN'), ('ADMIN', 'USER_ADMIN'), ('ADMIN', 'SETTINGS_WRITE'),
    ('ADMIN', 'AUDIT_READ'), ('ADMIN', 'OWN_DATA_READ'),

    ('PRESIDENT', 'MEMBER_READ'), ('PRESIDENT', 'MEMBER_WRITE'),
    ('PRESIDENT', 'LEDGER_READ'), ('PRESIDENT', 'INVESTMENT_READ'),
    ('PRESIDENT', 'DASHBOARD_READ_ALL'), ('PRESIDENT', 'REPORT_EXPORT'),
    ('PRESIDENT', 'APPROVE'), ('PRESIDENT', 'ROLE_ASSIGN'),
    ('PRESIDENT', 'SETTINGS_WRITE'), ('PRESIDENT', 'AUDIT_READ'),
    ('PRESIDENT', 'OWN_DATA_READ'),

    ('VICE_PRESIDENT', 'MEMBER_READ'), ('VICE_PRESIDENT', 'LEDGER_READ'),
    ('VICE_PRESIDENT', 'INVESTMENT_READ'), ('VICE_PRESIDENT', 'DASHBOARD_READ_ALL'),
    ('VICE_PRESIDENT', 'REPORT_EXPORT'), ('VICE_PRESIDENT', 'APPROVE'),
    ('VICE_PRESIDENT', 'OWN_DATA_READ'),

    ('TREASURER', 'MEMBER_READ'), ('TREASURER', 'MEMBER_WRITE'),
    ('TREASURER', 'LEDGER_READ'), ('TREASURER', 'LEDGER_POST'),
    ('TREASURER', 'LEDGER_BACKDATE'), ('TREASURER', 'LEDGER_REVERSE'),
    ('TREASURER', 'PAYMENT_VERIFY'), ('TREASURER', 'INVESTMENT_READ'),
    ('TREASURER', 'INVESTMENT_WRITE'), ('TREASURER', 'CYCLE_REPOST'),
    ('TREASURER', 'DASHBOARD_READ_ALL'), ('TREASURER', 'REPORT_EXPORT'),
    ('TREASURER', 'RECONCILE'), ('TREASURER', 'FISCAL_YEAR_CLOSE'),
    ('TREASURER', 'APPROVE'), ('TREASURER', 'OWN_DATA_READ'),

    ('AUDITOR', 'MEMBER_READ'), ('AUDITOR', 'LEDGER_READ'),
    ('AUDITOR', 'INVESTMENT_READ'), ('AUDITOR', 'DASHBOARD_READ_ALL'),
    ('AUDITOR', 'REPORT_EXPORT'), ('AUDITOR', 'AUDIT_READ'),
    ('AUDITOR', 'OWN_DATA_READ'),

    ('MEMBER', 'OWN_DATA_READ')
)
INSERT INTO role_permissions (role_id, permission_id)
SELECT roles.id, permissions.id FROM grants
JOIN roles ON roles.code = grants.role_code
JOIN permissions ON permissions.code = grants.permission_code;
