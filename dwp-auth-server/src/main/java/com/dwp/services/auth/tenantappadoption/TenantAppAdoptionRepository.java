package com.dwp.services.auth.tenantappadoption;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TenantAppAdoptionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TenantAppAdoptionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean catalogProductExists(String productKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM auth_product_authorization_active active_pointer
                  JOIN auth_product_capability_contract capability
                    ON capability.bundle_id = active_pointer.bundle_id
                 WHERE capability.product_key = ? AND capability.lifecycle_state = 'ACTIVE'
                """, Integer.class, productKey);
        return count != null && count > 0;
    }

    public boolean resourceTemplateExists(String appResourceKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_tenant_resource_templates
                 WHERE resource_key = ? AND resource_type = 'APP' AND lifecycle_state = 'ACTIVE'
                """, Integer.class, appResourceKey);
        return count != null && count > 0;
    }

    public boolean activeUserExists(Long tenantId, Long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_users
                 WHERE tenant_id = ? AND user_id = ? AND status IN ('ACTIVE', 'INVITED')
                """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    public TenantAppAdoptionDtos.Installation insertInstallation(
            Long tenantId,
            Long actorId,
            TenantAppAdoptionDtos.CreateInstallationRequest request) {
        UUID id = UUID.randomUUID();
        String executorState = "EXTERNAL_SERVICE".equals(request.installationKind())
                ? "UNAVAILABLE" : "NOT_REQUIRED";
        jdbc.update("""
                INSERT INTO com_tenant_app_installations (
                    installation_id, tenant_id, product_key, app_resource_key,
                    installation_kind, external_executor_state, seat_capacity,
                    justification, requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, request.productKey(), request.appResourceKey(),
                request.installationKind(), executorState, request.seatCapacity(),
                request.justification().trim(), actorId, actorId, actorId);
        return requireInstallation(tenantId, id);
    }

    public List<TenantAppAdoptionDtos.Installation> installations(Long tenantId) {
        return jdbc.query("""
                SELECT installation.*,
                       COUNT(assignment.assignment_id) FILTER (
                           WHERE assignment.lifecycle_state IN ('APPROVED', 'ACTIVE'))
                           AS reserved_seats,
                       COUNT(assignment.assignment_id) FILTER (
                           WHERE assignment.lifecycle_state = 'ACTIVE'
                             AND (assignment.valid_to IS NULL
                                  OR assignment.valid_to > CURRENT_TIMESTAMP))
                           AS active_seats
                  FROM com_tenant_app_installations installation
                  LEFT JOIN com_tenant_app_workforce_assignments assignment
                    ON assignment.tenant_id = installation.tenant_id
                   AND assignment.installation_id = installation.installation_id
                 WHERE installation.tenant_id = ?
                 GROUP BY installation.installation_id
                 ORDER BY installation.updated_at DESC, installation.product_key
                """, this::installation, tenantId);
    }

    public TenantAppAdoptionDtos.Installation requireInstallation(
            Long tenantId, UUID installationId) {
        return jdbc.query("""
                SELECT installation.*,
                       COUNT(assignment.assignment_id) FILTER (
                           WHERE assignment.lifecycle_state IN ('APPROVED', 'ACTIVE'))
                           AS reserved_seats,
                       COUNT(assignment.assignment_id) FILTER (
                           WHERE assignment.lifecycle_state = 'ACTIVE'
                             AND (assignment.valid_to IS NULL
                                  OR assignment.valid_to > CURRENT_TIMESTAMP))
                           AS active_seats
                  FROM com_tenant_app_installations installation
                  LEFT JOIN com_tenant_app_workforce_assignments assignment
                    ON assignment.tenant_id = installation.tenant_id
                   AND assignment.installation_id = installation.installation_id
                 WHERE installation.tenant_id = ? AND installation.installation_id = ?
                 GROUP BY installation.installation_id
                """, this::installation, tenantId, installationId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public TenantAppAdoptionDtos.Installation lockInstallation(
            Long tenantId, UUID installationId) {
        jdbc.queryForObject("""
                SELECT installation_id FROM com_tenant_app_installations
                 WHERE tenant_id = ? AND installation_id = ? FOR UPDATE
                """, UUID.class, tenantId, installationId);
        return requireInstallation(tenantId, installationId);
    }

    public TenantAppAdoptionDtos.Installation transitionInstallation(
            Long tenantId,
            UUID installationId,
            long version,
            String requiredState,
            String nextState,
            Long actorId,
            String reason,
            UUID receiptId,
            Instant now) {
        int updated;
        if ("IN_REVIEW".equals(nextState)) {
            updated = jdbc.update("""
                    UPDATE com_tenant_app_installations
                       SET lifecycle_state = 'IN_REVIEW', submitted_at = ?,
                           version = version + 1, updated_at = ?, updated_by = ?
                     WHERE tenant_id = ? AND installation_id = ?
                       AND lifecycle_state = ? AND version = ?
                    """, timestamp(now), timestamp(now), actorId,
                    tenantId, installationId, requiredState, version);
        } else if ("APPROVED".equals(nextState) || "REJECTED".equals(nextState)) {
            updated = jdbc.update("""
                    UPDATE com_tenant_app_installations
                       SET lifecycle_state = ?, approved_by = ?, approved_at = ?,
                           decision_reason = ?, version = version + 1,
                           updated_at = ?, updated_by = ?
                     WHERE tenant_id = ? AND installation_id = ?
                       AND lifecycle_state = ? AND version = ? AND requested_by <> ?
                    """, nextState, actorId, timestamp(now), reason,
                    timestamp(now), actorId, tenantId, installationId,
                    requiredState, version, actorId);
        } else if ("ENABLED".equals(nextState)) {
            updated = jdbc.update("""
                    UPDATE com_tenant_app_installations
                       SET lifecycle_state = 'ENABLED', activated_by = ?, activated_at = ?,
                           activation_receipt_id = ?, version = version + 1,
                           updated_at = ?, updated_by = ?
                     WHERE tenant_id = ? AND installation_id = ?
                       AND lifecycle_state = ? AND version = ?
                       AND installation_kind = 'INTERNAL_AUTH_CONTROLLED'
                       AND requested_by <> ? AND approved_by <> ?
                    """, actorId, timestamp(now), receiptId, timestamp(now), actorId,
                    tenantId, installationId, requiredState, version, actorId, actorId);
        } else {
            throw new IllegalArgumentException("Unsupported installation transition.");
        }
        requireUpdated(updated);
        return requireInstallation(tenantId, installationId);
    }

    public TenantAppAdoptionDtos.Assignment insertAssignment(
            Long tenantId,
            Long actorId,
            TenantAppAdoptionDtos.CreateAssignmentRequest request,
            String settlementState) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_app_workforce_assignments (
                    assignment_id, tenant_id, installation_id, principal_type,
                    principal_ref, external_settlement_state, valid_to,
                    justification, requested_by, created_by, updated_by)
                VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, request.installationId(), String.valueOf(request.userId()),
                settlementState, timestamp(request.validTo()), request.justification().trim(),
                actorId, actorId, actorId);
        return requireAssignment(tenantId, id);
    }

    public List<TenantAppAdoptionDtos.Assignment> assignments(
            Long tenantId, UUID installationId) {
        return jdbc.query("""
                SELECT assignment.*, installation.product_key, user_record.display_name
                  FROM com_tenant_app_workforce_assignments assignment
                  JOIN com_tenant_app_installations installation
                    ON installation.tenant_id = assignment.tenant_id
                   AND installation.installation_id = assignment.installation_id
                  JOIN com_users user_record
                    ON user_record.tenant_id = assignment.tenant_id
                   AND user_record.user_id::text = assignment.principal_ref
                 WHERE assignment.tenant_id = ?
                   AND (?::uuid IS NULL OR assignment.installation_id = ?::uuid)
                 ORDER BY assignment.updated_at DESC, assignment.assignment_id
                """, this::assignment, tenantId, installationId, installationId);
    }

    public TenantAppAdoptionDtos.Assignment requireAssignment(Long tenantId, UUID assignmentId) {
        return jdbc.query("""
                SELECT assignment.*, installation.product_key, user_record.display_name
                  FROM com_tenant_app_workforce_assignments assignment
                  JOIN com_tenant_app_installations installation
                    ON installation.tenant_id = assignment.tenant_id
                   AND installation.installation_id = assignment.installation_id
                  JOIN com_users user_record
                    ON user_record.tenant_id = assignment.tenant_id
                   AND user_record.user_id::text = assignment.principal_ref
                 WHERE assignment.tenant_id = ? AND assignment.assignment_id = ?
                """, this::assignment, tenantId, assignmentId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public TenantAppAdoptionDtos.Assignment transitionAssignment(
            Long tenantId,
            UUID assignmentId,
            long version,
            String requiredState,
            String nextState,
            Long actorId,
            String reason,
            UUID receiptId,
            Instant now) {
        int updated;
        if ("APPROVED".equals(nextState) || "DENIED".equals(nextState)) {
            updated = jdbc.update("""
                    UPDATE com_tenant_app_workforce_assignments
                       SET lifecycle_state = ?, approved_by = ?, approved_at = ?,
                           decision_reason = ?, version = version + 1,
                           updated_at = ?, updated_by = ?
                     WHERE tenant_id = ? AND assignment_id = ?
                       AND lifecycle_state = ? AND version = ?
                       AND requested_by <> ? AND principal_ref <> ?
                    """, nextState, actorId, timestamp(now), reason,
                    timestamp(now), actorId, tenantId, assignmentId,
                    requiredState, version, actorId, String.valueOf(actorId));
        } else if ("ACTIVE".equals(nextState)) {
            updated = jdbc.update("""
                    UPDATE com_tenant_app_workforce_assignments
                       SET lifecycle_state = 'ACTIVE', valid_from = ?, activated_by = ?,
                           activated_at = ?, activation_receipt_id = ?,
                           version = version + 1, updated_at = ?, updated_by = ?
                     WHERE tenant_id = ? AND assignment_id = ?
                       AND lifecycle_state = ? AND version = ?
                       AND requested_by <> ? AND approved_by <> ? AND principal_ref <> ?
                    """, timestamp(now), actorId, timestamp(now), receiptId,
                    timestamp(now), actorId, tenantId, assignmentId,
                    requiredState, version, actorId, actorId, String.valueOf(actorId));
        } else if ("REVOKED".equals(nextState)) {
            updated = jdbc.update("""
                    UPDATE com_tenant_app_workforce_assignments
                       SET lifecycle_state = 'REVOKED', revoked_by = ?, revoked_at = ?,
                           revocation_reason = ?, version = version + 1,
                           updated_at = ?, updated_by = ?
                     WHERE tenant_id = ? AND assignment_id = ?
                       AND lifecycle_state IN ('APPROVED', 'ACTIVE') AND version = ?
                    """, actorId, timestamp(now), reason, timestamp(now), actorId,
                    tenantId, assignmentId, version);
        } else {
            throw new IllegalArgumentException("Unsupported assignment transition.");
        }
        requireUpdated(updated);
        return requireAssignment(tenantId, assignmentId);
    }

    public void appendEvent(
            Long tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Long actorId,
            String correlationId,
            long resultingVersion,
            Map<String, Object> evidence) {
        jdbc.update("""
                INSERT INTO com_tenant_app_adoption_events (
                    tenant_id, aggregate_type, aggregate_id, event_type, actor_id,
                    correlation_id, resulting_version, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, tenantId, aggregateType, aggregateId, eventType, actorId,
                correlationId, resultingVersion, json(evidence));
    }

    private TenantAppAdoptionDtos.Installation installation(ResultSet result, int ignored)
            throws SQLException {
        return new TenantAppAdoptionDtos.Installation(
                result.getObject("installation_id", UUID.class),
                result.getString("product_key"), result.getString("app_resource_key"),
                result.getString("installation_kind"), result.getString("lifecycle_state"),
                result.getString("external_executor_state"),
                (Integer) result.getObject("seat_capacity"),
                result.getLong("reserved_seats"), result.getLong("active_seats"),
                result.getString("justification"), result.getLong("requested_by"),
                instant(result, "submitted_at"), (Long) result.getObject("approved_by"),
                instant(result, "approved_at"), result.getString("decision_reason"),
                (Long) result.getObject("activated_by"), instant(result, "activated_at"),
                result.getObject("activation_receipt_id", UUID.class), result.getLong("version"),
                instant(result, "created_at"), instant(result, "updated_at"), List.of());
    }

    private TenantAppAdoptionDtos.Assignment assignment(ResultSet result, int ignored)
            throws SQLException {
        return new TenantAppAdoptionDtos.Assignment(
                result.getObject("assignment_id", UUID.class),
                result.getObject("installation_id", UUID.class), result.getString("product_key"),
                Long.valueOf(result.getString("principal_ref")), result.getString("display_name"),
                result.getString("lifecycle_state"), result.getInt("seat_quantity"),
                result.getString("source_type"), result.getString("external_settlement_state"),
                instant(result, "valid_from"), instant(result, "valid_to"),
                result.getString("justification"), result.getLong("requested_by"),
                (Long) result.getObject("approved_by"), instant(result, "approved_at"),
                result.getString("decision_reason"), (Long) result.getObject("activated_by"),
                instant(result, "activated_at"),
                result.getObject("activation_receipt_id", UUID.class),
                (Long) result.getObject("revoked_by"), instant(result, "revoked_at"),
                result.getString("revocation_reason"), result.getLong("version"),
                instant(result, "created_at"), instant(result, "updated_at"), List.of());
    }

    private void requireUpdated(int count) {
        if (count != 1) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The tenant app adoption record changed state or version. Refresh and retry.");
        }
    }

    private OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant app adoption evidence serialization failed.", exception);
        }
    }
}
