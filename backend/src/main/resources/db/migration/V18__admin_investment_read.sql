-- Administrators can inspect financial cycles for oversight, without posting or approving money.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code = 'INVESTMENT_READ'
ON CONFLICT DO NOTHING;
