package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class ApprovalDraftRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectMapper canonicalMapper;

    public ApprovalDraftRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.canonicalMapper = mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public ApprovalWorkDtos.DraftState lock(ApprovalRequestContext.Actor actor, UUID requestId) {
        return state(actor, requestId, true);
    }

    public ApprovalWorkDtos.DraftState state(ApprovalRequestContext.Actor actor, UUID requestId, boolean lock) {
        var rows = jdbc.query("""
                SELECT request.request_id, request.version, payload.schema_version,
                       request.deleted_at, request.deleted_by
                  FROM apr_requests request
                  JOIN apr_tenants tenant ON tenant.tenant_id = request.tenant_id
                  JOIN apr_request_payloads payload
                    ON payload.tenant_id = request.tenant_id AND payload.request_id = request.request_id
                 WHERE request.tenant_id = :tenantId AND request.request_id = :requestId
                   AND request.requester_user_id = :userId AND tenant.lifecycle_state = 'ACTIVE'
                """ + (lock ? " FOR UPDATE OF request FOR SHARE OF tenant" : ""), params(actor, requestId),
                (rs, row) -> new ApprovalWorkDtos.DraftState(requestId, rs.getLong("version"),
                        rs.getInt("schema_version"), instant(rs, "deleted_at"),
                        rs.getObject("deleted_by", Long.class)));
        if (rows.isEmpty()) throw forbidden();
        return rows.getFirst();
    }

    public boolean isDraft(ApprovalRequestContext.Actor actor, UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT status = 'DRAFT' FROM apr_requests
                 WHERE tenant_id = :tenantId AND request_id = :requestId AND requester_user_id = :userId
                """, params(actor, requestId), Boolean.class));
    }

    public ResubmitSource lockResubmitSource(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion) {
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(actor.tenantId(), requestId);
        var rows = jdbc.query(
                ApprovalResubmitDraftSql.OWNED_TERMINAL_SOURCE,
                params(actor, requestId),
                (result, rowNumber) -> new ResubmitSource(
                        requestId,
                        result.getLong("version"),
                        result.getString("status"),
                        result.getObject("workflow_id", UUID.class),
                        result.getObject("form_id", UUID.class),
                        result.getString("title"),
                        result.getString("summary"),
                        result.getString("priority"),
                        object(result.getString("payload"))));
        if (rows.size() != 1) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The owned source approval request is unavailable.");
        }
        ResubmitSource source = rows.getFirst();
        if (source.version() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The source approval request version changed.");
        }
        if (!Set.of("APPROVED", "REJECTED").contains(source.status())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Only a terminal approved or rejected request can seed a resubmission draft.");
        }
        return source;
    }

    public ApprovalWorkDtos.Page<ApprovalWorkDtos.DraftRevision> revisions(
            ApprovalRequestContext.Actor actor, UUID requestId, int page, int size) {
        var parameters = params(actor, requestId).addValue("offset", (long) page * size).addValue("size", size);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM apr_request_payload_versions history
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                """, parameters, Long.class);
        var items = jdbc.query("""
                SELECT * FROM apr_request_payload_versions
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                 ORDER BY revision_number DESC LIMIT :size OFFSET :offset
                """, parameters, (rs, row) -> revision(rs));
        return ApprovalWorkDtos.Page.of(items, total == null ? 0 : total, page, size, Instant.now());
    }

    public ApprovalWorkDtos.DraftRevisionDetail revision(
            ApprovalRequestContext.Actor actor, UUID requestId, int revision) {
        var rows = jdbc.query("""
                SELECT * FROM apr_request_payload_versions
                 WHERE tenant_id = :tenantId AND request_id = :requestId AND revision_number = :revision
                """, params(actor, requestId).addValue("revision", revision),
                (rs, row) -> new ApprovalWorkDtos.DraftRevisionDetail(revision(rs),
                        object(rs.getString("payload")), object(rs.getString("draft_snapshot"))));
        if (rows.isEmpty()) throw forbidden();
        return rows.getFirst();
    }

    public void setDeleted(ApprovalRequestContext.Actor actor, UUID requestId,
                           long expectedVersion, boolean deleted, String reason) {
        int changed = jdbc.update("""
                UPDATE apr_requests
                   SET deleted_at = CASE WHEN :deleted THEN CURRENT_TIMESTAMP ELSE NULL END,
                       deleted_by = CASE WHEN :deleted THEN :userId ELSE NULL END,
                       deletion_reason = CASE WHEN :deleted THEN :reason ELSE NULL END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE tenant_id = :tenantId AND request_id = :requestId AND requester_user_id = :userId
                   AND status = 'DRAFT' AND version = :expectedVersion
                   AND ((:deleted AND deleted_at IS NULL) OR (NOT :deleted AND deleted_at IS NOT NULL))
                   AND EXISTS (SELECT 1 FROM apr_tenants WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE')
                """, params(actor, requestId).addValue("expectedVersion", expectedVersion)
                .addValue("deleted", deleted).addValue("reason", reason));
        if (changed != 1) throw conflict();
    }

    public Receipt begin(ApprovalRequestContext.Actor actor, String route, String type,
                         UUID requestId, String key, Object body, long version, Integer revision,
                         String correlationId) {
        String fingerprint = fingerprint(body);
        var parameters = params(actor, requestId).addValue("route", route).addValue("type", type)
                .addValue("key", key).addValue("fingerprint", fingerprint).addValue("version", version)
                .addValue("revision", revision).addValue("correlationId", correlationId);
        jdbc.update("""
                INSERT INTO apr_draft_commands (command_id,tenant_id,actor_user_id,request_id,
                    command_route,command_type,idempotency_key,fingerprint,expected_version,source_revision,correlation_id)
                VALUES (gen_random_uuid(),:tenantId,:userId,:requestId,:route,:type,:key,:fingerprint,:version,:revision,:correlationId)
                ON CONFLICT (tenant_id,actor_user_id,command_route,idempotency_key) DO NOTHING
                """, parameters);
        return jdbc.query("""
                SELECT command_id,fingerprint,result,request_id FROM apr_draft_commands
                 WHERE tenant_id = :tenantId AND actor_user_id = :userId AND command_route = :route AND idempotency_key = :key
                 FOR UPDATE
                """, parameters, rs -> {
                    if (!rs.next()) throw unavailable();
                    if (!fingerprint.equals(rs.getString("fingerprint").strip())) throw conflict();
                    return new Receipt(rs.getObject("command_id", UUID.class), rs.getString("result"),
                            rs.getObject("request_id", UUID.class));
                });
    }

    public void complete(Receipt receipt, Object response, ApprovalWorkDtos.DraftState state) {
        int changed = jdbc.update("""
                UPDATE apr_draft_commands SET result = CAST(:result AS jsonb), result_state = CAST(:state AS jsonb),
                    request_id = :requestId, completed_at = CURRENT_TIMESTAMP
                 WHERE command_id = :id AND result IS NULL
                """, new MapSqlParameterSource("id", receipt.id()).addValue("result", json(response))
                .addValue("state", json(state)).addValue("requestId", state.requestId()));
        if (changed != 1) throw conflict();
    }

    public ApprovalWorkDtos.DraftReconciliation reconcile(ApprovalRequestContext.Actor actor, String key) {
        var receipts = jdbc.query("""
                SELECT command.command_type,command.command_route,command.result_state,command.completed_at
                  FROM apr_draft_commands command
                  JOIN apr_requests request ON request.tenant_id = command.tenant_id AND request.request_id = command.request_id
                  JOIN apr_tenants tenant ON tenant.tenant_id = request.tenant_id
                 WHERE command.tenant_id = :tenantId AND command.actor_user_id = :userId
                   AND command.idempotency_key = :key AND command.result IS NOT NULL
                   AND request.requester_user_id = :userId AND tenant.lifecycle_state = 'ACTIVE'
                 ORDER BY command.completed_at,command.command_id
                """, params(actor, null).addValue("key", key), (rs, row) -> new ApprovalWorkDtos.DraftReceipt(
                        rs.getString("command_type"), rs.getString("command_route"),
                        read(rs.getString("result_state"), ApprovalWorkDtos.DraftState.class), instant(rs, "completed_at")));
        if (receipts.isEmpty()) throw forbidden();
        return new ApprovalWorkDtos.DraftReconciliation(key, List.copyOf(receipts));
    }

    public void event(ApprovalRequestContext.Actor actor, UUID requestId, String type, String reason,
                      String correlationId, Map<String, Object> data) {
        jdbc.update(ApprovalCommandSql02.APPEND_EVENT_INSERT_APR_REQUEST_EVENTS,
                params(actor, requestId).addValue("eventId", UUID.randomUUID()).addValue("eventType", type)
                .addValue("actorId", actor.userId().toString()).addValue("message", reason)
                .addValue("correlationId", correlationId).addValue("eventData", json(data)));
    }

    public <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException exception) { throw unavailable(); }
    }

    private Map<String, Object> object(String json) {
        if (json == null) return Map.of();
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw unavailable(); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw unavailable(); }
    }

    private String fingerprint(Object body) {
        // Sort object maps so replay does not depend on client property insertion order.
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonicalMapper.writeValueAsBytes(body)));
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException exception) { throw unavailable(); }
    }

    private ApprovalWorkDtos.DraftRevision revision(ResultSet rs) throws SQLException {
        boolean recoverable = rs.getString("draft_snapshot") != null;
        return new ApprovalWorkDtos.DraftRevision(rs.getInt("revision_number"), rs.getString("payload_sha256").strip(),
                rs.getString("change_type"), rs.getObject("changed_by", Long.class), rs.getString("change_reason"),
                instant(rs, "created_at"), recoverable, recoverable ? "SNAPSHOT_AVAILABLE" : "LEGACY_METADATA_UNAVAILABLE");
    }

    private Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private MapSqlParameterSource params(ApprovalRequestContext.Actor actor, UUID id) {
        return new MapSqlParameterSource("tenantId", actor.tenantId()).addValue("userId", actor.userId()).addValue("requestId", id);
    }

    private BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN, "The owned draft is unavailable."); }
    private BaseException conflict() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Draft command or version changed."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Draft command evidence is unavailable."); }

    public record Receipt(UUID id, String result, UUID requestId) {
        public boolean replay() { return result != null; }
    }

    public record ResubmitSource(
            UUID requestId,
            long version,
            String status,
            UUID workflowId,
            UUID formId,
            String title,
            String summary,
            String priority,
            Map<String, Object> payload) {
    }
}
