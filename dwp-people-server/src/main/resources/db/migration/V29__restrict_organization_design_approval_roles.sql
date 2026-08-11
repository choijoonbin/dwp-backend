UPDATE ppl_organization_scenario_approvals
   SET required_role_code = 'HR_ADMIN',
       version = version + 1
 WHERE required_role_code IN ('PEOPLE_ADMIN', 'TENANT_ADMIN', 'PLATFORM_ADMIN');

DELETE FROM ppl_approval_role_catalog
 WHERE role_code IN ('PEOPLE_ADMIN', 'TENANT_ADMIN', 'PLATFORM_ADMIN');

COMMENT ON TABLE ppl_approval_role_catalog IS
    'Product-owned roles permitted to approve workforce organization design decisions. Tenant and platform administration roles are intentionally excluded.';
