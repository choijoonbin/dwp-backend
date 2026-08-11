INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES (
    'CATALOG_READ',
    'Read product contract catalog',
    'L1',
    'Inspect global product code contracts, ownership, and enforcement evidence')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'CATALOG_READ'),
    ('PROVIDER_OPERATOR', 'CATALOG_READ'),
    ('PROVIDER_AUDITOR', 'CATALOG_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;
