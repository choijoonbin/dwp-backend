package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

@Repository
class JdbcReferenceConfigurationRepository implements ReferenceConfigurationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcReferenceConfigurationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void lockIdempotency(long tenantId, String idempotencyKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(?, ?)",
                resultSet -> null,
                Long.hashCode(tenantId),
                idempotencyKey.hashCode());
    }

    @Override
    public Optional<StoredReceipt> findReceipt(long tenantId, String idempotencyKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT request_digest, receipt_payload
                      FROM hris_reference_configuration_command_receipts
                     WHERE tenant_id = ? AND idempotency_key = ?
                    """, (rs, row) -> new StoredReceipt(
                            rs.getString("request_digest"),
                            read(rs.getString("receipt_payload"), CommandReceipt.class)),
                    tenantId, idempotencyKey));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public void saveReceipt(
            long tenantId,
            String idempotencyKey,
            String requestDigest,
            CommandReceipt receipt) {
        jdbc.update("""
                INSERT INTO hris_reference_configuration_command_receipts (
                    tenant_id, receipt_id, idempotency_key, request_digest,
                    command_type, configuration_id, configuration_version,
                    receipt_payload, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS uuid), ?, CAST(? AS jsonb), ?)
                """,
                tenantId,
                receipt.receiptId(),
                idempotencyKey,
                requestDigest,
                receipt.commandType(),
                resultConfigurationId(receipt.resultRef()),
                resultVersion(receipt.resultRef()),
                write(receipt),
                receipt.createdAt());
    }

    @Override
    public long createVersion(
            long tenantId,
            UUID configurationId,
            long actorId,
            CreateVersionCommand command,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO hris_reference_configurations (
                    tenant_id, configuration_id, configuration_key, domain,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, configuration_id) DO NOTHING
                """,
                tenantId,
                configurationId,
                command.schemaKey(),
                command.domain().name(),
                actorId,
                now,
                now);
        var owner = jdbc.queryForMap("""
                SELECT domain, configuration_key
                  FROM hris_reference_configurations
                 WHERE tenant_id = ? AND configuration_id = ?
                 FOR UPDATE
                """, tenantId, configurationId);
        if (!command.domain().name().equals(owner.get("domain"))) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_DOMAIN_CONFLICT");
        }
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = ? AND configuration_id = ?
                """, Long.class, tenantId, configurationId);
        long version = next == null ? 1 : next;
        jdbc.update("""
                INSERT INTO hris_reference_configuration_versions (
                    tenant_id, configuration_id, version, domain, lifecycle_state,
                    effective_from, effective_to, schema_key, schema_version,
                    payload_reference, payload_digest, aggregate_version,
                    authored_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """,
                tenantId,
                configurationId,
                version,
                command.domain().name(),
                command.effectiveFrom(),
                command.effectiveTo(),
                command.schemaKey(),
                command.schemaVersion(),
                command.payload().reference(),
                normalizeDigest(command.payload().digest()),
                actorId,
                now,
                now);
        jdbc.update("""
                UPDATE hris_reference_configurations
                   SET updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ?
                """, now, tenantId, configurationId);
        return version;
    }

    @Override
    public Optional<VersionDetail> findVersion(long tenantId, UUID configurationId, long version) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT configuration_id, version, domain, lifecycle_state,
                           effective_from, effective_to, schema_key, schema_version,
                           payload_reference, payload_digest, approval_receipt_id,
                           aggregate_version, authored_by, approved_by, scheduled_at,
                           supersedes_version, created_at, updated_at
                      FROM hris_reference_configuration_versions
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                    """, this::version, tenantId, configurationId, version));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public List<CatalogItem> list(long tenantId, int limit) {
        return jdbc.query("""
                SELECT c.configuration_id, c.configuration_key, v.domain,
                       v.lifecycle_state, v.version, v.updated_at
                  FROM hris_reference_configurations c
                  JOIN LATERAL (
                       SELECT domain, lifecycle_state, version, updated_at
                         FROM hris_reference_configuration_versions v
                        WHERE v.tenant_id = c.tenant_id
                          AND v.configuration_id = c.configuration_id
                        ORDER BY version DESC
                        LIMIT 1
                  ) v ON TRUE
                 WHERE c.tenant_id = ?
                 ORDER BY c.configuration_key, c.configuration_id
                 LIMIT ?
                """, (rs, row) -> new CatalogItem(
                        rs.getObject("configuration_id", UUID.class),
                        rs.getString("configuration_key"),
                        Domain.valueOf(rs.getString("domain")),
                        LifecycleState.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                tenantId, limit);
    }

    @Override
    public boolean transition(
            long tenantId,
            UUID configurationId,
            long version,
            long expectedAggregateVersion,
            LifecycleState expectedState,
            LifecycleState targetState,
            TransitionEvidence evidence,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE hris_reference_configuration_versions
                   SET lifecycle_state = ?,
                       aggregate_version = aggregate_version + 1,
                       validation_receipt_id = CASE WHEN ? = 'VALIDATED'
                           THEN ? ELSE validation_receipt_id END,
                       simulation_receipt_id = CASE WHEN ? = 'SIMULATED'
                           THEN ? ELSE simulation_receipt_id END,
                       approval_receipt_id = CASE WHEN ? = 'APPROVED'
                           THEN ? ELSE approval_receipt_id END,
                       retirement_approval_receipt_id = CASE WHEN ? = 'RETIRED'
                           THEN ? ELSE retirement_approval_receipt_id END,
                       approval_decision_digest = CASE WHEN ? = 'APPROVED'
                           THEN ? ELSE approval_decision_digest END,
                       approved_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_by END,
                       impact_digest = CASE WHEN ? = 'PENDING_APPROVAL'
                           THEN ? ELSE impact_digest END,
                       scheduled_at = COALESCE(?, scheduled_at),
                       effective_from = COALESCE(?, effective_from),
                       effective_to = COALESCE(?, effective_to),
                       replacement_version = COALESCE(?, replacement_version),
                       reason_code = COALESCE(?, reason_code),
                       updated_at = ?
                 WHERE tenant_id = ?
                   AND configuration_id = ?
                   AND version = ?
                   AND lifecycle_state = ?
                   AND aggregate_version = ?
                   AND (? <> 'SIMULATED' OR validation_receipt_id = ?)
                   AND (? <> 'PENDING_APPROVAL' OR (
                       validation_receipt_id = ? AND simulation_receipt_id = ?))
                   AND (? NOT IN ('SCHEDULED', 'PUBLISHED') OR (
                       approval_receipt_id = ? AND impact_digest = ?))
                """,
                targetState.name(),
                targetState.name(),
                evidence.validationReceiptId(),
                targetState.name(),
                evidence.simulationReceiptId(),
                targetState.name(),
                evidence.approvalReceiptId(),
                targetState.name(),
                evidence.approvalReceiptId(),
                targetState.name(),
                normalizeNullableDigest(evidence.approvalDecisionDigest()),
                targetState.name(),
                evidence.actorId(),
                targetState.name(),
                normalizeNullableDigest(evidence.impactDigest()),
                evidence.scheduledAt(),
                evidence.effectiveFrom(),
                evidence.effectiveTo(),
                evidence.replacementVersion(),
                evidence.reasonCode(),
                now,
                tenantId,
                configurationId,
                version,
                expectedState.name(),
                expectedAggregateVersion,
                targetState.name(),
                evidence.validationReceiptId(),
                targetState.name(),
                evidence.validationReceiptId(),
                evidence.simulationReceiptId(),
                targetState.name(),
                evidence.approvalReceiptId(),
                normalizeNullableDigest(evidence.impactDigest()));
        return changed == 1;
    }

    @Override
    public long rollback(
            long tenantId,
            UUID configurationId,
            long currentVersion,
            long targetVersion,
            long expectedAggregateVersion,
            long actorId,
            RollbackCommand command,
            OffsetDateTime now) {
        VersionDetail source = findVersion(tenantId, configurationId, targetVersion)
                .orElseThrow(() -> new IllegalStateException("ROLLBACK_TARGET_NOT_FOUND"));
        if (source.state() != LifecycleState.PUBLISHED && source.state() != LifecycleState.RETIRED) {
            throw new IllegalStateException("ROLLBACK_TARGET_NOT_PUBLISHED");
        }
        RollbackEvidence sourceEvidence = jdbc.queryForObject("""
                SELECT validation_receipt_id, simulation_receipt_id
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                """, (rs, row) -> new RollbackEvidence(
                        rs.getObject("validation_receipt_id", UUID.class),
                        rs.getObject("simulation_receipt_id", UUID.class)),
                tenantId, configurationId, targetVersion);
        int closed = jdbc.update("""
                UPDATE hris_reference_configuration_versions
                   SET lifecycle_state = 'RETIRED', effective_to = ?, reason_code = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                   AND lifecycle_state = 'PUBLISHED' AND aggregate_version = ?
                """, command.effectiveFrom(), command.reasonCode(), now,
                tenantId, configurationId, currentVersion, expectedAggregateVersion);
        if (closed != 1) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
        }
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = ? AND configuration_id = ?
                """, Long.class, tenantId, configurationId);
        long rollbackVersion = next == null ? currentVersion + 1 : next;
        jdbc.update("""
                INSERT INTO hris_reference_configuration_versions (
                    tenant_id, configuration_id, version, domain, lifecycle_state,
                    effective_from, effective_to, schema_key, schema_version,
                    payload_reference, payload_digest, validation_receipt_id,
                    simulation_receipt_id, approval_receipt_id, impact_digest,
                    aggregate_version, authored_by, approved_by,
                    supersedes_version, reason_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PUBLISHED', ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                configurationId,
                rollbackVersion,
                source.domain().name(),
                command.effectiveFrom(),
                source.schemaKey(),
                source.schemaVersion(),
                source.payloadReference(),
                source.payloadDigest(),
                sourceEvidence.validationReceiptId(),
                sourceEvidence.simulationReceiptId(),
                command.approvalReceiptId(),
                normalizeDigest(command.impactDigest()),
                source.authoredBy(),
                actorId,
                currentVersion,
                command.reasonCode(),
                now,
                now);
        return rollbackVersion;
    }

    @Override
    public void appendOutbox(
            long tenantId,
            UUID configurationId,
            long version,
            String eventType,
            String payload,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO hris_reference_configuration_outbox (
                    tenant_id, event_id, aggregate_id, aggregate_version,
                    event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, tenantId, UUID.randomUUID(), configurationId, version, eventType, payload, now);
    }

    @Override
    public void appendAudit(
            long tenantId,
            UUID configurationId,
            long version,
            String action,
            long actorId,
            String correlationId,
            String evidenceDigest,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO hris_reference_configuration_audit (
                    tenant_id, audit_id, configuration_id, configuration_version,
                    action, actor_id, correlation_id, evidence_digest, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS uuid), ?, ?)
                """, tenantId, UUID.randomUUID(), configurationId, version, action,
                actorId, correlationId, evidenceDigest, now);
    }

    private VersionDetail version(ResultSet rs, int row) throws SQLException {
        return new VersionDetail(
                rs.getObject("configuration_id", UUID.class),
                rs.getLong("version"),
                Domain.valueOf(rs.getString("domain")),
                LifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                rs.getString("schema_key"),
                rs.getInt("schema_version"),
                rs.getString("payload_reference"),
                rs.getString("payload_digest"),
                rs.getObject("approval_receipt_id", UUID.class),
                rs.getLong("aggregate_version"),
                nullableLong(rs, "authored_by"),
                nullableLong(rs, "approved_by"),
                rs.getObject("scheduled_at", OffsetDateTime.class),
                nullableLong(rs, "supersedes_version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String normalizeDigest(String digest) {
        return digest.startsWith("sha256:") ? digest.substring(7).toLowerCase() : digest.toLowerCase();
    }

    private String normalizeNullableDigest(String digest) {
        return digest == null ? null : normalizeDigest(digest);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_SERIALIZATION_FAILED", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_RECEIPT_INVALID", exception);
        }
    }

    private UUID resultConfigurationId(String resultRef) {
        return UUID.fromString(resultRef.split("/", -1)[0]);
    }

    private long resultVersion(String resultRef) {
        return Long.parseLong(resultRef.split("/", -1)[1]);
    }

    private record RollbackEvidence(UUID validationReceiptId, UUID simulationReceiptId) {
    }
}
