package com.dwp.services.auth.tenantsettings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
class ProductCapabilityEntitlementRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    ProductCapabilityEntitlementRepository(
            JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    Map<Long, List<TenantSettingsDtos.AccessGrant>> grants(
            Long tenantId, List<Long> userIds) {
        Map<Long, List<TenantSettingsDtos.AccessGrant>> grants = new LinkedHashMap<>();
        userIds.forEach(id -> grants.put(id, new ArrayList<>()));
        if (userIds.isEmpty()) return grants;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("userIds", userIds);
        namedJdbc.query("""
                WITH effective_roles AS (
                    SELECT direct.user_id, direct.role_id
                      FROM com_role_members direct
                      JOIN com_roles role ON role.tenant_id = direct.tenant_id
                                         AND role.role_id = direct.role_id
                                         AND role.status = 'ACTIVE'
                     WHERE direct.tenant_id = :tenantId AND direct.user_id IN (:userIds)
                    UNION
                    SELECT member.user_id, assignment.role_id
                      FROM com_group_members member
                      JOIN com_groups access_group ON access_group.tenant_id = member.tenant_id
                       AND access_group.group_id = member.group_id AND access_group.status = 'ACTIVE'
                      JOIN com_group_role_assignments assignment
                        ON assignment.tenant_id = member.tenant_id
                       AND assignment.group_id = member.group_id
                      JOIN com_roles role ON role.tenant_id = assignment.tenant_id
                       AND role.role_id = assignment.role_id AND role.status = 'ACTIVE'
                     WHERE member.tenant_id = :tenantId AND member.user_id IN (:userIds)
                       AND assignment.lifecycle_state = 'ACTIVE'
                       AND assignment.assignment_type = 'ACTIVE'
                       AND assignment.scope_type = 'TENANT'
                       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
                       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                ), effective_permissions AS (
                    SELECT effective_role.user_id,
                           resource.key || ':' || permission.code AS permission_key
                      FROM effective_roles effective_role
                      JOIN com_role_permissions role_permission ON role_permission.tenant_id = :tenantId
                       AND role_permission.role_id = effective_role.role_id
                      JOIN com_resources resource ON resource.tenant_id = role_permission.tenant_id
                       AND resource.resource_id = role_permission.resource_id AND resource.enabled = TRUE
                      JOIN com_permissions permission
                        ON permission.permission_id = role_permission.permission_id
                     GROUP BY effective_role.user_id, resource.key, permission.code
                    HAVING BOOL_OR(role_permission.effect = 'ALLOW')
                       AND NOT BOOL_OR(role_permission.effect = 'DENY')
                ), active_capabilities AS (
                    SELECT capability.contract_key, capability.product_key,
                           capability.surface_key, capability.descriptor,
                           mapping.app_resource_key, bundle.activated_at
                      FROM auth_product_authorization_active active_pointer
                      JOIN auth_product_authorization_bundle bundle
                        ON bundle.bundle_id = active_pointer.bundle_id AND bundle.bundle_status = 'ACTIVE'
                      JOIN auth_product_capability_contract capability
                        ON capability.bundle_id = active_pointer.bundle_id
                       AND capability.lifecycle_state = 'ACTIVE'
                      JOIN sys_product_app_resource_mapping mapping
                        ON mapping.product_key = capability.product_key
                       AND mapping.lifecycle_state = 'ACTIVE'
                )
                SELECT permission.user_id, 'CAPABILITY'::text AS entitlement_type,
                       capability.contract_key AS entitlement_key,
                       COALESCE(capability.descriptor ->> 'displayName',
                                capability.descriptor ->> 'label', capability.contract_key) AS display_name,
                       CASE WHEN suppression.override_change_id IS NULL THEN 'PRODUCT_AUTHORIZATION'
                            ELSE 'TENANT_CAPABILITY_SUPPRESSION' END AS source_type,
                       capability.contract_key AS source_id,
                       capability.product_key || ' / ' || capability.surface_key AS source_name,
                       'APP'::text AS scope_type, capability.app_resource_key AS scope_ref,
                       CASE WHEN suppression.override_change_id IS NULL THEN 'PERMISSION_SATISFIED'
                            ELSE 'TENANT_DISABLED' END AS lifecycle_state,
                       capability.activated_at AS valid_from, suppression.valid_to,
                       COALESCE(capability.descriptor ->> 'riskTier', 'LOW')
                           IN ('HIGH', 'CRITICAL') AS privileged,
                       suppression.requested_by, suppression.approved_by, suppression.approved_at,
                       suppression.activated_by, suppression.activated_at,
                       CASE WHEN suppression.override_change_id IS NULL
                            THEN 'RUNTIME_EVALUATION_REQUIRED' ELSE 'TENANT_SUPPRESSED'
                       END AS approval_lineage_state
                  FROM effective_permissions permission
                  JOIN active_capabilities capability
                    ON permission.permission_key = capability.descriptor ->> 'resolvedCapabilityCode'
                  LEFT JOIN LATERAL (
                      SELECT change.override_change_id, change.valid_to, change.requested_by,
                             change.approved_by, change.approved_at, change.activated_by,
                             change.activated_at
                        FROM com_tenant_capability_override_changes change
                       WHERE change.tenant_id = :tenantId
                         AND change.contract_key = capability.contract_key
                         AND change.desired_state = 'DISABLED'
                         AND change.lifecycle_state = 'ACTIVE'
                         AND change.valid_to > CURRENT_TIMESTAMP
                       ORDER BY change.activated_at DESC LIMIT 1
                  ) suppression ON TRUE
                 WHERE NOT COALESCE(
                           (capability.descriptor ->> 'requiresProductEntitlement')::boolean, FALSE)
                    OR EXISTS (
                        SELECT 1 FROM effective_permissions app_permission
                         WHERE app_permission.user_id = permission.user_id
                           AND app_permission.permission_key = capability.app_resource_key || ':VIEW')
                 ORDER BY permission.user_id, capability.product_key,
                          capability.surface_key, capability.contract_key
                """, params, (RowCallbackHandler) result -> grants.computeIfAbsent(
                        result.getLong("user_id"), ignored -> new ArrayList<>()).add(grant(result)));
        return grants;
    }

    Instant freshest(Long tenantId) {
        return jdbc.query("""
                SELECT MAX(updated_at) FROM (
                    SELECT MAX(bundle.activated_at) AS updated_at
                      FROM auth_product_authorization_active active_pointer
                      JOIN auth_product_authorization_bundle bundle
                        ON bundle.bundle_id = active_pointer.bundle_id
                    UNION ALL
                    SELECT MAX(updated_at) FROM com_tenant_capability_override_changes
                     WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_role_members WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_group_members WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_group_role_assignments WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_role_permissions WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_resources WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_tenant_app_installations
                     WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_tenant_app_workforce_assignments
                     WHERE tenant_id = ?
                ) sources
                """, result -> result.next() ? instant(result, 1) : null,
                tenantId, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    private TenantSettingsDtos.AccessGrant grant(ResultSet result) throws SQLException {
        return new TenantSettingsDtos.AccessGrant(
                result.getString("entitlement_type"), result.getString("entitlement_key"),
                result.getString("display_name"), result.getString("source_type"),
                result.getString("source_id"), result.getString("source_name"),
                result.getString("scope_type"), result.getString("scope_ref"),
                result.getString("lifecycle_state"), instant(result, "valid_from"),
                instant(result, "valid_to"), result.getBoolean("privileged"),
                (Long) result.getObject("requested_by"), (Long) result.getObject("approved_by"),
                instant(result, "approved_at"), (Long) result.getObject("activated_by"),
                instant(result, "activated_at"), result.getString("approval_lineage_state"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return ((java.time.LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private Instant instant(ResultSet result, int column) throws SQLException {
        Object value = result.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return ((java.time.LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
}
