-- Product permission opens the DWP HCM shell; these policies independently define
-- which tenant population and field groups each built-in operator may access.
INSERT INTO ppl_workforce_access_policies (
    tenant_id, subject_type, subject_ref, population_type,
    organization_public_id, field_groups, action_codes,
    justification, created_by, updated_by)
SELECT tenant.tenant_id, 'ROLE', seed.role_code, 'TENANT', NULL,
       seed.field_groups, seed.action_codes, seed.justification, 1, 1
  FROM (
        SELECT tenant_id FROM sys_service_tenants
        UNION
        SELECT DISTINCT tenant_id FROM ppl_persons
  ) tenant
 CROSS JOIN (VALUES
    ('HR_ADMIN',
     ARRAY['DIRECTORY', 'EMPLOYMENT', 'JOB_GRADE']::VARCHAR[],
     ARRAY['READ']::VARCHAR[],
     'Built-in HCM domain operations boundary without worker identifiers or export.'),
    ('PEOPLE_ADMIN',
     ARRAY['DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT', 'JOB_GRADE']::VARCHAR[],
     ARRAY['READ', 'EXPORT']::VARCHAR[],
     'Built-in Core HR administration boundary with governed export capability.')
 ) seed(role_code, field_groups, action_codes, justification)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE ppl_workforce_access_policies IS
    'Server-enforced HCM target population, field-group, and action boundary independent of product menu entitlement.';
