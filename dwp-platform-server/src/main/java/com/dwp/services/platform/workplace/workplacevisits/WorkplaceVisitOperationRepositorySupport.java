package com.dwp.services.platform.workplace.workplacevisits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.VisitPreview;

class WorkplaceVisitOperationRepositorySupport {
    private final JdbcTemplate operationJdbc;
    private final ObjectMapper operationJson;

    WorkplaceVisitOperationRepositorySupport(JdbcTemplate operationJdbc, ObjectMapper operationJson) {
        this.operationJdbc = operationJdbc;
        this.operationJson = operationJson;
    }

    void lockCommand(long tenantId, long actorId, String scope, String key) {
        String identity = "workplace-visit:" + tenantId + ":" + actorId + ":"
                + scope.length() + ":" + scope + ":" + key.length() + ":" + key;
        operationJdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, identity), resultSet -> null);
    }

    Optional<PreviewCommandRow> previewCommand(
            long tenantId, long actorId, String key) {
        return operationJdbc.query("""
                SELECT request_fingerprint,response_snapshot
                  FROM wp_visit_preview_commands
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, (rs, n) -> new PreviewCommandRow(rs.getString("request_fingerprint"),
                        decodePreview(rs.getString("response_snapshot"))),
                tenantId, actorId, key).stream().findFirst();
    }

    void insertPreviewCommand(long tenantId, long actorId, String key, String fingerprint,
                              String correlationId, VisitPreview response, OffsetDateTime now) {
        operationJdbc.update("""
                INSERT INTO wp_visit_preview_commands(
                    command_id,tenant_id,actor_user_id,idempotency_key,request_fingerprint,
                    preview_id,response_snapshot,correlation_id,created_at)
                VALUES(?,?,?,?,?,?,?::jsonb,?,?)
                """, UUID.randomUUID(), tenantId, actorId, key, fingerprint,
                response.previewId(), encode(response), correlationId, now);
    }

    UUID outbox(long tenantId, UUID visitId, String operation, String deduplicationKey,
                String evidence, String state, OffsetDateTime now) {
        return outbox(tenantId, visitId, operation, deduplicationKey,
                null, evidence, state, now);
    }

    UUID outbox(long tenantId, UUID visitId, String operation, String deduplicationKey,
                UUID relatedOutboxId, String evidence, String state, OffsetDateTime now) {
        ProviderSnapshot provider = providerSnapshot(tenantId, operation, relatedOutboxId)
                .orElse(null);
        return insertOutbox(tenantId, visitId, operation, deduplicationKey,
                relatedOutboxId, evidence, state, now, provider);
    }

    UUID outboxWithProviderSnapshot(
            long tenantId, UUID visitId, String operation, String deduplicationKey,
            String evidence, String state, OffsetDateTime now, OutboxRow source) {
        ProviderSnapshot provider = source.providerKind() == null
                || source.providerCode() == null
                || source.providerConfigurationVersion() == null
                ? null : new ProviderSnapshot(source.providerKind(), source.providerCode(),
                source.providerConfigurationVersion());
        return insertOutbox(tenantId, visitId, operation, deduplicationKey,
                null, evidence, state, now, provider);
    }

    private UUID insertOutbox(
            long tenantId, UUID visitId, String operation, String deduplicationKey,
            UUID relatedOutboxId, String evidence, String state, OffsetDateTime now,
            ProviderSnapshot provider) {
        UUID outboxId = UUID.randomUUID();
        operationJdbc.update("""
                INSERT INTO wp_visit_outbox(
                    outbox_id,tenant_id,visit_id,operation_type,deduplication_key,
                    related_outbox_id,evidence_reference,delivery_state,next_attempt_at,
                    provider_kind,provider_code,provider_configuration_version,
                    created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,operation_type,deduplication_key) DO NOTHING
                """, outboxId, tenantId, visitId, operation, deduplicationKey,
                relatedOutboxId, evidence, state, now,
                provider == null ? null : provider.kind(),
                provider == null ? null : provider.code(),
                provider == null ? null : provider.configurationVersion(), now, now);
        return operationJdbc.queryForObject("""
                SELECT outbox_id FROM wp_visit_outbox
                 WHERE tenant_id=? AND operation_type=? AND deduplication_key=?
                """, UUID.class, tenantId, operation, deduplicationKey);
    }

    Optional<OutboxRow> outbox(long tenantId, UUID outboxId) {
        return operationJdbc.query("""
                SELECT * FROM wp_visit_outbox WHERE tenant_id=? AND outbox_id=?
                """, this::outboxRow, tenantId, outboxId).stream().findFirst();
    }

    Optional<OutboxRow> latestOutbox(long tenantId, UUID visitId, String operation) {
        return operationJdbc.query("""
                SELECT * FROM wp_visit_outbox
                 WHERE tenant_id=? AND visit_id=? AND operation_type=?
                 ORDER BY created_at DESC,outbox_id DESC LIMIT 1
                """, this::outboxRow, tenantId, visitId, operation).stream().findFirst();
    }

    List<OutboxRow> pendingOutbox(long tenantId, int limit) {
        return operationJdbc.query("""
                SELECT * FROM wp_visit_outbox
                 WHERE tenant_id=? AND delivery_state IN ('PENDING','RETRY')
                   AND next_attempt_at<=CURRENT_TIMESTAMP
                 ORDER BY created_at,outbox_id LIMIT ?
                """, this::outboxRow, tenantId, Math.max(1, Math.min(limit, 100)));
    }

    List<OutboxRow> pendingOutbox(int limit) {
        return operationJdbc.query("""
                SELECT * FROM wp_visit_outbox
                 WHERE delivery_state IN ('PENDING','RETRY')
                   AND next_attempt_at<=CURRENT_TIMESTAMP
                 ORDER BY next_attempt_at,created_at,outbox_id LIMIT ?
                """, this::outboxRow, Math.max(1, Math.min(limit, 500)));
    }

    List<OutboxRow> staleProcessing(OffsetDateTime before, int limit) {
        return operationJdbc.query("""
                SELECT * FROM wp_visit_outbox
                 WHERE delivery_state='PROCESSING' AND updated_at<?
                 ORDER BY updated_at,outbox_id LIMIT ?
                """, this::outboxRow, before, Math.max(1, Math.min(limit, 500)));
    }

    boolean claimOutbox(long tenantId, UUID outboxId, OffsetDateTime now) {
        return operationJdbc.update("""
                UPDATE wp_visit_outbox SET delivery_state='PROCESSING',
                       attempt_count=attempt_count+1,updated_at=?
                 WHERE tenant_id=? AND outbox_id=?
                   AND delivery_state IN ('PENDING','RETRY')
                """, now, tenantId, outboxId) == 1;
    }

    boolean completeOutbox(long tenantId, UUID outboxId, String state,
                           String evidenceReference, OffsetDateTime now) {
        return operationJdbc.update("""
                UPDATE wp_visit_outbox SET delivery_state=?,evidence_reference=?,updated_at=?
                 WHERE tenant_id=? AND outbox_id=? AND delivery_state='PROCESSING'
                """, state, evidenceReference, now, tenantId, outboxId) == 1;
    }

    boolean reconcileOutbox(long tenantId, UUID outboxId, String state,
                            String evidenceReference, OffsetDateTime now) {
        return operationJdbc.update("""
                UPDATE wp_visit_outbox SET delivery_state=?,evidence_reference=?,updated_at=?
                 WHERE tenant_id=? AND outbox_id=? AND delivery_state='RESULT_UNKNOWN'
                """, state, evidenceReference, now, tenantId, outboxId) == 1;
    }

    boolean retryOutbox(long tenantId, UUID outboxId, String evidenceReference,
                        OffsetDateTime nextAttemptAt, OffsetDateTime now) {
        return operationJdbc.update("""
                UPDATE wp_visit_outbox SET delivery_state='RETRY',evidence_reference=?,
                       next_attempt_at=?,updated_at=?
                 WHERE tenant_id=? AND outbox_id=? AND delivery_state='PROCESSING'
                """, evidenceReference, nextAttemptAt, now, tenantId, outboxId) == 1;
    }

    boolean retryStaleLookup(long tenantId, UUID outboxId, OffsetDateTime staleBefore,
                             OffsetDateTime nextAttemptAt, OffsetDateTime now) {
        return operationJdbc.update("""
                UPDATE wp_visit_outbox SET delivery_state='RETRY',next_attempt_at=?,updated_at=?
                 WHERE tenant_id=? AND outbox_id=? AND operation_type='CHECK_PROVIDER_STATUS'
                   AND delivery_state='PROCESSING' AND updated_at<?
                """, nextAttemptAt, now, tenantId, outboxId, staleBefore) == 1;
    }

    private Optional<ProviderSnapshot> providerSnapshot(
            long tenantId, String operation, UUID relatedOutboxId) {
        if ("CHECK_PROVIDER_STATUS".equals(operation) && relatedOutboxId != null) {
            return operationJdbc.query("""
                    SELECT provider_kind,provider_code,provider_configuration_version
                      FROM wp_visit_outbox
                     WHERE tenant_id=? AND outbox_id=?
                       AND provider_kind IS NOT NULL
                    """, (rs, n) -> new ProviderSnapshot(rs.getString(1), rs.getString(2),
                            rs.getLong(3)), tenantId, relatedOutboxId).stream().findFirst();
        }
        String kind = switch (operation) {
            case "SEND_INVITATION", "NOTIFY_HOST" -> "VISITOR";
            case "REQUEST_ACCESS", "REVOKE_ACCESS" -> "ACCESS";
            default -> null;
        };
        if (kind == null) return Optional.empty();
        return operationJdbc.query("""
                SELECT provider_kind,provider_code,configuration_version
                  FROM wp_visit_provider_bindings
                 WHERE tenant_id=? AND provider_kind=? AND active=TRUE
                """, (rs, n) -> new ProviderSnapshot(rs.getString(1), rs.getString(2),
                        rs.getLong(3)), tenantId, kind).stream().findFirst();
    }

    private OutboxRow outboxRow(ResultSet rs, int n) throws SQLException {
        return new OutboxRow(rs.getObject("outbox_id", UUID.class),
                rs.getLong("tenant_id"), rs.getObject("visit_id", UUID.class),
                rs.getString("operation_type"), rs.getString("deduplication_key"),
                rs.getObject("related_outbox_id", UUID.class),
                rs.getString("evidence_reference"), rs.getString("delivery_state"),
                rs.getInt("attempt_count"), rs.getString("provider_kind"),
                rs.getString("provider_code"),
                (Long) rs.getObject("provider_configuration_version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String encode(Object value) {
        try { return operationJson.writeValueAsString(value); }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid operation JSON", e);
        }
    }

    private VisitPreview decodePreview(String value) {
        try { return operationJson.readValue(value, VisitPreview.class); }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid preview receipt JSON", e);
        }
    }

    record PreviewCommandRow(String fingerprint, VisitPreview response) { }

    record OutboxRow(UUID id, long tenantId, UUID visitId, String operationType,
                     String deduplicationKey, UUID relatedOutboxId, String evidenceReference,
                     String deliveryState, int attemptCount, String providerKind,
                     String providerCode, Long providerConfigurationVersion,
                     OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    private record ProviderSnapshot(String kind, String code, long configurationVersion) { }
}
