package com.dwp.services.approval.auditrecords;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ApprovalAuditRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ApprovalAuditRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    List<RawEvent> search(
            ApprovalAuditModels.Scope scope,
            ApprovalAuditModels.SearchFilter filter,
            ApprovalAuditModels.AccessLevel accessLevel,
            int limit) {
        Query query = query(scope, filter, accessLevel, false);
        query.sql.append(" ORDER BY event.occurred_at DESC, event.event_id DESC LIMIT :limit");
        query.parameters.addValue("limit", limit);
        return jdbc.query(query.sql.toString(), query.parameters, this::event);
    }

    RawEvent eventById(ApprovalAuditModels.Scope scope, UUID eventId) {
        List<RawEvent> rows = jdbc.query("""
                SELECT event.event_id, event.request_id, request.request_number,
                       event.event_type, event.actor_type, event.actor_id,
                       event.outcome, event.message, event.event_data::text,
                       event.occurred_at, document.hold_active,
                       document.pending_hold_id, document.retain_until
                  FROM apr_request_events event
                  JOIN apr_requests request
                    ON request.tenant_id = event.tenant_id
                   AND request.request_id = event.request_id
                  LEFT JOIN apr_document_heads document
                    ON document.tenant_id = request.tenant_id
                   AND document.request_id = request.request_id
                 WHERE request.tenant_id = :tenant
                   AND request.management_resource_set_key = :scope
                   AND event.event_id = :eventId
                """, params(scope).addValue("eventId", eventId), this::event);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    ApprovalAuditModels.RetentionLinkage retentionByRequest(
            ApprovalAuditModels.Scope scope,
            UUID requestId) {
        List<ApprovalAuditModels.RetentionLinkage> rows = jdbc.query("""
                SELECT document.hold_active, document.pending_hold_id,
                       document.retain_until
                  FROM apr_requests request
                  LEFT JOIN apr_document_heads document
                    ON document.tenant_id = request.tenant_id
                   AND document.request_id = request.request_id
                 WHERE request.tenant_id = :tenant
                   AND request.management_resource_set_key = :scope
                   AND request.request_id = :requestId
                """, params(scope).addValue("requestId", requestId),
                (result, row) -> retention(result));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    int count(
            ApprovalAuditModels.Scope scope,
            ApprovalAuditModels.SearchFilter filter,
            ApprovalAuditModels.AccessLevel accessLevel) {
        Query query = query(scope, filter, accessLevel, true);
        Integer count = jdbc.queryForObject(query.sql.toString(), query.parameters, Integer.class);
        return count == null ? 0 : count;
    }

    void insertSavedView(
            ApprovalAuditModels.Scope scope,
            UUID id,
            String name,
            ApprovalAuditModels.Visibility visibility,
            ApprovalAuditModels.SearchFilter filter,
            Instant now) {
        jdbc.update("""
                INSERT INTO apr_audit_saved_views (
                    saved_view_id, tenant_id, management_resource_set_key,
                    owner_user_id, view_name, visibility, filter_payload,
                    lifecycle_state, created_at, updated_at)
                VALUES (:id, :tenant, :scope, :owner, :name, :visibility,
                    CAST(:filter AS jsonb), 'ACTIVE', :now, :now)
                """, params(scope)
                .addValue("id", id)
                .addValue("owner", scope.actorUserId())
                .addValue("name", name)
                .addValue("visibility", visibility.name())
                .addValue("filter", json(filter))
                .addValue("now", Timestamp.from(now)));
    }

    List<ApprovalAuditModels.SavedView> savedViews(ApprovalAuditModels.Scope scope) {
        return jdbc.query("""
                SELECT saved_view_id, view_name, visibility, filter_payload::text,
                       owner_user_id, version, updated_at
                  FROM apr_audit_saved_views
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND lifecycle_state = 'ACTIVE'
                   AND (visibility = 'SHARED' OR owner_user_id = :owner)
                 ORDER BY visibility DESC, lower(view_name), saved_view_id
                """, params(scope).addValue("owner", scope.actorUserId()), (result, row) ->
                new ApprovalAuditModels.SavedView(
                        result.getObject("saved_view_id", UUID.class),
                        result.getString("view_name"),
                        ApprovalAuditModels.Visibility.valueOf(result.getString("visibility")),
                        read(result.getString("filter_payload"), ApprovalAuditModels.SearchFilter.class),
                        result.getLong("owner_user_id"),
                        result.getLong("version"),
                        result.getTimestamp("updated_at").toInstant()));
    }

    ApprovalAuditModels.SavedView savedView(
            ApprovalAuditModels.Scope scope,
            UUID savedViewId) {
        return savedViews(scope).stream()
                .filter(view -> view.savedViewId().equals(savedViewId))
                .findFirst().orElse(null);
    }

    boolean exportRequestMatches(
            ApprovalAuditModels.Scope scope,
            UUID exportId,
            ApprovalAuditModels.AccessLevel level,
            ApprovalAuditModels.SearchFilter filter) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM apr_audit_export_jobs
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND export_id = :id
                   AND access_level = :level
                   AND filter_payload = CAST(:filter AS jsonb)
                """, params(scope)
                .addValue("id", exportId)
                .addValue("level", level.name())
                .addValue("filter", json(filter)), Integer.class);
        return count != null && count == 1;
    }

    void insertCompletedExport(
            ApprovalAuditModels.Scope scope,
            ApprovalAuditModels.AccessLevel level,
            ApprovalAuditModels.SearchFilter filter,
            ApprovalAuditModels.ExportManifest manifest,
            String manifestSha256,
            Map<String, Integer> retention,
            Instant now) {
        jdbc.update("""
                INSERT INTO apr_audit_export_jobs (
                    export_id, tenant_id, management_resource_set_key,
                    requested_by, access_level, filter_payload, status,
                    manifest_payload, manifest_sha256, exported_event_count,
                    integrity_status, retention_snapshot, requested_at, completed_at)
                VALUES (:id, :tenant, :scope, :actor, :level,
                    CAST(:filter AS jsonb), 'COMPLETE', CAST(:manifest AS jsonb),
                    :hash, :count, 'DIGEST_VERIFIED', CAST(:retention AS jsonb),
                    :now, :now)
                """, params(scope)
                .addValue("id", manifest.exportId())
                .addValue("actor", scope.actorUserId())
                .addValue("level", level.name())
                .addValue("filter", json(filter))
                .addValue("manifest", json(manifest))
                .addValue("hash", manifestSha256)
                .addValue("count", manifest.eventCount())
                .addValue("retention", json(retention))
                .addValue("now", Timestamp.from(now)));
    }

    ApprovalAuditModels.ExportReceipt export(
            ApprovalAuditModels.Scope scope,
            UUID exportId) {
        List<ApprovalAuditModels.ExportReceipt> rows = jdbc.query("""
                SELECT export_id, status, integrity_status, manifest_sha256,
                       manifest_payload::text, external_attestation_type,
                       external_attestation_reference, external_attested_at,
                       external_attestation_issuer, external_attestor_identity,
                       external_attestation_key_id,
                       external_verification_reference, version, completed_at
                  FROM apr_audit_export_jobs
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND export_id = :id
                """, params(scope).addValue("id", exportId), (result, row) ->
                new ApprovalAuditModels.ExportReceipt(
                        result.getObject("export_id", UUID.class),
                        result.getString("status"),
                        result.getString("integrity_status"),
                        result.getString("manifest_sha256"),
                        readNullable(result.getString("manifest_payload"),
                                ApprovalAuditModels.ExportManifest.class),
                        result.getString("external_attestation_type"),
                        result.getString("external_attestation_reference"),
                        instant(result, "external_attested_at"),
                        result.getString("external_attestation_issuer"),
                        result.getString("external_attestor_identity"),
                        result.getString("external_attestation_key_id"),
                        result.getString("external_verification_reference"),
                        result.getLong("version"),
                        instant(result, "completed_at")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    ApprovalAuditModels.VerificationReceipt insertVerification(
            ApprovalAuditModels.Scope scope,
            UUID exportId,
            ApprovalAuditModels.VerificationCommand command,
            String manifestSha256,
            String recomputedSha256,
            Instant now) {
        String outcome = manifestSha256.equals(recomputedSha256)
                ? "VERIFIED" : "DIGEST_MISMATCH";
        int inserted = jdbc.update("""
                INSERT INTO apr_audit_verification_receipts(
                    verification_id,tenant_id,management_resource_set_key,
                    export_id,export_version,manifest_sha256,recomputed_sha256,
                    result,verified_by,verified_at)
                SELECT :verification,:tenant,:scope,export_id,version,
                       manifest_sha256,:recomputed,:outcome,:actor,:now
                  FROM apr_audit_export_jobs
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND export_id=:export AND version=:version AND status='COMPLETE'
                   AND requested_by<>:actor
                ON CONFLICT DO NOTHING
                """, params(scope)
                .addValue("verification", command.verificationId())
                .addValue("export", exportId)
                .addValue("version", command.expectedExportVersion())
                .addValue("recomputed", recomputedSha256)
                .addValue("outcome", outcome)
                .addValue("actor", scope.actorUserId())
                .addValue("now", Timestamp.from(now)));
        if (inserted != 1) return null;
        return new ApprovalAuditModels.VerificationReceipt(
                command.verificationId(), exportId, command.expectedExportVersion(),
                manifestSha256, recomputedSha256, outcome, scope.actorUserId(), now);
    }

    List<ApprovalAuditModels.VerificationReceipt> verifications(
            ApprovalAuditModels.Scope scope,
            UUID exportId) {
        return jdbc.query("""
                SELECT verification_id,export_version,manifest_sha256,recomputed_sha256,
                       result,verified_by,verified_at
                  FROM apr_audit_verification_receipts
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND export_id=:export
                 ORDER BY verified_at DESC,verification_id DESC
                """, params(scope).addValue("export", exportId), (result, row) ->
                new ApprovalAuditModels.VerificationReceipt(
                        result.getObject("verification_id", UUID.class), exportId,
                        result.getLong("export_version"),
                        result.getString("manifest_sha256"),
                        result.getString("recomputed_sha256"), result.getString("result"),
                        result.getLong("verified_by"),
                        result.getTimestamp("verified_at").toInstant()));
    }

    boolean linkAttestation(
            ApprovalAuditModels.Scope scope,
            UUID exportId,
            long expectedVersion,
            ApprovalAuditModels.VerifiedExternalAttestation attestation) {
        return jdbc.update("""
                UPDATE apr_audit_export_jobs
                   SET integrity_status = 'EXTERNAL_ATTESTED',
                       external_attestation_type = :type,
                       external_attestation_reference = :reference,
                       external_attested_at = :attestedAt,
                       external_attestation_issuer = :issuer,
                       external_attestor_identity = :attestorIdentity,
                       external_attestation_key_id = :keyId,
                       external_verification_reference = :verificationReference,
                       version = version + 1
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND export_id = :id
                   AND status = 'COMPLETE'
                   AND integrity_status = 'DIGEST_VERIFIED'
                   AND version = :version
                """, params(scope)
                .addValue("id", exportId)
                .addValue("type", attestation.type())
                .addValue("reference", attestation.reference())
                .addValue("attestedAt", Timestamp.from(attestation.attestedAt()))
                .addValue("issuer", attestation.issuer())
                .addValue("attestorIdentity", attestation.attestorIdentity())
                .addValue("keyId", attestation.keyId())
                .addValue("verificationReference", attestation.verificationReference())
                .addValue("version", expectedVersion)) == 1;
    }

    private Query query(
            ApprovalAuditModels.Scope scope,
            ApprovalAuditModels.SearchFilter filter,
            ApprovalAuditModels.AccessLevel accessLevel,
            boolean count) {
        String select = count ? "SELECT count(*)" : """
                SELECT event.event_id, event.request_id, request.request_number,
                       event.event_type, event.actor_type, event.actor_id,
                       event.outcome, event.message, event.event_data::text,
                       event.occurred_at, document.hold_active,
                       document.pending_hold_id, document.retain_until
                """;
        StringBuilder sql = new StringBuilder(select).append("""
                  FROM apr_request_events event
                  JOIN apr_requests request
                    ON request.tenant_id = event.tenant_id
                   AND request.request_id = event.request_id
                  LEFT JOIN apr_document_heads document
                    ON document.tenant_id = request.tenant_id
                   AND document.request_id = request.request_id
                 WHERE request.tenant_id = :tenant
                   AND request.management_resource_set_key = :scope
                   AND event.occurred_at >= :from
                   AND event.occurred_at < :to
                """);
        MapSqlParameterSource parameters = params(scope)
                .addValue("from", Timestamp.from(filter.from()))
                .addValue("to", Timestamp.from(filter.to()));
        if (!filter.eventTypes().isEmpty()) {
            sql.append(" AND event.event_type IN (:eventTypes)");
            parameters.addValue("eventTypes", filter.eventTypes());
        }
        if (!filter.outcomes().isEmpty()) {
            sql.append(" AND event.outcome IN (:outcomes)");
            parameters.addValue("outcomes", filter.outcomes());
        }
        if (filter.requestId() != null) {
            sql.append(" AND event.request_id = :requestId");
            parameters.addValue("requestId", filter.requestId());
        }
        if (filter.text() != null && !filter.text().isBlank()) {
            if (accessLevel == ApprovalAuditModels.AccessLevel.METADATA) {
                sql.append("""
                         AND lower(concat_ws(' ', request.request_number,
                             event.event_type, event.outcome, event.actor_type)) LIKE :text
                        """);
            } else {
                sql.append("""
                         AND lower(concat_ws(' ', request.request_number,
                             event.event_type, event.outcome, event.actor_type,
                             event.message)) LIKE :text
                        """);
            }
            parameters.addValue("text", "%" + filter.text().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (!count && filter.cursor() != null) {
            sql.append("""
                     AND (event.occurred_at, event.event_id)
                         < (:cursorTime, :cursorId)
                    """);
            parameters.addValue("cursorTime", Timestamp.from(filter.cursor().occurredAt()));
            parameters.addValue("cursorId", filter.cursor().eventId());
        }
        return new Query(sql, parameters);
    }

    private RawEvent event(ResultSet result, int row) throws SQLException {
        return new RawEvent(
                result.getObject("event_id", UUID.class),
                result.getObject("request_id", UUID.class),
                result.getString("request_number"),
                result.getString("event_type"),
                result.getString("actor_type"),
                result.getString("actor_id"),
                result.getString("outcome"),
                result.getString("message"),
                readMap(result.getString("event_data")),
                retention(result),
                result.getTimestamp("occurred_at").toInstant());
    }

    private ApprovalAuditModels.RetentionLinkage retention(ResultSet result) throws SQLException {
        UUID pending = result.getObject("pending_hold_id", UUID.class);
        Boolean active = result.getObject("hold_active", Boolean.class);
        Instant retainUntil = instant(result, "retain_until");
        String status = active == null ? "NOT_LINKED"
                : Boolean.TRUE.equals(active) ? "LEGAL_HOLD_ACTIVE"
                : pending != null ? "LEGAL_HOLD_PENDING"
                : retainUntil != null ? "RETENTION_WINDOW_RECORDED"
                : "ELIGIBILITY_NOT_EVALUATED";
        return new ApprovalAuditModels.RetentionLinkage(
                status, Boolean.TRUE.equals(active), pending != null, retainUntil);
    }

    private MapSqlParameterSource params(ApprovalAuditModels.Scope scope) {
        return new MapSqlParameterSource()
                .addValue("tenant", scope.tenantId())
                .addValue("scope", scope.resourceSetKey());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("The audit value cannot be serialized.", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return mapper.readValue(value, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored audit evidence is invalid.", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored audit data is invalid.", exception);
        }
    }

    private <T> T readNullable(String value, Class<T> type) {
        return value == null ? null : read(value, type);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    record RawEvent(
            UUID eventId,
            UUID requestId,
            String requestNumber,
            String eventType,
            String actorType,
            String actorId,
            String outcome,
            String message,
            Map<String, Object> evidence,
            ApprovalAuditModels.RetentionLinkage retention,
            Instant occurredAt) {
    }

    private record Query(StringBuilder sql, MapSqlParameterSource parameters) {
    }
}
