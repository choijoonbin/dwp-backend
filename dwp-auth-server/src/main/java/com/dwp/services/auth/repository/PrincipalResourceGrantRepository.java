package com.dwp.services.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PrincipalResourceGrantRepository {

    private final JdbcTemplate jdbc;

    public PrincipalResourceGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EffectiveGrant> findEffective(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT grant_record.principal_resource_grant_id::text AS grant_id,
                       resource.type AS resource_type, resource.key AS resource_key,
                       resource.name AS resource_name, permission.code AS permission_code,
                       permission.name AS permission_name, grant_record.effect
                  FROM com_principal_resource_grants grant_record
                  JOIN com_resources resource
                    ON resource.resource_id = grant_record.resource_id
                   AND resource.tenant_id = grant_record.tenant_id
                   AND resource.enabled = TRUE
                  JOIN com_permissions permission
                    ON permission.permission_id = grant_record.permission_id
                 WHERE grant_record.tenant_id = ?
                   AND grant_record.lifecycle_state = 'ACTIVE'
                   AND grant_record.valid_from <= CURRENT_TIMESTAMP
                   AND (grant_record.valid_to IS NULL
                        OR grant_record.valid_to > CURRENT_TIMESTAMP)
                   AND (
                       (grant_record.principal_type = 'USER'
                            AND grant_record.principal_ref = ?)
                       OR (grant_record.principal_type = 'GROUP' AND EXISTS (
                            SELECT 1
                              FROM com_group_members membership
                              JOIN com_groups access_group
                                ON access_group.tenant_id = membership.tenant_id
                               AND access_group.group_id = membership.group_id
                               AND access_group.status = 'ACTIVE'
                             WHERE membership.tenant_id = grant_record.tenant_id
                               AND membership.user_id = ?
                               AND membership.group_id::text = grant_record.principal_ref
                       ))
                   )
                 ORDER BY resource.key, permission.code, grant_record.created_at
                """, (result, ignored) -> new EffectiveGrant(
                        result.getString("grant_id"),
                        result.getString("resource_type"),
                        result.getString("resource_key"),
                        result.getString("resource_name"),
                        result.getString("permission_code"),
                        result.getString("permission_name"),
                        result.getString("effect")),
                tenantId, userId.toString(), userId);
    }

    public void lockSource(Long tenantId, String sourceType, String sourceRef) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null,
                tenantId + ":" + sourceType + ":" + sourceRef);
    }

    public Optional<GrantRecord> findBySource(
            Long tenantId, String sourceType, String sourceRef) {
        return jdbc.query("""
                SELECT grant_record.principal_resource_grant_id,
                       grant_record.tenant_id, grant_record.principal_type,
                       grant_record.principal_ref, resource.key AS resource_key,
                       permission.code AS permission_code, grant_record.source_type,
                       grant_record.source_ref, grant_record.lifecycle_state,
                       grant_record.valid_from, grant_record.valid_to,
                       grant_record.justification, grant_record.version
                  FROM com_principal_resource_grants grant_record
                  JOIN com_resources resource
                    ON resource.resource_id = grant_record.resource_id
                  JOIN com_permissions permission
                    ON permission.permission_id = grant_record.permission_id
                 WHERE grant_record.tenant_id = ?
                   AND grant_record.source_type = ?
                   AND grant_record.source_ref = ?
                """, (result, ignored) -> new GrantRecord(
                        result.getObject("principal_resource_grant_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getString("principal_type"),
                        result.getString("principal_ref"),
                        result.getString("resource_key"),
                        result.getString("permission_code"),
                        result.getString("source_type"),
                        result.getString("source_ref"),
                        result.getString("lifecycle_state"),
                        result.getObject("valid_from", OffsetDateTime.class),
                        result.getObject("valid_to", OffsetDateTime.class),
                        result.getString("justification"),
                        result.getLong("version")),
                tenantId, sourceType, sourceRef).stream().findFirst();
    }

    public GrantRecord grant(
            Long tenantId,
            String principalType,
            String principalRef,
            Long resourceId,
            Long permissionId,
            String sourceType,
            String sourceRef,
            OffsetDateTime validTo,
            String justification,
            Long actorId) {
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_principal_resource_grants (
                    principal_resource_grant_id, tenant_id, principal_type, principal_ref,
                    resource_id, permission_id, source_type, source_ref, valid_to,
                    justification, granted_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, grantId, tenantId, principalType, principalRef, resourceId,
                permissionId, sourceType, sourceRef, validTo, justification,
                actorId, actorId, actorId);
        return findBySource(tenantId, sourceType, sourceRef).orElseThrow();
    }

    public boolean revoke(
            Long tenantId,
            String sourceType,
            String sourceRef,
            Long actorId,
            String reason,
            long version) {
        return jdbc.update("""
                UPDATE com_principal_resource_grants
                   SET lifecycle_state = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                       revoked_by = ?, revocation_reason = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND source_type = ? AND source_ref = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, actorId, reason, actorId, tenantId, sourceType, sourceRef, version) == 1;
    }

    public boolean reactivate(
            Long tenantId,
            String sourceType,
            String sourceRef,
            OffsetDateTime validTo,
            String justification,
            Long actorId,
            long version) {
        return jdbc.update("""
                UPDATE com_principal_resource_grants
                   SET lifecycle_state = 'ACTIVE', valid_from = CURRENT_TIMESTAMP,
                       valid_to = ?, justification = ?, granted_by = ?,
                       revoked_at = NULL, revoked_by = NULL, revocation_reason = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND source_type = ? AND source_ref = ?
                   AND lifecycle_state IN ('EXPIRED', 'REVOKED') AND version = ?
                """, validTo, justification, actorId, actorId, tenantId,
                sourceType, sourceRef, version) == 1;
    }

    public List<GrantRecord> expireDue(int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT principal_resource_grant_id
                      FROM com_principal_resource_grants
                     WHERE lifecycle_state = 'ACTIVE'
                       AND valid_to IS NOT NULL
                       AND valid_to <= CURRENT_TIMESTAMP
                     ORDER BY valid_to, principal_resource_grant_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), expired AS (
                    UPDATE com_principal_resource_grants grant_record
                       SET lifecycle_state = 'EXPIRED', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = NULL
                      FROM due
                     WHERE grant_record.principal_resource_grant_id = due.principal_resource_grant_id
                    RETURNING grant_record.*
                )
                SELECT expired.principal_resource_grant_id, expired.tenant_id,
                       expired.principal_type, expired.principal_ref,
                       resource.key AS resource_key, permission.code AS permission_code,
                       expired.source_type, expired.source_ref, expired.lifecycle_state,
                       expired.valid_from, expired.valid_to, expired.justification,
                       expired.version
                  FROM expired
                  JOIN com_resources resource ON resource.resource_id = expired.resource_id
                  JOIN com_permissions permission ON permission.permission_id = expired.permission_id
                 ORDER BY expired.valid_to, expired.principal_resource_grant_id
                """, (result, ignored) -> new GrantRecord(
                        result.getObject("principal_resource_grant_id", UUID.class),
                        result.getLong("tenant_id"), result.getString("principal_type"),
                        result.getString("principal_ref"), result.getString("resource_key"),
                        result.getString("permission_code"), result.getString("source_type"),
                        result.getString("source_ref"), result.getString("lifecycle_state"),
                        result.getObject("valid_from", OffsetDateTime.class),
                        result.getObject("valid_to", OffsetDateTime.class),
                        result.getString("justification"), result.getLong("version")), limit);
    }

    public record EffectiveGrant(
            String grantId,
            String resourceType,
            String resourceKey,
            String resourceName,
            String permissionCode,
            String permissionName,
            String effect) {
    }

    public record GrantRecord(
            UUID grantId,
            Long tenantId,
            String principalType,
            String principalRef,
            String resourceKey,
            String permissionCode,
            String sourceType,
            String sourceRef,
            String lifecycleState,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            String justification,
            long version) {
    }
}
