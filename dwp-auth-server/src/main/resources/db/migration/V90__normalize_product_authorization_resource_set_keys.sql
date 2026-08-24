-- Align physical resource-set keys with the canonical product-authorization
-- scope resolvers. The resource_set_id is the durable assignment boundary, so
-- this migration renames in place and never recreates sets, members, or duties.

CREATE TEMP TABLE tmp_product_authorization_resource_set_keys (
    legacy_key VARCHAR(80) PRIMARY KEY,
    canonical_key VARCHAR(80) UNIQUE NOT NULL,
    product_root_key VARCHAR(255) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_product_authorization_resource_set_keys VALUES
    ('APP_COMMUNICATIONS', 'RS_COMMUNICATIONS', 'APP.COMMUNICATIONS'),
    ('APP_EMPLOYEE_SERVICES', 'RS_SERVICES', 'APP.EMPLOYEE_SERVICES'),
    ('APP_APPROVALS', 'RS_APPROVALS', 'APP.APPROVALS'),
    ('APP_HRIS', 'RS_HCM_CONFIG', 'APP.HCM');

-- A second row already using the canonical key would make an in-place rename
-- ambiguous. Fail before any mutation instead of merging assignment scopes.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_admin_resource_sets legacy
          JOIN tmp_product_authorization_resource_set_keys mapping
            ON mapping.legacy_key = legacy.resource_set_key
          JOIN com_admin_resource_sets canonical
            ON canonical.tenant_id = legacy.tenant_id
           AND canonical.resource_set_key = mapping.canonical_key
           AND canonical.resource_set_id <> legacy.resource_set_id) THEN
        RAISE EXCEPTION
            'Canonical product authorization resource-set key collision';
    END IF;
END
$$;

UPDATE com_admin_resource_sets resource_set
   SET resource_set_key = mapping.canonical_key,
       version = resource_set.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_product_authorization_resource_set_keys mapping
 WHERE resource_set.resource_set_key = mapping.legacy_key;

-- Prove that each enabled product root resolves to one canonical physical set
-- and remains an ACTIVE member of that same stable resource_set_id. This is
-- evidence validation only; child ADMIN/ACTION resources are deliberately not
-- seeded into product sets.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_product_authorization_resource_set_keys mapping
          JOIN com_resources product_root
            ON product_root.type = 'APP'
           AND product_root.key = mapping.product_root_key
           AND product_root.enabled = TRUE
          LEFT JOIN com_admin_resource_sets resource_set
            ON resource_set.tenant_id = product_root.tenant_id
           AND resource_set.resource_set_key = mapping.canonical_key
           AND resource_set.resource_type = 'APP'
           AND resource_set.lifecycle_state = 'ACTIVE'
          LEFT JOIN com_admin_resource_set_members member
            ON member.tenant_id = product_root.tenant_id
           AND member.resource_set_id = resource_set.resource_set_id
           AND member.resource_type = 'APP'
           AND member.resource_key = mapping.product_root_key
           AND member.lifecycle_state = 'ACTIVE'
         WHERE resource_set.resource_set_id IS NULL
            OR member.resource_set_member_id IS NULL) THEN
        RAISE EXCEPTION
            'Canonical product authorization resource-set root evidence is incomplete';
    END IF;
END
$$;
