package com.dwp.services.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;

final class AppAdminPresetCatalogAuthorityFixture {

    private AppAdminPresetCatalogAuthorityFixture() {
    }

    static void materialize(
            JdbcTemplate jdbc, Long tenantId, Long roleId, String roleCode) {
        if (!"APP_CATALOG_ADMIN".equals(roleCode)) {
            return;
        }
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                SELECT ?, resource_type, resource_key, display_name, TRUE
                  FROM sys_tenant_resource_templates
                 WHERE resource_key = 'ADMIN.APP_GOVERNANCE'
                   AND lifecycle_state = 'ACTIVE'
                ON CONFLICT (tenant_id, type, key) DO UPDATE
                SET name = EXCLUDED.name, enabled = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                """, tenantId);
        jdbc.update("""
                INSERT INTO com_role_permissions (
                    tenant_id, role_id, resource_id, permission_id, effect)
                SELECT ?, ?, resource.resource_id, permission.permission_id, 'ALLOW'
                  FROM com_resources resource
                  JOIN com_permissions permission
                    ON permission.code IN ('VIEW', 'MANAGE')
                 WHERE resource.tenant_id = ?
                   AND resource.key = 'ADMIN.APP_GOVERNANCE'
                ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
                SET effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP
                """, tenantId, roleId, tenantId);
    }
}
