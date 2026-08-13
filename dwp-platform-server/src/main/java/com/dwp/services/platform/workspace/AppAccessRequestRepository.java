package com.dwp.services.platform.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AppAccessRequestRepository {

    private final JdbcTemplate jdbc;

    public AppAccessRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RequestRecord> latestOpen(Long tenantId, Long userId, String appKey) {
        return jdbc.query("""
                SELECT request.*, app.name_ko, app.name_en, app.resource_key
                  FROM usr_workspace_app_access_requests request
                  JOIN adm_workspace_apps app
                    ON app.tenant_id = request.tenant_id AND app.app_key = request.app_key
                 WHERE request.tenant_id = ? AND request.user_id = ? AND request.app_key = ?
                   AND request.request_state IN ('PENDING', 'APPROVED')
                   AND (request.requested_until IS NULL OR request.requested_until > CURRENT_TIMESTAMP)
                 ORDER BY request.created_at DESC
                 LIMIT 1
                """, this::map, tenantId, userId, appKey).stream().findFirst();
    }

    public List<RequestRecord> expiredCandidates(int limit) {
        return jdbc.query("""
                SELECT request.*, app.name_ko, app.name_en, app.resource_key
                  FROM usr_workspace_app_access_requests request
                  JOIN adm_workspace_apps app
                    ON app.tenant_id = request.tenant_id AND app.app_key = request.app_key
                 WHERE request.request_state IN ('PENDING', 'APPROVED')
                   AND request.requested_until <= CURRENT_TIMESTAMP
                 ORDER BY request.requested_until, request.created_at
                 LIMIT ?
                """, this::map, limit);
    }

    public List<RequestRecord> expiredCandidates(Long tenantId, Long userId, String appKey) {
        return jdbc.query("""
                SELECT request.*, app.name_ko, app.name_en, app.resource_key
                  FROM usr_workspace_app_access_requests request
                  JOIN adm_workspace_apps app
                    ON app.tenant_id = request.tenant_id AND app.app_key = request.app_key
                 WHERE request.tenant_id = ? AND request.user_id = ? AND request.app_key = ?
                   AND request.request_state IN ('PENDING', 'APPROVED')
                   AND request.requested_until <= CURRENT_TIMESTAMP
                 ORDER BY request.requested_until, request.created_at
                """, this::map, tenantId, userId, appKey);
    }

    public Optional<RequestRecord> request(Long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT request.*, app.name_ko, app.name_en, app.resource_key
                  FROM usr_workspace_app_access_requests request
                  JOIN adm_workspace_apps app
                    ON app.tenant_id = request.tenant_id AND app.app_key = request.app_key
                 WHERE request.tenant_id = ? AND request.app_access_request_id = ?
                """, this::map, tenantId, requestId).stream().findFirst();
    }

    public Optional<RequestRecord> requestForUpdate(Long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT request.*, app.name_ko, app.name_en, app.resource_key
                  FROM usr_workspace_app_access_requests request
                  JOIN adm_workspace_apps app
                    ON app.tenant_id = request.tenant_id AND app.app_key = request.app_key
                 WHERE request.tenant_id = ? AND request.app_access_request_id = ?
                 FOR UPDATE OF request
                """, this::map, tenantId, requestId).stream().findFirst();
    }

    public List<RequestRecord> list(Long tenantId, String state) {
        if (state == null || state.isBlank() || "ALL".equalsIgnoreCase(state)) {
            return jdbc.query("""
                    SELECT request.*, app.name_ko, app.name_en, app.resource_key
                      FROM usr_workspace_app_access_requests request
                      JOIN adm_workspace_apps app
                        ON app.tenant_id = request.tenant_id AND app.app_key = request.app_key
                     WHERE request.tenant_id = ?
                     ORDER BY CASE request.request_state WHEN 'PENDING' THEN 0 ELSE 1 END,
                              request.created_at DESC
                     LIMIT 500
                    """, this::map, tenantId);
        }
        return jdbc.query("""
                SELECT request.*, app.name_ko, app.name_en, app.resource_key
                  FROM usr_workspace_app_access_requests request
                  JOIN adm_workspace_apps app
                    ON app.tenant_id = request.tenant_id AND app.app_key = request.app_key
                 WHERE request.tenant_id = ? AND request.request_state = ?
                 ORDER BY request.created_at DESC
                 LIMIT 500
                """, this::map, tenantId, state);
    }

    public RequestRecord create(
            Long tenantId,
            Long userId,
            String appKey,
            String justification,
            OffsetDateTime requestedUntil) {
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usr_workspace_app_access_requests (
                    app_access_request_id, tenant_id, user_id, app_key,
                    justification, requested_until, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, tenantId, userId, appKey, justification,
                requestedUntil, userId, userId);
        return request(tenantId, requestId).orElseThrow();
    }

    public boolean cancel(
            Long tenantId,
            Long userId,
            UUID requestId,
            long version) {
        return jdbc.update("""
                UPDATE usr_workspace_app_access_requests
                   SET request_state = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND app_access_request_id = ? AND user_id = ?
                   AND request_state = 'PENDING' AND version = ?
                """, userId, tenantId, requestId, userId, version) == 1;
    }

    public boolean decide(
            Long tenantId,
            Long actorId,
            UUID requestId,
            String decision,
            String decisionNote,
            long version) {
        return jdbc.update("""
                UPDATE usr_workspace_app_access_requests
                   SET request_state = ?, decision_note = ?, decided_at = CURRENT_TIMESTAMP,
                       decided_by = ?, version = version + 1,
                       fulfillment_state = CASE WHEN ? = 'APPROVED' THEN 'PENDING'
                                                ELSE 'NOT_REQUIRED' END,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND app_access_request_id = ?
                   AND request_state = 'PENDING' AND version = ?
                   AND (requested_until IS NULL OR requested_until > CURRENT_TIMESTAMP)
                """, decision, decisionNote, actorId, decision, actorId,
                tenantId, requestId, version) == 1;
    }

    public boolean markFulfilled(
            Long tenantId,
            Long actorId,
            UUID requestId,
            String note,
            long version) {
        return jdbc.update("""
                UPDATE usr_workspace_app_access_requests
                   SET fulfillment_state = 'SUCCEEDED', fulfillment_attempts = fulfillment_attempts + 1,
                       fulfillment_note = ?, last_fulfillment_at = CURRENT_TIMESTAMP,
                       last_fulfillment_error = NULL, fulfilled_at = CURRENT_TIMESTAMP,
                       fulfilled_by = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND app_access_request_id = ?
                   AND request_state = 'APPROVED'
                   AND fulfillment_state IN ('PENDING', 'FAILED') AND version = ?
                """, note, actorId, actorId, tenantId, requestId, version) == 1;
    }

    public boolean markFulfillmentFailed(
            Long tenantId,
            Long actorId,
            UUID requestId,
            String note,
            String error,
            long version) {
        return jdbc.update("""
                UPDATE usr_workspace_app_access_requests
                   SET fulfillment_state = 'FAILED', fulfillment_attempts = fulfillment_attempts + 1,
                       fulfillment_note = ?, last_fulfillment_at = CURRENT_TIMESTAMP,
                       last_fulfillment_error = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND app_access_request_id = ?
                   AND request_state = 'APPROVED'
                   AND fulfillment_state IN ('PENDING', 'FAILED') AND version = ?
                """, note, error, actorId, tenantId, requestId, version) == 1;
    }

    public boolean revoke(
            Long tenantId,
            Long actorId,
            UUID requestId,
            String note,
            long version) {
        return jdbc.update("""
                UPDATE usr_workspace_app_access_requests
                   SET request_state = 'REVOKED', fulfillment_state = 'REVOKED',
                       revoked_at = CURRENT_TIMESTAMP, revoked_by = ?, revocation_note = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND app_access_request_id = ?
                   AND request_state = 'APPROVED' AND fulfillment_state = 'SUCCEEDED'
                   AND version = ?
                """, actorId, note, actorId, tenantId, requestId, version) == 1;
    }

    public boolean expire(Long tenantId, UUID requestId, long version) {
        return jdbc.update("""
                UPDATE usr_workspace_app_access_requests
                   SET request_state = 'EXPIRED',
                       fulfillment_state = CASE
                           WHEN fulfillment_state IN ('PENDING', 'FAILED', 'SUCCEEDED') THEN 'EXPIRED'
                           ELSE 'NOT_REQUIRED' END,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = NULL
                 WHERE tenant_id = ? AND app_access_request_id = ?
                   AND request_state IN ('PENDING', 'APPROVED') AND version = ?
                   AND requested_until <= CURRENT_TIMESTAMP
                """, tenantId, requestId, version) == 1;
    }

    private RequestRecord map(ResultSet result, int ignored) throws SQLException {
        return new RequestRecord(
                result.getObject("app_access_request_id", UUID.class),
                result.getLong("tenant_id"), result.getLong("user_id"),
                result.getString("app_key"), result.getString("name_ko"),
                result.getString("name_en"), result.getString("resource_key"),
                result.getString("requested_permission_code"),
                result.getString("justification"), result.getString("request_state"),
                result.getObject("requested_until", OffsetDateTime.class),
                result.getString("decision_note"),
                result.getObject("decided_at", OffsetDateTime.class),
                (Long) result.getObject("decided_by"),
                result.getString("fulfillment_state"),
                result.getInt("fulfillment_attempts"), result.getString("fulfillment_note"),
                result.getObject("last_fulfillment_at", OffsetDateTime.class),
                result.getString("last_fulfillment_error"),
                result.getObject("fulfilled_at", OffsetDateTime.class),
                (Long) result.getObject("fulfilled_by"),
                result.getObject("revoked_at", OffsetDateTime.class),
                (Long) result.getObject("revoked_by"), result.getString("revocation_note"),
                result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    public record RequestRecord(
            UUID requestId,
            Long tenantId,
            Long userId,
            String appKey,
            String appNameKo,
            String appNameEn,
            String resourceKey,
            String requestedPermissionCode,
            String justification,
            String state,
            OffsetDateTime requestedUntil,
            String decisionNote,
            OffsetDateTime decidedAt,
            Long decidedBy,
            String fulfillmentState,
            int fulfillmentAttempts,
            String fulfillmentNote,
            OffsetDateTime lastFulfillmentAt,
            String lastFulfillmentError,
            OffsetDateTime fulfilledAt,
            Long fulfilledBy,
            OffsetDateTime revokedAt,
            Long revokedBy,
            String revocationNote,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
