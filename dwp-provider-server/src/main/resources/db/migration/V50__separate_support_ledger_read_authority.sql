-- Support request/session ledgers contain customer approval references, purpose,
-- actor, and post-access review evidence. Estate inventory visibility must not
-- imply access to that privileged ledger.

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, lifecycle_state, description)
VALUES (
    'SUPPORT_ACCESS_READ',
    'Read privileged support access ledger',
    'L2',
    'ACTIVE',
    'View support access requests and sessions under actor- and state-aware projection')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    lifecycle_state = 'ACTIVE',
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'SUPPORT_ACCESS_READ'),
    ('PROVIDER_SUPPORT', 'SUPPORT_ACCESS_READ'),
    ('PROVIDER_AUDITOR', 'SUPPORT_ACCESS_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;

DELETE FROM prv_operator_role_permissions
 WHERE permission_code = 'SUPPORT_ACCESS_READ'
   AND role_code NOT IN ('PROVIDER_ADMIN', 'PROVIDER_SUPPORT', 'PROVIDER_AUDITOR');
