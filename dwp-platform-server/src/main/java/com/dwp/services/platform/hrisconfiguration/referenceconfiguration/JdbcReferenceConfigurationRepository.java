package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

@Repository
class JdbcReferenceConfigurationRepository implements ReferenceConfigurationRepository {
    final JdbcReferenceConfigurationMapping mapping;



    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcReferenceConfigurationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapping = new JdbcReferenceConfigurationMapping(objectMapper);
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
                mapping.advisoryKey(tenantId, subjectId, originatingAction, idempotencyKey));
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
                    """, (rs, row) -> mapping.storedReceipt(rs),
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
                    """, (rs, row) -> mapping.storedReceipt(rs), tenantId, receiptId));
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
                mapping.resultConfigurationId(receipt.resultRef()),
                mapping.resultVersion(receipt.resultRef()),
                mapping.write(receipt),
                receipt.createdAt());
    }

    @Override
    public void saveCanonicalReceipt(long tenantId, CanonicalReceipt receipt) {
        bindPrincipal(tenantId, receipt.subjectPrincipalPublicId());
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    command_receipt_id, public_id, tenant_id, command_type,
                    originating_action, subject_principal_public_id,
                    population_scope_digest, authority_population_scope_digest,
                    field_policy_revision, authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key,
                    origin_context_key, origin_context_scope_key,
                    origin_decision_revision, idempotency_key, request_digest,
                    aggregate_public_id, aggregate_revision, status, result_ref,
                    correlation_id, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.receiptId(), receipt.receiptId(), tenantId,
                receipt.commandType(), receipt.originatingAction(),
                receipt.subjectPrincipalPublicId(), receipt.populationScopeDigest(),
                receipt.authorityPopulationScopeDigest(),
                receipt.fieldPolicyRevision(), receipt.authorityFieldPolicyRevision(),
                receipt.purposeCode(), receipt.authorizationRevision(),
                receipt.authorityAuthorizationRevision(),
                receipt.originRouteContractKey(),
                receipt.originContextKey(), receipt.originContextScopeKey(),
                receipt.originDecisionRevision(), receipt.idempotencyKey(),
                receipt.requestDigest(), receipt.aggregatePublicId(),
                receipt.aggregateRevision(), receipt.status(), receipt.resultRef(),
                receipt.correlationId(), receipt.createdAt(), receipt.createdAt());
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
                || !command.schemaKey().equals(owner.get("configuration_key"))
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
                mapping.normalizeDigest(command.payload().digest()),
                actorId,
                now,
                now);
        jdbc.update("""
                UPDATE hris_reference_configurations
                   SET updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ?
                   AND scope_type = ? AND scope_id = ?
                """, now, tenantId, configurationId, scope.type().name(), scope.id());
        captureSnapshot(tenantId, scope, configurationId, version, now);
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
                    """, mapping::version,
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
                .filter(row -> mapping.matchesCatalog(row, filter))
                .sorted(Comparator.comparing(row -> row.item().publicId()))
                .toList();
        int start = mapping.seekIndex(candidates, seekAfter);
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
                .filter(row -> mapping.matchesWorkbench(row, query.filter()))
                .sorted(mapping.workbenchComparator(query.sort()))
                .toList();
        int start = mapping.seekIndex(candidates, seekAfter);
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
                       SELECT history.snapshot
                         FROM hris_reference_configuration_version_history history
                        WHERE history.tenant_id = c.tenant_id
                          AND history.configuration_id = c.configuration_id
                          AND history.scope_type = c.scope_type
                          AND history.scope_id = c.scope_id
                          AND history.captured_at <= ?
                        ORDER BY history.version DESC, history.aggregate_version DESC
                        LIMIT 1
                  ) history ON TRUE
                  CROSS JOIN LATERAL jsonb_populate_record(
                      NULL::hris_reference_configuration_versions,
                      history.snapshot) v
                 WHERE c.tenant_id = ? AND c.scope_type = ? AND c.scope_id = ?
                """, (rs, row) -> {
                    VersionDetail detail = mapping.version(rs, row);
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
        if (changed == 1) {
            captureSnapshot(tenantId, scope, configurationId, version, now);
        }
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
                   AND effective_from < ? AND (effective_to IS NULL OR effective_to > ?)
                """, now, command.reasonCode(), now,
                tenantId, configurationId, currentVersion, scope.type().name(), scope.id(),
                expectedAggregateVersion, now, now);
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
                scope.type().name(), scope.id(), now, source.schemaKey(),
                source.schemaVersion(), source.payloadReference(), source.payloadDigest(),
                mapping.value(source.validation(), ValidationFact::receiptId),
                mapping.value(source.validation(), ValidationFact::evidenceDigest),
                mapping.value(source.validation(), fact -> fact.schemaRevision()),
                mapping.value(source.validation(), ValidationFact::populationScopeDigest),
                mapping.value(source.validation(), ValidationFact::producedAt),
                mapping.value(source.simulation(), SimulationFact::receiptId),
                mapping.value(source.simulation(), SimulationFact::validationReceiptId),
                mapping.value(source.simulation(), SimulationFact::evidenceDigest),
                ownerImpactDigest,
                mapping.value(source.simulation(), SimulationFact::producedAt),
                mapping.value(source.simulation(), SimulationFact::freshUntil),
                mapping.value(source.submission(), SubmissionFact::receiptId),
                mapping.value(source.submission(), SubmissionFact::validationReceiptId),
                mapping.value(source.submission(), SubmissionFact::simulationReceiptId),
                mapping.value(source.submission(), SubmissionFact::evidenceDigest),
                mapping.value(source.submission(), SubmissionFact::approvalWorkflowKey),
                mapping.value(source.submission(), SubmissionFact::producedAt),
                command.approvalReceiptId(),
                mapping.value(source.approval(), ApprovalFact::submissionReceiptId),
                mapping.value(source.approval(), ApprovalFact::decisionDigest),
                mapping.value(source.approval(), ApprovalFact::evidenceDigest),
                mapping.value(source.approval(), ApprovalFact::producedAt),
                source.authoredBy(), actorId, currentVersion, command.reasonCode(), now, now);
        captureSnapshot(tenantId, scope, configurationId, currentVersion, now);
        captureSnapshot(tenantId, scope, configurationId, rollbackVersion, now);
        return rollbackVersion;
    }

    @Override
    public boolean retireAndActivateSuccessor(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long currentVersion,
            long currentAggregateVersion,
            long successorVersion,
            long successorAggregateVersion,
            OffsetDateTime boundary,
            String reasonCode,
            OffsetDateTime now) {
        bindTenant(tenantId);
        int retired = jdbc.update("""
                UPDATE hris_reference_configuration_versions
                   SET lifecycle_state = 'RETIRED', effective_to = ?, replacement_version = ?,
                       reason_code = ?, aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                   AND scope_type = ? AND scope_id = ?
                   AND lifecycle_state = 'PUBLISHED' AND aggregate_version = ?
                   AND effective_from < ?
                   AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                """, boundary, successorVersion, reasonCode, now,
                tenantId, configurationId, currentVersion, scope.type().name(), scope.id(),
                currentAggregateVersion, boundary, now, now);
        if (retired != 1) return false;
        int activated = jdbc.update("""
                UPDATE hris_reference_configuration_versions
                   SET lifecycle_state = 'PUBLISHED', aggregate_version = aggregate_version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                   AND scope_type = ? AND scope_id = ?
                   AND lifecycle_state = 'SCHEDULED' AND aggregate_version = ?
                   AND effective_from = ? AND scheduled_at <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                   AND approval_receipt_id IS NOT NULL
                """, now, tenantId, configurationId, successorVersion,
                scope.type().name(), scope.id(), successorAggregateVersion, boundary, now, now);
        if (activated != 1) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_SUCCESSOR_ACTIVATION_CONFLICT");
        }
        captureSnapshot(tenantId, scope, configurationId, currentVersion, now);
        captureSnapshot(tenantId, scope, configurationId, successorVersion, now);
        return true;
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

    private void captureSnapshot(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long version,
            OffsetDateTime capturedAt) {
        int inserted = jdbc.update("""
                INSERT INTO hris_reference_configuration_version_history (
                    tenant_id, configuration_id, version, aggregate_version,
                    scope_type, scope_id, captured_at, snapshot)
                SELECT v.tenant_id, v.configuration_id, v.version, v.aggregate_version,
                       v.scope_type, v.scope_id, ?, to_jsonb(v)
                  FROM hris_reference_configuration_versions v
                 WHERE v.tenant_id = ? AND v.configuration_id = ? AND v.version = ?
                   AND v.scope_type = ? AND v.scope_id = ?
                """, capturedAt, tenantId, configurationId, version,
                scope.type().name(), scope.id());
        if (inserted != 1) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_SNAPSHOT_CAPTURE_FAILED");
        }
    }

    private void bindTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', ?, true)",
                String.class,
                Long.toString(tenantId));
    }

    private void bindPrincipal(long tenantId, UUID subjectPrincipalPublicId) {
        bindTenant(tenantId);
        if (subjectPrincipalPublicId == null) {
            throw new IllegalArgumentException("subjectPrincipalPublicId is required");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.principal_public_id', ?, true)",
                String.class, subjectPrincipalPublicId.toString());
    }
}
