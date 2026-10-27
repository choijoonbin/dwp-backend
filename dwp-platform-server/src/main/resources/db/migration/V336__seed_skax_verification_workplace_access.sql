-- The bundled SKAX verification identity is a local, user-scoped fixture. Keep
-- the fail-closed workplace model intact for every other tenant and member,
-- while making the seeded workplace catalog usable by the verification flow.
-- BOOK is the least privilege that also inherits VIEW; catalog governance stays
-- behind its separate MANAGE permission.
WITH verification_identity AS (
    SELECT tenant.tenant_id, identity_link.user_id
      FROM sys_service_tenants tenant
      JOIN cal_identity_links identity_link
        ON identity_link.tenant_id = tenant.tenant_id
       AND identity_link.user_id = 900018
       AND identity_link.person_public_id =
           '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid
     WHERE tenant.tenant_key = 'default'
)
INSERT INTO wp_site_access_rules (
    access_rule_id, tenant_id, site_id, subject_type, subject_user_id,
    permission_code, effect, lifecycle_state, created_by, updated_by)
SELECT md5('workplace:verification-access:' || site.tenant_id || ':'
           || site.site_id || ':' || identity.user_id || ':BOOK')::uuid,
       site.tenant_id, site.site_id, 'USER', identity.user_id,
       'BOOK', 'ALLOW', 'ACTIVE', identity.user_id, identity.user_id
  FROM verification_identity identity
  JOIN wp_sites site
    ON site.tenant_id = identity.tenant_id
   AND site.lifecycle_state = 'ACTIVE'
ON CONFLICT DO NOTHING;
