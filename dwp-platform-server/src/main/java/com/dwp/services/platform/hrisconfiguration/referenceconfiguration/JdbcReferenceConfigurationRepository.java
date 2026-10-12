package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
    public void lockIdempotency(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey) {
        bindTenant(tenantId);
        jdbc.query(
                "SELECT pg_advisory_xact_lock(?)",
                resultSet -> null,
                advisoryKey(tenantId, subjectId, originatingAction, idempotencyKey));
    }

    @Override
    public Optional<StoredReceipt> findReceipt(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT request_digest, receipt_payload
                      FROM hris_reference_configuration_command_receipts
                     WHERE tenant_id = ? AND subject_id = ?
                       AND originating_action = ? AND idempotency_key = ?
                    """, (rs, row) -> storedReceipt(rs),
                    tenantId, subjectId, originatingAction, idempotencyKey));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<StoredReceipt> findReceiptById(long tenantId, UUID receiptId) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT request_digest, receipt_payload
                      FROM hris_reference_configuration_command_receipts
                     WHERE tenant_id = ? AND receipt_id = ?
                    """, (rs, row) -> storedReceipt(rs), tenantId, receiptId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public void saveReceipt(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey,
            String requestDigest,
            CommandReceipt receipt) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_reference_configuration_command_receipts (
                    tenant_id, receipt_id, subject_id, originating_action,
                    idempotency_key, request_digest, command_type,
                    configuration_id, configuration_version, receipt_payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid), ?, CAST(? AS jsonb), ?)
                """,
                tenantId,
                receipt.receiptId(),
                subjectId,
                originatingAction,
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
            PopulationScope scope,
            UUID configurationId,
            long actorId,
            CreateVersionCommand command,
            OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_reference_configurations (
                    tenant_id, configuration_id, configuration_key, domain,
                    scope_type, scope_id, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, configuration_id) DO NOTHING
                """,
                tenantId,
                configurationId,
                command.schemaKey(),
                command.domain().name(),
                scope.type().name(),
                scope.id(),
                actorId,
                now,
                now);
        var owner = jdbc.queryForMap("""
                SELECT domain, configuration_key, scope_type, scope_id
                  FROM hris_reference_configurations
                 WHERE tenant_id = ? AND configuration_id = ?
                   AND scope_type = ? AND scope_id = ?
                 FOR UPDATE
                """, tenantId, configurationId, scope.type().name(), scope.id());
        if (!command.domain().name().equals(owner.get("domain"))
                || !scope.type().name().equals(owner.get("scope_type"))
                || !scope.id().equals(owner.get("scope_id"))) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_OWNER_CONFLICT");
        }
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = ? AND configuration_id = ?
                   AND scope_type = ? AND scope_id = ?
                """, Long.class, tenantId, configurationId, scope.type().name(), scope.id());
        long version = next == null ? 1 : next;
        jdbc.update("""
                INSERT INTO hris_reference_configuration_versions (
                    tenant_id, configuration_id, version, domain, scope_type, scope_id,
                    lifecycle_state, effective_from, effective_to, schema_key, schema_version,
                    payload_reference, payload_digest, aggregate_version,
                    authored_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """,
                tenantId,
                configurationId,
                version,
                command.domain().name(),
                scope.type().name(),
                scope.id(),
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
                   AND scope_type = ? AND scope_id = ?
                """, now, tenantId, configurationId, scope.type().name(), scope.id());
        return version;
    }

    @Override
    public Optional<VersionDetail> findVersion(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long version) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT *
                      FROM hris_reference_configuration_versions
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                    """, this::version,
                    tenantId, configurationId, version, scope.type().name(), scope.id()));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public CatalogPage queryCatalog(
            long tenantId,
            PopulationScope scope,
            OffsetDateTime asOf,
            CatalogFilter filter,
            UUID seekAfter,
            int limit) {
        List<WorkbenchRow> candidates = latestRows(tenantId, scope, asOf).stream()
                .filter(row -> matchesCatalog(row, filter))
                .sorted(Comparator.comparing((WorkbenchRow row) -> row.item().key())
                        .thenComparing(row -> row.item().publicId()))
                .toList();
        int start = seekIndex(candidates, seekAfter);
        boolean seekFound = seekAfter == null || start >= 0;
        if (!seekFound) return new CatalogPage(List.of(), false, false);
        int from = seekAfter == null ? 0 : start + 1;
        int to = Math.min(candidates.size(), from + limit);
        List<CatalogItem> items = from >= candidates.size()
                ? List.of()
                : candidates.subList(from, to).stream().map(WorkbenchRow::item).toList();
        return new CatalogPage(items, to < candidates.size(), true);
    }

    @Override
    public WorkbenchPage queryWorkbench(
            long tenantId,
            PopulationScope scope,
            WorkbenchQuery query,
            OffsetDateTime asOf,
            UUID seekAfter,
            int limit) {
        List<WorkbenchRow> candidates = latestRows(tenantId, scope, asOf).stream()
                .filter(row -> matchesWorkbench(row, query.filter()))
                .sorted(workbenchComparator(query.sort()))
                .toList();
        int start = seekIndex(candidates, seekAfter);
        boolean seekFound = seekAfter == null || start >= 0;
        if (!seekFound) return new WorkbenchPage(List.of(), false, false);
        int from = seekAfter == null ? 0 : start + 1;
        int to = Math.min(candidates.size(), from + limit);
        List<WorkbenchRow> rows = from >= candidates.size()
                ? List.of()
                : List.copyOf(candidates.subList(from, to));
        return new WorkbenchPage(rows, to < candidates.size(), true);
    }

    private List<WorkbenchRow> latestRows(
            long tenantId,
            PopulationScope scope,
            OffsetDateTime asOf) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT c.configuration_key, v.*
                  FROM hris_reference_configurations c
                  JOIN LATERAL (
                       SELECT *
                         FROM hris_reference_configuration_versions candidate
                        WHERE candidate.tenant_id = c.tenant_id
                          AND candidate.configuration_id = c.configuration_id
                          AND candidate.scope_type = c.scope_type
                          AND candidate.scope_id = c.scope_id
                          AND candidate.created_at <= ?
                        ORDER BY candidate.version DESC
                        LIMIT 1
                  ) v ON TRUE
                 WHERE c.tenant_id = ? AND c.scope_type = ? AND c.scope_id = ?
                """, (rs, row) -> {
                    VersionDetail detail = version(rs, row);
                    CatalogItem item = new CatalogItem(
                            detail.configurationId(),
                            rs.getString("configuration_key"),
                            detail.domain(),
                            detail.state(),
                            detail.version(),
                            detail.updatedAt());
                    return new WorkbenchRow(item, detail);
                }, asOf, tenantId, scope.type().name(), scope.id());
    }

    @Override
    public boolean transition(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long version,
            long expectedAggregateVersion,
            LifecycleState expectedState,
            LifecycleState targetState,
            TransitionEvidence evidence,
            OffsetDateTime now) {
        bindTenant(tenantId);
        int changed = switch (targetState) {
            case VALIDATED -> jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'VALIDATED', aggregate_version = aggregate_version + 1,
                           validation_receipt_id = ?, validation_evidence_digest = ?,
                           validation_schema_revision = ?, validation_scope_digest = ?,
                           validated_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                       AND lifecycle_state = ? AND aggregate_version = ?
                    """, evidence.validation().receiptId(), evidence.validation().evidenceDigest(),
                    evidence.validation().schemaRevision(), evidence.validation().populationScopeDigest(),
                    evidence.validation().producedAt(), now,
                    tenantId, configurationId, version, scope.type().name(), scope.id(),
                    expectedState.name(), expectedAggregateVersion);
            case SIMULATED -> jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'SIMULATED', aggregate_version = aggregate_version + 1,
                           simulation_receipt_id = ?, simulation_validation_receipt_id = ?,
                           simulation_evidence_digest = ?, simulation_impact_digest = ?,
                           simulated_at = ?, simulation_fresh_until = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                       AND lifecycle_state = ? AND aggregate_version = ?
                       AND validation_receipt_id = ?
                    """, evidence.simulation().receiptId(), evidence.simulation().validationReceiptId(),
                    evidence.simulation().evidenceDigest(), evidence.simulation().impactDigest(),
                    evidence.simulation().producedAt(), evidence.simulation().freshUntil(), now,
                    tenantId, configurationId, version, scope.type().name(), scope.id(),
                    expectedState.name(), expectedAggregateVersion,
                    evidence.simulation().validationReceiptId());
            case PENDING_APPROVAL -> jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'PENDING_APPROVAL', aggregate_version = aggregate_version + 1,
                           submission_receipt_id = ?, submission_validation_receipt_id = ?,
                           submission_simulation_receipt_id = ?, submitted_evidence_digest = ?,
                           approval_workflow_key = ?, submitted_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                       AND lifecycle_state = ? AND aggregate_version = ?
                       AND validation_receipt_id = ? AND simulation_receipt_id = ?
                    """, evidence.submission().receiptId(), evidence.submission().validationReceiptId(),
                    evidence.submission().simulationReceiptId(), evidence.submission().evidenceDigest(),
                    evidence.submission().approvalWorkflowKey(), evidence.submission().producedAt(), now,
                    tenantId, configurationId, version, scope.type().name(), scope.id(),
                    expectedState.name(), expectedAggregateVersion,
                    evidence.submission().validationReceiptId(),
                    evidence.submission().simulationReceiptId());
            case APPROVED -> jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'APPROVED', aggregate_version = aggregate_version + 1,
                           approval_receipt_id = ?, approval_submission_receipt_id = ?,
                           approval_decision_digest = ?, approval_evidence_digest = ?,
                           approved_at = ?, approved_by = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                       AND lifecycle_state = ? AND aggregate_version = ?
                       AND submission_receipt_id = ?
                    """, evidence.approval().receiptId(), evidence.approval().submissionReceiptId(),
                    evidence.approval().decisionDigest(), evidence.approval().evidenceDigest(),
                    evidence.approval().producedAt(), evidence.actorId(), now,
                    tenantId, configurationId, version, scope.type().name(), scope.id(),
                    expectedState.name(), expectedAggregateVersion,
                    evidence.approval().submissionReceiptId());
            case SCHEDULED -> jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'SCHEDULED', aggregate_version = aggregate_version + 1,
                           scheduled_at = ?, effective_from = ?, effective_to = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                       AND lifecycle_state = ? AND aggregate_version = ?
                    """, evidence.scheduledAt(), evidence.effectiveFrom(), evidence.effectiveTo(), now,
                    tenantId, configurationId, version, scope.type().name(), scope.id(),
                    expectedState.name(), expectedAggregateVersion);
            case PUBLISHED -> jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'PUBLISHED', aggregate_version = aggregate_version + 1,
                           effective_from = ?, effective_to = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                       AND lifecycle_state = ? AND aggregate_version = ?
                    """, evidence.effectiveFrom(), evidence.effectiveTo(), now,
                    tenantId, configurationId, version, scope.type().name(), scope.id(),
                    expectedState.name(), expectedAggregateVersion);
            case RETIRED -> jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'RETIRED', aggregate_version = aggregate_version + 1,
                           effective_to = ?, replacement_version = ?, reason_code = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ?
                       AND lifecycle_state = ? AND aggregate_version = ?
                    """, evidence.effectiveTo(), evidence.replacementVersion(), evidence.reasonCode(), now,
                    tenantId, configurationId, version, scope.type().name(), scope.id(),
                    expectedState.name(), expectedAggregateVersion);
            default -> throw new IllegalArgumentException("Unsupported lifecycle transition " + targetState);
        };
        return changed == 1;
    }

    @Override
    public long rollback(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long currentVersion,
            long targetVersion,
            long expectedAggregateVersion,
            long actorId,
            RollbackCommand command,
            String ownerImpactDigest,
            OffsetDateTime now) {
        bindTenant(tenantId);
        VersionDetail source = findVersion(tenantId, scope, configurationId, targetVersion)
                .orElseThrow(() -> new IllegalStateException("ROLLBACK_TARGET_NOT_FOUND"));
        if (source.state() != LifecycleState.PUBLISHED && source.state() != LifecycleState.RETIRED) {
            throw new IllegalStateException("ROLLBACK_TARGET_NOT_PUBLISHED");
        }
        int closed = jdbc.update("""
                UPDATE hris_reference_configuration_versions
                   SET lifecycle_state = 'RETIRED', effective_to = ?, reason_code = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                   AND scope_type = ? AND scope_id = ?
                   AND lifecycle_state = 'PUBLISHED' AND aggregate_version = ?
                """, command.effectiveFrom(), command.reasonCode(), now,
                tenantId, configurationId, currentVersion, scope.type().name(), scope.id(),
                expectedAggregateVersion);
        if (closed != 1) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
        }
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = ? AND configuration_id = ?
                   AND scope_type = ? AND scope_id = ?
                """, Long.class, tenantId, configurationId, scope.type().name(), scope.id());
        long rollbackVersion = next == null ? currentVersion + 1 : next;
        jdbc.update("""
                INSERT INTO hris_reference_configuration_versions (
                    tenant_id, configuration_id, version, domain, scope_type, scope_id,
                    lifecycle_state, effective_from, effective_to, schema_key, schema_version,
                    payload_reference, payload_digest,
                    validation_receipt_id, validation_evidence_digest, validation_schema_revision,
                    validation_scope_digest, validated_at,
                    simulation_receipt_id, simulation_validation_receipt_id,
                    simulation_evidence_digest, simulation_impact_digest,
                    simulated_at, simulation_fresh_until,
                    submission_receipt_id, submission_validation_receipt_id,
                    submission_simulation_receipt_id, submitted_evidence_digest,
                    approval_workflow_key, submitted_at,
                    approval_receipt_id, approval_submission_receipt_id,
                    approval_decision_digest, approval_evidence_digest, approved_at,
                    aggregate_version, authored_by, approved_by, supersedes_version,
                    reason_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, NULL, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                """,
                tenantId, configurationId, rollbackVersion, source.domain().name(),
                scope.type().name(), scope.id(), command.effectiveFrom(), source.schemaKey(),
                source.schemaVersion(), source.payloadReference(), source.payloadDigest(),
                value(source.validation(), ValidationFact::receiptId),
                value(source.validation(), ValidationFact::evidenceDigest),
                value(source.validation(), fact -> fact.schemaRevision()),
                value(source.validation(), ValidationFact::populationScopeDigest),
                value(source.validation(), ValidationFact::producedAt),
                value(source.simulation(), SimulationFact::receiptId),
                value(source.simulation(), SimulationFact::validationReceiptId),
                value(source.simulation(), SimulationFact::evidenceDigest),
                ownerImpactDigest,
                value(source.simulation(), SimulationFact::producedAt),
                value(source.simulation(), SimulationFact::freshUntil),
                value(source.submission(), SubmissionFact::receiptId),
                value(source.submission(), SubmissionFact::validationReceiptId),
                value(source.submission(), SubmissionFact::simulationReceiptId),
                value(source.submission(), SubmissionFact::evidenceDigest),
                value(source.submission(), SubmissionFact::approvalWorkflowKey),
                value(source.submission(), SubmissionFact::producedAt),
                command.approvalReceiptId(),
                value(source.approval(), ApprovalFact::submissionReceiptId),
                value(source.approval(), ApprovalFact::decisionDigest),
                value(source.approval(), ApprovalFact::evidenceDigest),
                value(source.approval(), ApprovalFact::producedAt),
                source.authoredBy(), actorId, currentVersion, command.reasonCode(), now, now);
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
        bindTenant(tenantId);
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
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_reference_configuration_audit (
                    tenant_id, audit_id, configuration_id, configuration_version,
                    action, actor_id, correlation_id, evidence_digest, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS uuid), ?, ?)
                """, tenantId, UUID.randomUUID(), configurationId, version, action,
                actorId, correlationId, evidenceDigest, now);
    }

    private StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        return new StoredReceipt(
                rs.getString("request_digest"),
                read(rs.getString("receipt_payload"), CommandReceipt.class));
    }

    private VersionDetail version(ResultSet rs, int row) throws SQLException {
        UUID validationReceipt = rs.getObject("validation_receipt_id", UUID.class);
        ValidationFact validation = validationReceipt == null ? null : new ValidationFact(
                validationReceipt,
                rs.getString("validation_evidence_digest"),
                rs.getInt("validation_schema_revision"),
                rs.getString("validation_scope_digest"),
                rs.getObject("validated_at", OffsetDateTime.class));
        UUID simulationReceipt = rs.getObject("simulation_receipt_id", UUID.class);
        SimulationFact simulation = simulationReceipt == null ? null : new SimulationFact(
                simulationReceipt,
                rs.getObject("simulation_validation_receipt_id", UUID.class),
                rs.getString("simulation_evidence_digest"),
                rs.getString("simulation_impact_digest"),
                rs.getObject("simulated_at", OffsetDateTime.class),
                rs.getObject("simulation_fresh_until", OffsetDateTime.class));
        UUID submissionReceipt = rs.getObject("submission_receipt_id", UUID.class);
        SubmissionFact submission = submissionReceipt == null ? null : new SubmissionFact(
                submissionReceipt,
                rs.getObject("submission_validation_receipt_id", UUID.class),
                rs.getObject("submission_simulation_receipt_id", UUID.class),
                rs.getString("submitted_evidence_digest"),
                rs.getString("approval_workflow_key"),
                rs.getObject("submitted_at", OffsetDateTime.class));
        UUID approvalReceipt = rs.getObject("approval_receipt_id", UUID.class);
        ApprovalFact approval = approvalReceipt == null ? null : new ApprovalFact(
                approvalReceipt,
                rs.getObject("approval_submission_receipt_id", UUID.class),
                rs.getString("approval_decision_digest"),
                rs.getString("approval_evidence_digest"),
                rs.getObject("approved_at", OffsetDateTime.class));
        return new VersionDetail(
                rs.getObject("configuration_id", UUID.class),
                rs.getLong("version"),
                Domain.valueOf(rs.getString("domain")),
                ScopeType.valueOf(rs.getString("scope_type")),
                rs.getObject("scope_id", UUID.class),
                LifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                rs.getString("schema_key"),
                rs.getInt("schema_version"),
                rs.getString("payload_reference"),
                rs.getString("payload_digest"),
                validation,
                simulation,
                submission,
                approval,
                rs.getLong("aggregate_version"),
                nullableLong(rs, "authored_by"),
                nullableLong(rs, "approved_by"),
                rs.getObject("scheduled_at", OffsetDateTime.class),
                nullableLong(rs, "supersedes_version"),
                nullableLong(rs, "replacement_version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private boolean matchesCatalog(WorkbenchRow row, CatalogFilter filter) {
        if (filter == null) return true;
        if (filter.key() != null && !row.item().key().equals(filter.key())) return false;
        if (filter.domain() != null && row.item().domain() != filter.domain()) return false;
        if (filter.state() != null && row.item().state() != filter.state()) return false;
        return filter.minimumVersion() == null || row.item().version() >= filter.minimumVersion();
    }

    private boolean matchesWorkbench(WorkbenchRow row, WorkbenchFilter filter) {
        if (filter == null) return true;
        CatalogItem item = row.item();
        VersionDetail detail = row.detail();
        if (notEmpty(filter.policyKeys()) && !filter.policyKeys().contains(detail.schemaKey())) return false;
        if (filter.minimumPolicyRevision() != null
                && detail.version() < filter.minimumPolicyRevision()) return false;
        if (notEmpty(filter.referencePublicIds())
                && !filter.referencePublicIds().contains(item.publicId().toString())) return false;
        if (notEmpty(filter.referenceCodes())
                && !filter.referenceCodes().contains(item.domain().name())) return false;
        if (notEmpty(filter.effectiveStatuses())
                && !filter.effectiveStatuses().contains(item.state().name())) return false;
        if (filter.minimumStatusRevision() != null
                && detail.version() < filter.minimumStatusRevision()) return false;
        if (filter.effectiveOn() != null) {
            if (filter.effectiveOn().isBefore(detail.effectiveFrom().toLocalDate())) return false;
            if (detail.effectiveTo() != null
                    && !filter.effectiveOn().isBefore(detail.effectiveTo().toLocalDate())) return false;
        }
        return true;
    }

    private Comparator<WorkbenchRow> workbenchComparator(List<String> sorts) {
        Comparator<WorkbenchRow> comparator = null;
        for (String sort : sorts) {
            Comparator<WorkbenchRow> next = switch (sort) {
                case "policyVersion:ASC" -> Comparator.comparingLong(row -> row.detail().version());
                case "policyVersion:DESC" -> Comparator.comparingLong(
                        (WorkbenchRow row) -> row.detail().version()).reversed();
                case "referenceData:ASC" -> Comparator.comparing(row -> row.item().key());
                case "referenceData:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().key()).reversed();
                case "effectiveStatus:ASC" -> Comparator.comparing(
                        row -> row.item().state().name());
                case "effectiveStatus:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().state().name()).reversed();
                case "itemPublicId:ASC" -> Comparator.comparing(row -> row.item().publicId());
                case "itemPublicId:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().publicId()).reversed();
                default -> throw new IllegalArgumentException("REFERENCE_CONFIGURATION_SORT_INVALID");
            };
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        Comparator<WorkbenchRow> stable = Comparator.comparing(row -> row.item().publicId());
        return comparator == null ? stable : comparator.thenComparing(stable);
    }

    private int seekIndex(List<WorkbenchRow> candidates, UUID seekAfter) {
        if (seekAfter == null) return -1;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).item().publicId().equals(seekAfter)) return index;
        }
        return -1;
    }

    private boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private void bindTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', ?, true)",
                String.class,
                Long.toString(tenantId));
    }

    private long advisoryKey(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey) {
        String material = tenantId + "|" + subjectId + "|" + originatingAction + "|" + idempotencyKey;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String normalizeDigest(String digest) {
        return digest.startsWith("sha256:")
                ? digest.substring(7).toLowerCase(Locale.ROOT)
                : digest.toLowerCase(Locale.ROOT);
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

    private <T, R> R value(T source, java.util.function.Function<T, R> extractor) {
        return source == null ? null : extractor.apply(source);
    }
}
