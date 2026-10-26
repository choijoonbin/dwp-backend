package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** PostgreSQL adapter for the exact six-table V334 ownership model. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcIntegrationRepository implements IntegrationRepository,
        IntegrationExecutionRepository {

    private static final UUID COMPENSATION_CLAIM_PRINCIPAL = UUID.fromString(
            "00000000-0000-4000-8000-000000000009");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IntegrationCanonicalJson canonical;

    JdbcIntegrationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.canonical = new IntegrationCanonicalJson(json);
    }

    @Override
    public OffsetDateTime databaseNow() {
        return jdbc.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class);
    }

    @Override
    public void bindTenantPrincipal(
            long tenantId, UUID subjectPrincipalPublicId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(tenantId));
        jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                String.class, subjectPrincipalPublicId.toString());
    }

    @Override
    public Optional<CommandReceipt> findReplay(CommandSeal command) {
        List<StoredReceipt> rows = jdbc.query("""
                SELECT request_digest, public_id, command_type, originating_action,
                       subject_principal_public_id, population_scope_digest,
                       field_policy_revision, purpose_code, authorization_revision,
                       authority_population_scope_digest,
                       authority_field_policy_revision,
                       authority_authorization_revision,
                       origin_route_contract_key, origin_context_key,
                       origin_context_scope_key, origin_decision_revision,
                       status, correlation_id, created_at, aggregate_revision,
                       result_ref
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND originating_action = ? AND idempotency_key = ?
                """, (rs, row) -> storedReceipt(rs), command.tenantId(),
                command.subjectPrincipalPublicId(), command.originatingAction(),
                command.idempotencyKey());
        if (rows.isEmpty()) return Optional.empty();
        StoredReceipt stored = rows.getFirst();
        if (!stored.matchesCurrentAuthority(command)) {
            throw notFound("SYS008_COMMAND_RECEIPT_NOT_AVAILABLE");
        }
        if (!stored.requestDigest().equals(command.requestDigest())) {
            throw conflict("SYS008_IDEMPOTENCY_DIGEST_CONFLICT");
        }
        return Optional.of(stored.receipt());
    }

    @Override
    public CommandReservation beginCommand(
            CommandSeal command, OffsetDateTime now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, command.tenantId() + "\u001f"
                        + command.subjectPrincipalPublicId() + "\u001f"
                        + command.originatingAction() + "\u001f"
                        + command.idempotencyKey());
        Optional<CommandReceipt> replay = findReplay(command);
        if (replay.isPresent()) {
            return new CommandReservation(replay.get(), false);
        }
        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest, field_policy_revision,
                    authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, status, correlation_id,
                    created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'ACCEPTED', ?, ?)
                """, receiptId, command.tenantId(), command.commandType(),
                command.originatingAction(), command.subjectPrincipalPublicId(),
                command.populationScopeDigest(), command.populationScopeDigest(),
                command.fieldPolicyRevision(), command.fieldPolicyRevision(),
                command.purposeCode(), command.authorizationRevision(),
                command.authorizationRevision(), command.routeContractKey(),
                command.contextKey(), command.contextScopeKey(),
                command.decisionRevision(), command.idempotencyKey(),
                command.requestDigest(), command.correlationId(), now);
        return new CommandReservation(receipt(
                command, receiptId, ReceiptStatus.ACCEPTED, now,
                null, null), true);
    }

    @Override
    public CommandReceipt progressCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RUNNING', aggregate_public_id = ?,
                       aggregate_revision = ?, result_ref = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'ACCEPTED'
                """, aggregatePublicId, aggregateRevision, resultRef,
                command.tenantId(), receiptId);
        if (changed != 1) {
            throw conflict("SYS008_RECEIPT_RUNNING_CAS_CONFLICT");
        }
        return receipt(command, receiptId, ReceiptStatus.RUNNING, now,
                aggregateRevision, resultRef);
    }

    @Override
    public CommandReceipt finishCommand(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        if (!Set.of(ReceiptStatus.SUCCEEDED, ReceiptStatus.REJECTED,
                ReceiptStatus.FAILED, ReceiptStatus.RESULT_UNKNOWN)
                .contains(status)) {
            throw new IllegalArgumentException("receipt status is not terminal");
        }
        boolean rejected = status == ReceiptStatus.REJECTED;
        int progressed;
        if (rejected) {
            progressed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'RUNNING', aggregate_public_id = NULL,
                           aggregate_revision = NULL, result_ref = NULL
                     WHERE tenant_id = ? AND public_id = ?
                       AND status = 'ACCEPTED'
                    """, command.tenantId(), receiptId);
        } else {
            if (aggregatePublicId == null || aggregateRevision < 1
                    || resultRef == null || resultRef.isBlank()) {
                throw new IllegalArgumentException(
                        "terminal receipt aggregate binding is required");
            }
            progressed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'RUNNING', aggregate_public_id = ?,
                           aggregate_revision = ?, result_ref = ?
                     WHERE tenant_id = ? AND public_id = ?
                       AND status = 'ACCEPTED'
                    """, aggregatePublicId, aggregateRevision, resultRef,
                    command.tenantId(), receiptId);
        }
        int changed;
        if (rejected) {
            changed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'REJECTED', error_code = ?, completed_at = ?,
                           aggregate_public_id = NULL, aggregate_revision = NULL,
                           result_ref = NULL
                     WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                    """, receiptError(status), now,
                    command.tenantId(), receiptId);
        } else {
            changed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = ?, error_code = ?, completed_at = ?,
                           aggregate_revision = ?, result_ref = ?
                     WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                       AND aggregate_public_id = ? AND result_ref = ?
                       AND aggregate_revision <= ?
                    """, status.name(), receiptError(status), now,
                    aggregateRevision, resultRef, command.tenantId(), receiptId,
                    aggregatePublicId, resultRef, aggregateRevision);
        }
        if (progressed == 0 && changed == 0) {
            throw conflict("SYS008_RECEIPT_RUNNING_CAS_CONFLICT");
        }
        if (changed != 1) {
            throw conflict("SYS008_RECEIPT_TERMINAL_CAS_CONFLICT");
        }
        return receipt(command, receiptId, status, now,
                rejected ? null : aggregateRevision,
                rejected ? null : resultRef);
    }

    @Override
    public void markOriginalReceiptOutcome(
            ExecutionRecord execution,
            ReceiptStatus status,
            OffsetDateTime now) {
        if (!Set.of(ReceiptStatus.SUCCEEDED, ReceiptStatus.FAILED,
                ReceiptStatus.RESULT_UNKNOWN).contains(status)) {
            throw new IllegalArgumentException("provider receipt outcome is invalid");
        }
        int changed = jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = ?, error_code = ?, aggregate_revision = ?,
                       completed_at = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                """, status.name(), receiptError(status),
                execution.aggregateRevision(), now,
                execution.tenantId(), execution.originalCommandReceiptId());
        if (changed == 1) return;
        List<String> existing = jdbc.query("""
                SELECT status
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, (rs, row) -> rs.getString(1), execution.tenantId(),
                execution.originalCommandReceiptId());
        if (existing.size() == 1 && Set.of(
                ReceiptStatus.SUCCEEDED.name(), ReceiptStatus.FAILED.name(),
                ReceiptStatus.RESULT_UNKNOWN.name())
                .contains(existing.getFirst())) {
            return;
        }
        throw conflict("SYS008_ORIGINAL_RECEIPT_TERMINAL_CAS_CONFLICT");
    }

    @Override
    public CatalogPage catalog(
            long tenantId,
            OffsetDateTime asOf,
            int limit,
            String afterItemPublicId,
            String filter) {
        StringBuilder sql = new StringBuilder("""
                WITH catalog_rows AS (
                    SELECT d.public_id, d.connector_key, v.connector_type,
                           CASE
                             WHEN v.activated_at <= ? THEN 'ACTIVE'
                             WHEN v.approved_at <= ? THEN 'APPROVED'
                             WHEN v.submitted_at <= ? THEN 'PENDING_APPROVAL'
                             WHEN v.tested_at <= ? THEN 'TESTED'
                             WHEN v.validated_at <= ? THEN 'VALIDATED'
                             ELSE 'DRAFT'
                           END AS lifecycle_state,
                           v.version_number,
                           GREATEST(v.created_at,
                             CASE WHEN v.validated_at <= ? THEN v.validated_at END,
                             CASE WHEN v.tested_at <= ? THEN v.tested_at END,
                             CASE WHEN v.submitted_at <= ? THEN v.submitted_at END,
                             CASE WHEN v.approved_at <= ? THEN v.approved_at END,
                             CASE WHEN v.activated_at <= ? THEN v.activated_at END)
                               AS state_updated_at
                      FROM sys_connector_definitions d
                      JOIN LATERAL (
                           SELECT version.*
                             FROM sys_connector_definition_versions version
                            WHERE version.tenant_id = d.tenant_id
                              AND version.definition_id = d.definition_id
                              AND version.created_at <= ?
                            ORDER BY version.version_number DESC LIMIT 1
                      ) v ON TRUE
                     WHERE d.tenant_id = ? AND d.created_at <= ?
                )
                SELECT * FROM catalog_rows
                 WHERE public_id::text > COALESCE(?, '')
                """);
        List<Object> arguments = new ArrayList<>();
        for (int index = 0; index < 11; index++) arguments.add(asOf);
        arguments.add(tenantId);
        arguments.add(asOf);
        arguments.add(afterItemPublicId);
        if (filter != null) {
            sql.append(" AND (connector_key || ' ' || connector_type || ' ' || "
                    + "lifecycle_state) ILIKE ?");
            arguments.add("%" + filter + "%");
        }
        sql.append(" ORDER BY public_id::text LIMIT ?");
        arguments.add(limit + 1);
        List<ConnectorSummary> rows = jdbc.query(sql.toString(), (rs, row) -> new ConnectorSummary(
                uuid(rs, "public_id"), rs.getString("connector_key"),
                rs.getString("connector_type"), rs.getString("lifecycle_state"),
                rs.getLong("version_number"),
                rs.getObject("state_updated_at", OffsetDateTime.class)),
                arguments.toArray());
        String next = rows.size() > limit
                ? rows.get(limit - 1).publicId().toString() : null;
        return new CatalogPage(rows.stream().limit(limit).toList(), next);
    }

    @Override
    public DefinitionRecord insertDefinition(
            long tenantId,
            UUID connectorId,
            long actorId,
            String actorScopeDigest,
            ConnectorCommand command,
            String definitionDigest,
            long endpointPolicyRevision,
            long secretRevision,
            OffsetDateTime now) {
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_connector_definitions (
                    tenant_id, definition_id, public_id, connector_key,
                    lifecycle_state, aggregate_revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'INACTIVE', 1, ?, ?)
                """, tenantId, definitionId, connectorId,
                command.connectorKey(), now, now);
        jdbc.update("""
                INSERT INTO sys_connector_definition_versions (
                    tenant_id, definition_version_id, definition_id,
                    version_number, lifecycle_state, connector_type,
                    endpoint_uri, endpoint_policy_key, endpoint_policy_revision,
                    secret_ref, secret_revision, definition_digest,
                    subject_revision, object_revision, author_actor_id,
                    author_scope_digest, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'DRAFT', ?, ?, ?, ?, ?, ?, ?,
                        1, 1, ?, ?, ?, ?)
                """, tenantId, versionId, definitionId,
                command.type().name(), command.endpoint().toString(),
                command.egressPolicyKey(), endpointPolicyRevision,
                command.secretRef(), secretRevision, definitionDigest,
                actorId, actorScopeDigest, now, now);
        return lockDefinition(tenantId, connectorId);
    }

    @Override
    public DefinitionRecord lockDefinition(
            long tenantId, UUID connectorId) {
        try {
            return jdbc.queryForObject("""
                    SELECT d.definition_id, d.public_id AS connector_public_id,
                           d.tenant_id, d.connector_key, d.aggregate_revision,
                           v.definition_version_id, v.version_number,
                           v.lifecycle_state, v.connector_type, v.endpoint_uri,
                           v.endpoint_policy_key, v.endpoint_policy_revision,
                           v.secret_ref, v.secret_revision, v.definition_digest,
                           v.subject_revision, v.object_revision,
                           v.author_actor_id, v.author_scope_digest,
                           v.approved_by_actor_id, v.approval_receipt_id,
                           v.connection_test_result_ref,
                           v.connection_test_result_digest,
                           COALESCE((SELECT MAX(m.version_number)
                               FROM sys_connector_mapping_versions m
                              WHERE m.tenant_id = d.tenant_id
                                AND m.definition_id = d.definition_id
                                AND m.lifecycle_state = 'ACTIVE'), 0)
                               AS active_mapping_version
                      FROM sys_connector_definitions d
                      JOIN LATERAL (
                           SELECT version.*
                             FROM sys_connector_definition_versions version
                            WHERE version.tenant_id = d.tenant_id
                              AND version.definition_id = d.definition_id
                            ORDER BY version.version_number DESC LIMIT 1
                      ) v ON TRUE
                     WHERE d.tenant_id = ? AND d.public_id = ?
                     FOR UPDATE OF d, v
                    """, (rs, row) -> definition(rs), tenantId, connectorId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_CONNECTOR_NOT_FOUND");
        }
    }

    @Override
    public DefinitionRecord transitionDefinition(
            DefinitionRecord current,
            DefinitionState target,
            long actorId,
            UUID evidenceReceiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now) {
        int changed = switch (target) {
            case VALIDATED -> jdbc.update("""
                    UPDATE sys_connector_definition_versions
                       SET lifecycle_state = 'VALIDATED',
                           validation_receipt_id = ?,
                           validation_evidence_digest = ?,
                           validated_by_actor_id = ?, validated_at = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND definition_version_id = ?
                       AND lifecycle_state = 'DRAFT' AND object_revision = ?
                    """, evidenceReceiptId, evidenceDigest, actorId, now, now,
                    current.tenantId(), current.definitionVersionId(),
                    current.objectRevision());
            case PENDING_APPROVAL -> jdbc.update("""
                    UPDATE sys_connector_definition_versions
                       SET lifecycle_state = 'PENDING_APPROVAL',
                           submission_receipt_id = ?,
                           submission_evidence_digest = ?,
                           submitted_by_actor_id = ?, submitted_at = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND definition_version_id = ?
                       AND lifecycle_state = 'TESTED' AND object_revision = ?
                    """, evidenceReceiptId, evidenceDigest, actorId, now, now,
                    current.tenantId(), current.definitionVersionId(),
                    current.objectRevision());
            case APPROVED -> jdbc.update("""
                    UPDATE sys_connector_definition_versions
                       SET lifecycle_state = 'APPROVED', approval_receipt_id = ?,
                           approval_evidence_digest = ?,
                           approval_step_up_evidence_digest = ?,
                           approved_by_actor_id = ?, approved_at = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND definition_version_id = ?
                       AND lifecycle_state = 'PENDING_APPROVAL'
                       AND object_revision = ?
                    """, evidenceReceiptId, evidenceDigest, stepUpDigest,
                    actorId, now, now, current.tenantId(),
                    current.definitionVersionId(), current.objectRevision());
            case ACTIVE -> jdbc.update("""
                    UPDATE sys_connector_definition_versions
                       SET lifecycle_state = 'ACTIVE', activation_receipt_id = ?,
                           activation_evidence_digest = ?,
                           activation_step_up_evidence_digest = ?,
                           activated_by_actor_id = ?, activated_at = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND definition_version_id = ?
                       AND lifecycle_state = 'APPROVED' AND object_revision = ?
                    """, evidenceReceiptId, evidenceDigest, stepUpDigest,
                    actorId, now, now, current.tenantId(),
                    current.definitionVersionId(), current.objectRevision());
            default -> throw new IllegalArgumentException(
                    "unsupported definition transition target: " + target);
        };
        if (changed != 1) throw conflict("SYS008_DEFINITION_VERSION_CAS_CONFLICT");
        int aggregate = target == DefinitionState.ACTIVE
                ? jdbc.update("""
                    UPDATE sys_connector_definitions
                       SET lifecycle_state = 'ACTIVE', active_version_number = ?,
                           aggregate_revision = aggregate_revision + 1,
                           updated_at = ?
                     WHERE tenant_id = ? AND definition_id = ?
                       AND aggregate_revision = ?
                    """, current.definitionVersion(), now, current.tenantId(),
                    current.definitionId(), current.aggregateRevision())
                : incrementDefinitionAggregate(current, now);
        if (aggregate != 1) throw conflict("SYS008_DEFINITION_AGGREGATE_CAS_CONFLICT");
        return lockDefinition(current.tenantId(), current.connectorId());
    }

    @Override
    public DefinitionRecord recordConnectionTest(
            DefinitionRecord current,
            long actorId,
            UUID commandReceiptId,
            String receiptRef,
            String receiptDigest,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'TESTED',
                       connection_test_receipt_id = ?,
                       connection_test_result_ref = ?,
                       connection_test_result_digest = ?,
                       connection_test_evidence_digest = ?,
                       tested_by_actor_id = ?, tested_at = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                   AND lifecycle_state = 'VALIDATED' AND object_revision = ?
                """, commandReceiptId, receiptRef, receiptDigest, receiptDigest,
                actorId, now, now, current.tenantId(),
                current.definitionVersionId(), current.objectRevision());
        if (changed != 1) throw conflict("SYS008_CONNECTION_TEST_CAS_CONFLICT");
        if (incrementDefinitionAggregate(current, now) != 1) {
            throw conflict("SYS008_DEFINITION_AGGREGATE_CAS_CONFLICT");
        }
        return lockDefinition(current.tenantId(), current.connectorId());
    }

    @Override
    public MappingRecord insertMapping(
            DefinitionRecord definition,
            long actorId,
            String actorScopeDigest,
            MappingCommand command,
            String direction,
            String sourceSchemaDigest,
            String targetSchemaDigest,
            OffsetDateTime now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, definition.tenantId() + "\u001f"
                        + definition.definitionId() + "\u001fdefault");
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                  FROM sys_connector_mapping_versions
                 WHERE tenant_id = ? AND definition_id = ?
                   AND mapping_key = 'default'
                """, Long.class, definition.tenantId(), definition.definitionId());
        UUID mappingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_connector_mapping_versions (
                    tenant_id, mapping_version_id, definition_id, mapping_key,
                    version_number, lifecycle_state, direction,
                    source_schema_version, source_schema_digest,
                    target_schema_version, target_schema_digest,
                    mapping_ref, mapping_digest, compatibility_mode,
                    effective_from, effective_to, subject_revision,
                    object_revision, author_actor_id, author_scope_digest,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'default', ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, 1, 1, ?, ?, ?, ?)
                """, definition.tenantId(), mappingId, definition.definitionId(),
                next, direction, command.sourceSchemaVersion(), sourceSchemaDigest,
                command.targetSchemaVersion(), targetSchemaDigest,
                command.mappingRef(), command.mappingDigest(),
                command.compatibilityMode().name(), command.effectiveFrom(),
                command.effectiveTo(), actorId, actorScopeDigest, now, now);
        if (incrementDefinitionAggregate(
                definition.tenantId(), definition.definitionId(), now) != 1) {
            throw conflict("SYS008_DEFINITION_AGGREGATE_CAS_CONFLICT");
        }
        return lockMapping(definition.tenantId(), definition.connectorId(), next);
    }

    @Override
    public MappingRecord lockMapping(
            long tenantId, UUID connectorId, long version) {
        try {
            return jdbc.queryForObject("""
                    SELECT m.*, d.public_id AS connector_public_id
                      FROM sys_connector_mapping_versions m
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = m.tenant_id
                       AND d.definition_id = m.definition_id
                     WHERE m.tenant_id = ? AND d.public_id = ?
                       AND m.version_number = ? AND m.mapping_key = 'default'
                     FOR UPDATE OF m
                    """, (rs, row) -> mapping(rs), tenantId, connectorId, version);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_MAPPING_NOT_FOUND");
        }
    }

    @Override
    public MappingRecord transitionMapping(
            MappingRecord current,
            MappingState target,
            long actorId,
            UUID evidenceReceiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now) {
        int changed = switch (target) {
            case VALIDATED -> jdbc.update("""
                    UPDATE sys_connector_mapping_versions
                       SET lifecycle_state = 'VALIDATED',
                           validation_receipt_id = ?,
                           validation_evidence_digest = ?,
                           validated_by_actor_id = ?, validated_at = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND mapping_version_id = ?
                       AND lifecycle_state = 'DRAFT' AND object_revision = ?
                    """, evidenceReceiptId, evidenceDigest, actorId, now, now,
                    current.tenantId(), current.mappingId(),
                    current.objectRevision());
            case PENDING_APPROVAL -> jdbc.update("""
                    UPDATE sys_connector_mapping_versions
                       SET lifecycle_state = 'PENDING_APPROVAL',
                           submission_receipt_id = ?,
                           submission_evidence_digest = ?,
                           submitted_by_actor_id = ?, submitted_at = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND mapping_version_id = ?
                       AND lifecycle_state = 'VALIDATED'
                       AND dry_run_receipt_id IS NOT NULL
                       AND object_revision = ?
                    """, evidenceReceiptId, evidenceDigest, actorId, now, now,
                    current.tenantId(), current.mappingId(),
                    current.objectRevision());
            case APPROVED -> jdbc.update("""
                    UPDATE sys_connector_mapping_versions
                       SET lifecycle_state = 'APPROVED', approval_receipt_id = ?,
                           approval_evidence_digest = ?,
                           approval_step_up_evidence_digest = ?,
                           approved_by_actor_id = ?, approved_at = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND mapping_version_id = ?
                       AND lifecycle_state = 'PENDING_APPROVAL'
                       AND object_revision = ?
                    """, evidenceReceiptId, evidenceDigest, stepUpDigest,
                    actorId, now, now, current.tenantId(), current.mappingId(),
                    current.objectRevision());
            case ACTIVE -> {
                jdbc.query("""
                        SELECT mapping_version_id
                          FROM sys_connector_mapping_versions
                         WHERE tenant_id = ? AND definition_id = ?
                           AND mapping_key = 'default'
                           AND lifecycle_state = 'ACTIVE'
                         FOR UPDATE
                        """, ignored -> null,
                        current.tenantId(), current.definitionId());
                jdbc.update("""
                        UPDATE sys_connector_mapping_versions
                           SET lifecycle_state = 'SUSPENDED',
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND definition_id = ?
                           AND mapping_key = 'default'
                           AND lifecycle_state = 'ACTIVE'
                           AND mapping_version_id <> ?
                        """, now, current.tenantId(), current.definitionId(),
                        current.mappingId());
                yield jdbc.update("""
                        UPDATE sys_connector_mapping_versions
                           SET lifecycle_state = 'ACTIVE', activation_receipt_id = ?,
                               activation_evidence_digest = ?,
                               activation_step_up_evidence_digest = ?,
                               activated_by_actor_id = ?, activated_at = ?,
                               object_revision = object_revision + 1, updated_at = ?
                         WHERE tenant_id = ? AND mapping_version_id = ?
                           AND lifecycle_state = 'APPROVED' AND object_revision = ?
                        """, evidenceReceiptId, evidenceDigest, stepUpDigest,
                        actorId, now, now, current.tenantId(), current.mappingId(),
                        current.objectRevision());
            }
            default -> throw new IllegalArgumentException(
                    "unsupported mapping transition target: " + target);
        };
        if (changed != 1) throw conflict("SYS008_MAPPING_CAS_CONFLICT");
        if (incrementDefinitionAggregate(
                current.tenantId(), current.definitionId(), now) != 1) {
            throw conflict("SYS008_DEFINITION_AGGREGATE_CAS_CONFLICT");
        }
        return lockMapping(current.tenantId(), current.connectorId(),
                current.version());
    }

    @Override
    public MappingRecord recordDryRun(
            MappingRecord current,
            long actorId,
            UUID commandReceiptId,
            String resultRef,
            String resultDigest,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE sys_connector_mapping_versions
                   SET dry_run_receipt_id = ?, dry_run_result_ref = ?,
                       dry_run_result_digest = ?, dry_run_evidence_digest = ?,
                       dry_run_source_count = 0, dry_run_rejected_count = 0,
                       dry_run_by_actor_id = ?, dry_run_at = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                   AND lifecycle_state = 'VALIDATED'
                   AND dry_run_receipt_id IS NULL AND object_revision = ?
                """, commandReceiptId, resultRef, resultDigest, resultDigest,
                actorId, now, now, current.tenantId(), current.mappingId(),
                current.objectRevision());
        if (changed != 1) throw conflict("SYS008_DRY_RUN_CAS_CONFLICT");
        if (incrementDefinitionAggregate(
                current.tenantId(), current.definitionId(), now) != 1) {
            throw conflict("SYS008_DEFINITION_AGGREGATE_CAS_CONFLICT");
        }
        return lockMapping(current.tenantId(), current.connectorId(),
                current.version());
    }

    @Override
    public ExecutionRecord insertExecution(
            DefinitionRecord definition,
            MappingRecord mapping,
            UUID executionPublicId,
            long actorId,
            UUID commandReceiptId,
            ExecutionCommand command,
            String providerIdempotencyKey,
            String providerRequestDigest,
            long authorizationRevision,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId,
            List<IntegrationRuntimePorts.SourceManifestItem> sourceManifest,
            OffsetDateTime now) {
        UUID internalId = UUID.randomUUID();
        List<IntegrationRuntimePorts.SourceManifestItem> manifest =
                List.copyOf(sourceManifest);
        if (manifest.isEmpty() || manifest.size() > 10_000) {
            throw conflict("SYS008_SOURCE_MANIFEST_INVALID");
        }
        String sourceSetDigest = canonical.digest(manifest);
        jdbc.update("""
                INSERT INTO sys_connector_executions (
                    tenant_id, execution_id, public_id, definition_id,
                    definition_version_id, definition_version_number,
                    mapping_version_id, mapping_version_number,
                    endpoint_policy_revision, secret_revision, operation,
                    lifecycle_state, payload_ref, payload_digest,
                    source_count, source_item_set_digest,
                    selected_item_count, selected_item_digest,
                    accepted_count, rejected_count, quarantined_count,
                    provider_key, provider_operation,
                    provider_idempotency_key, provider_request_digest,
                    authorization_revision, population_kind,
                    legal_entity_public_id, original_command_receipt_id,
                    retry_generation, current_attempt_fence, object_revision,
                    requested_by_actor_id, requested_scope_digest,
                    requested_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, ?,
                        ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 1,
                        ?, ?, ?, ?)
                """, definition.tenantId(), internalId, executionPublicId,
                definition.definitionId(), definition.definitionVersionId(),
                definition.definitionVersion(), mapping.mappingId(),
                mapping.version(), definition.endpointPolicyRevision(),
                definition.secretRevision(), command.operation().name(),
                command.payloadRef(), command.payloadDigest(), manifest.size(),
                sourceSetDigest, manifest.size(), sourceSetDigest,
                definition.connectorId().toString(),
                command.operation().name(), providerIdempotencyKey,
                providerRequestDigest, authorizationRevision, populationKind,
                legalEntityPublicId, commandReceiptId,
                manifest.size(), actorId, requestedScopeDigest, now, now);
        int ordinal = 0;
        for (IntegrationRuntimePorts.SourceManifestItem source : manifest) {
            ordinal++;
            UUID itemId = UUID.randomUUID();
            UUID itemPublicId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sys_connector_execution_items (
                        tenant_id, execution_item_id, public_id, execution_id,
                        item_ordinal, source_key_digest, source_digest,
                        mapping_version_id, mapping_version_number,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, definition.tenantId(), itemId, itemPublicId,
                    internalId, ordinal, source.sourceKeyDigest(),
                    source.sourceDigest(), mapping.mappingId(), mapping.version(),
                    now, now);
            String itemProviderKey = "sys008-item-" +
                    IntegrationDomainPolicy.sha256(providerIdempotencyKey
                            + ":" + source.sourceKeyDigest());
            String itemRequestDigest = canonical.digest(
                    providerRequestDigest, source.sourceKeyDigest(),
                    source.sourceDigest(), "1");
            jdbc.update("""
                    INSERT INTO sys_connector_execution_attempts (
                        tenant_id, execution_attempt_id, public_id, execution_id,
                        execution_item_id, attempt_number, attempt_fence,
                        execution_revision_snapshot, lifecycle_state,
                        provider_idempotency_key, request_digest,
                        object_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 1, ?, 1, 'READY', ?, ?, 1, ?, ?)
                    """, definition.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                    internalId, itemId, ordinal, itemProviderKey,
                    itemRequestDigest, now, now);
        }
        return lockExecution(definition.tenantId(), executionPublicId);
    }

    @Override
    public ExecutionRecord lockExecution(
            long tenantId, UUID executionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                     FOR UPDATE OF e
                    """, (rs, row) -> execution(rs), tenantId, executionId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
    }

    @Override
    public ExecutionRecord lockExecutionAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                       AND e.requested_scope_digest = ?
                       AND e.population_kind = ?
                       AND e.legal_entity_public_id IS NOT DISTINCT FROM CAST(? AS UUID)
                     FOR UPDATE OF e
                    """, (rs, row) -> execution(rs), tenantId, executionId,
                    requestedScopeDigest, populationKind, legalEntityPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
    }

    @Override
    public ExecutionResponse executionResponse(
            long tenantId, UUID executionId) {
        ExecutionRecord record;
        try {
            record = jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                    """, (rs, row) -> execution(rs), tenantId, executionId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
        return new ExecutionResponse(record.executionId(),
                record.mappingVersion(), record.state(), record.sourceCount(),
                record.acceptedCount(), record.rejectedCount(),
                record.selectedItemCount(), record.selectedItemDigest(),
                record.reconciliationDigest(), record.aggregateRevision());
    }

    @Override
    public ExecutionResponse executionResponseAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId) {
        ExecutionRecord record;
        try {
            record = jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                       AND e.requested_scope_digest = ?
                       AND e.population_kind = ?
                       AND e.legal_entity_public_id IS NOT DISTINCT FROM CAST(? AS UUID)
                    """, (rs, row) -> execution(rs), tenantId, executionId,
                    requestedScopeDigest, populationKind, legalEntityPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
        return new ExecutionResponse(record.executionId(),
                record.mappingVersion(), record.state(), record.sourceCount(),
                record.acceptedCount(), record.rejectedCount(),
                record.selectedItemCount(), record.selectedItemDigest(),
                record.reconciliationDigest(), record.aggregateRevision());
    }

    @Override
    public ExecutionRecord beginDispatch(
            ExecutionRecord current,
            long expectedFence,
            OffsetDateTime now) {
        if (current.attemptFence() != expectedFence) {
            throw conflict("SYS008_STALE_ATTEMPT_FENCE");
        }
        int transforming = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'TRANSFORMING',
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'ACCEPTED' AND object_revision = ?
                   AND current_attempt_fence = ?
                """, now, current.tenantId(), current.internalId(),
                current.aggregateRevision(), expectedFence);
        if (transforming != 1) throw conflict("SYS008_DISPATCH_CAS_CONFLICT");
        if (current.retryGeneration() == 0) {
            List<InitialItem> items = jdbc.query("""
                    SELECT i.execution_item_id, i.source_digest,
                           a.provider_idempotency_key, a.attempt_fence
                      FROM sys_connector_execution_items i
                      JOIN sys_connector_execution_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.execution_item_id = i.execution_item_id
                       AND a.attempt_number = 1
                       AND a.lifecycle_state = 'READY'
                     WHERE i.tenant_id = ? AND i.execution_id = ?
                       AND i.lifecycle_state = 'PENDING'
                     ORDER BY i.item_ordinal
                     FOR UPDATE OF i, a
                    """, (rs, row) -> new InitialItem(
                    uuid(rs, "execution_item_id"),
                    rs.getString("source_digest"),
                    rs.getString("provider_idempotency_key"),
                    rs.getLong("attempt_fence")),
                    current.tenantId(), current.internalId());
            if (items.size() != current.sourceCount()) {
                throw conflict("SYS008_INITIAL_SOURCE_SET_CHANGED");
            }
            for (InitialItem item : items) {
                String transformedDigest = canonical.digest(
                        item.sourceDigest(), current.mappingDigest());
                int transformedItem = jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'TRANSFORMED',
                               transformed_digest = ?,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                           AND lifecycle_state = 'PENDING'
                        """, transformedDigest, now, current.tenantId(),
                        item.itemId());
                if (transformedItem != 1) {
                    throw conflict("SYS008_INITIAL_ITEM_TRANSFORM_CONFLICT");
                }
                int dispatchedItem = jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'DISPATCHED',
                               provider_item_key = ?,
                               latest_attempt_number = 1,
                               latest_attempt_fence = ?,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                           AND lifecycle_state = 'TRANSFORMED'
                        """, item.providerIdempotencyKey(), item.attemptFence(),
                        now, current.tenantId(), item.itemId());
                if (dispatchedItem != 1) {
                    throw conflict("SYS008_INITIAL_ITEM_DISPATCH_CONFLICT");
                }
            }
        }
        int dispatched = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'DISPATCHED',
                       unresolved_external_effect = TRUE,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'TRANSFORMING'
                   AND object_revision = ?
                   AND current_attempt_fence = ?
                """, now, current.tenantId(), current.internalId(),
                current.aggregateRevision() + 1, expectedFence);
        if (dispatched != 1) throw conflict("SYS008_DISPATCH_FENCE_CONFLICT");
        int dispatchedAttempts = jdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'DISPATCHED', dispatched_at = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'READY'
                """, now, now, current.tenantId(), current.internalId());
        if (dispatchedAttempts < 1) {
            throw conflict("SYS008_READY_ATTEMPT_REQUIRED");
        }
        return lockExecution(current.tenantId(), current.executionId());
    }

    @Override
    public List<AttemptRecord> currentAttempts(ExecutionRecord current) {
        List<AttemptRecord> attempts = jdbc.query("""
                SELECT a.execution_attempt_id, a.execution_item_id,
                       i.public_id AS item_public_id, a.attempt_number,
                       a.attempt_fence, a.provider_idempotency_key,
                       a.request_digest
                  FROM sys_connector_execution_attempts a
                  JOIN sys_connector_execution_items i
                    ON i.tenant_id = a.tenant_id
                   AND i.execution_item_id = a.execution_item_id
                   AND i.latest_attempt_number = a.attempt_number
                   AND i.latest_attempt_fence = a.attempt_fence
                 WHERE a.tenant_id = ? AND a.execution_id = ?
                   AND i.lifecycle_state IN (
                       'DISPATCHED', 'RESULT_UNKNOWN', 'ACCEPTED',
                       'REJECTED_RETRYABLE')
                   AND a.lifecycle_state IN (
                       'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED',
                       'RECONCILED', 'FAILED')
                 ORDER BY a.attempt_fence
                 FOR UPDATE OF a, i
                """, (rs, row) -> new AttemptRecord(
                uuid(rs, "execution_attempt_id"),
                uuid(rs, "execution_item_id"), uuid(rs, "item_public_id"),
                rs.getInt("attempt_number"), rs.getLong("attempt_fence"),
                rs.getString("provider_idempotency_key"),
                rs.getString("request_digest")),
                current.tenantId(), current.internalId());
        if (attempts.isEmpty()) {
            throw conflict("SYS008_CURRENT_ATTEMPT_REQUIRED");
        }
        return List.copyOf(attempts);
    }

    @Override
    public ExecutionRecord transitionExecution(
            ExecutionRecord current,
            ExecutionState target,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = ?, object_revision = object_revision + 1,
                       updated_at = ?, completed_at = CASE
                           WHEN ? IN ('RECONCILED', 'DEAD_LETTER', 'CANCELLED')
                           THEN ? ELSE completed_at END
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, target.name(), now, target.name(), now,
                current.tenantId(), current.internalId(), current.state().name(),
                current.aggregateRevision());
        if (changed != 1) throw conflict("SYS008_EXECUTION_CAS_CONFLICT");
        return lockExecution(current.tenantId(), current.executionId());
    }

    @Override
    public CompensationClaim claimCompensation(
            ExecutionRecord current,
            String claimDigest,
            Long expectedRevision,
            OffsetDateTime now) {
        if (claimDigest == null || !claimDigest.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException(
                    "SYS008_COMPENSATION_CLAIM_DIGEST_INVALID");
        }
        if (Set.of(ExecutionState.RECONCILED, ExecutionState.DEAD_LETTER,
                ExecutionState.CANCELLED).contains(current.state())) {
            throw conflict("SYS008_COMPENSATION_CLAIM_STATE_INVALID");
        }
        String originalPrincipal = jdbc.queryForObject(
                "SELECT current_setting('dwp.principal_public_id', true)",
                String.class);
        if (originalPrincipal == null || originalPrincipal.isBlank()) {
            throw new IllegalStateException(
                    "SYS008_TRUSTED_PRINCIPAL_CONTEXT_REQUIRED");
        }
        String claimKey = "sys008-comp-claim-" + current.executionId();
        try {
            jdbc.queryForObject(
                    "SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, COMPENSATION_CLAIM_PRINCIPAL.toString());
            List<String> existing = jdbc.query("""
                    SELECT request_digest
                      FROM sys_hris_command_receipts
                     WHERE tenant_id = ?
                       AND subject_principal_public_id = ?
                       AND originating_action = ?
                       AND idempotency_key = ?
                     FOR UPDATE
                    """, (rs, row) -> rs.getString(1), current.tenantId(),
                    COMPENSATION_CLAIM_PRINCIPAL,
                    IntegrationService.CANCEL_EXECUTION, claimKey);
            if (!existing.isEmpty()) {
                if (!claimDigest.equals(existing.getFirst())) {
                    throw conflict(
                            "SYS008_COMPENSATION_CLAIM_DIGEST_CONFLICT");
                }
                return new CompensationClaim(current, claimDigest, false);
            }
            if (expectedRevision == null
                    || expectedRevision != current.aggregateRevision()) {
                throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                        "SYS008_EXECUTION_REVISION_CONFLICT");
            }
            UUID claimReceiptId = UUID.randomUUID();
            long claimedRevision = current.aggregateRevision() + 1;
            int inserted = jdbc.update("""
                    INSERT INTO sys_hris_command_receipts (
                        public_id, tenant_id, command_type, originating_action,
                        subject_principal_public_id, population_scope_digest,
                        authority_population_scope_digest,
                        field_policy_revision, authority_field_policy_revision,
                        purpose_code, authorization_revision,
                        authority_authorization_revision,
                        origin_route_contract_key, origin_context_key,
                        origin_context_scope_key, origin_decision_revision,
                        idempotency_key, request_digest, status, correlation_id,
                        created_at)
                    VALUES (?, ?, 'COMPENSATION_EFFECT_CLAIM', ?, ?, ?, ?,
                            1, 1, ?, ?, ?, 'SYS-API-026',
                            'sys008.compensation-claim',
                            'sys008.compensation-claim', ?, ?, ?, 'ACCEPTED',
                            ?, ?)
                    """, claimReceiptId, current.tenantId(),
                    IntegrationService.CANCEL_EXECUTION,
                    COMPENSATION_CLAIM_PRINCIPAL,
                    current.requestedScopeDigest(),
                    current.requestedScopeDigest(),
                    IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE,
                    current.authorizationRevision(),
                    current.authorizationRevision(),
                    "execution:" + current.executionId(), claimKey,
                    claimDigest, current.correlationId(), now);
            if (inserted != 1) {
                throw conflict("SYS008_COMPENSATION_CLAIM_CAS_CONFLICT");
            }
            int changed = jdbc.update("""
                    UPDATE sys_connector_executions
                       SET object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND execution_id = ?
                       AND lifecycle_state = ? AND object_revision = ?
                    """, now, current.tenantId(), current.internalId(),
                    current.state().name(), current.aggregateRevision());
            if (changed != 1) {
                throw conflict("SYS008_COMPENSATION_CLAIM_CAS_CONFLICT");
            }
            changed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'RUNNING', aggregate_public_id = ?,
                           aggregate_revision = ?, result_ref = ?
                     WHERE tenant_id = ? AND public_id = ?
                       AND status = 'ACCEPTED'
                    """, current.executionId(), claimedRevision,
                    "/v1/admin/hris/connector-executions/"
                            + current.executionId(), current.tenantId(),
                    claimReceiptId);
            if (changed != 1) {
                throw conflict("SYS008_COMPENSATION_CLAIM_CAS_CONFLICT");
            }
            jdbc.queryForObject(
                    "SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, originalPrincipal);
            ExecutionRecord claimed = lockExecution(
                    current.tenantId(), current.executionId());
            return new CompensationClaim(claimed, claimDigest, true);
        } finally {
            jdbc.queryForObject(
                    "SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, originalPrincipal);
        }
    }

    @Override
    public ExecutionRecord markCompensationResultUnknown(
            ExecutionRecord current,
            String operationsExceptionRef,
            OffsetDateTime now) {
        if (operationsExceptionRef == null || operationsExceptionRef.isBlank()) {
            throw new IllegalArgumentException(
                    "SYS008_OPERATIONS_EXCEPTION_REF_REQUIRED");
        }
        if (Set.of(ExecutionState.RECONCILED, ExecutionState.DEAD_LETTER,
                ExecutionState.CANCELLED).contains(current.state())) {
            throw conflict("SYS008_COMPENSATION_UNKNOWN_STATE_INVALID");
        }
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET unresolved_external_effect = TRUE,
                       operations_exception_ref = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, operationsExceptionRef, now,
                current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision());
        if (changed != 1) {
            throw conflict("SYS008_COMPENSATION_UNKNOWN_CAS_CONFLICT");
        }
        return lockExecution(current.tenantId(), current.executionId());
    }

    @Override
    public ExecutionRecord beginReconciliation(
            ExecutionRecord current,
            String providerReceiptRef,
            String providerReceiptDigest,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(
                current.state(), ExecutionState.RECONCILING);
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RECONCILING',
                       provider_receipt_ref = ?,
                       provider_receipt_digest = ?,
                       reconciliation_receipt_ref = ?,
                       reconciliation_receipt_digest = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN ('RESULT_UNKNOWN', 'ACKNOWLEDGED')
                   AND object_revision = ?
                   AND provider_idempotency_key = ?
                """, providerReceiptRef, providerReceiptDigest,
                providerReceiptRef, providerReceiptDigest, now,
                current.tenantId(), current.internalId(),
                current.aggregateRevision(), current.providerIdempotencyKey());
        if (changed != 1) throw conflict("SYS008_RECONCILIATION_CAS_CONFLICT");
        return lockExecution(current.tenantId(), current.executionId());
    }

    @Override
    public ExecutionRecord applyProviderEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            ExecutionState target,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        if (evidence.sourceCount() != current.sourceCount()) {
            throw conflict("SYS008_PROVIDER_SOURCE_SNAPSHOT_CHANGED");
        }
        String evidenceDigest = canonical.digest(evidence);
        String operationsExceptionRef = target == ExecutionState.RESULT_UNKNOWN
                ? "hris-operations/sys008/" + current.executionId() : null;
        applyAttemptAndItemEvidence(current, evidence, evidenceDigest, target, now);
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = ?,
                       provider_receipt_ref = CASE
                           WHEN lifecycle_state = 'DISPATCHED'
                           THEN ?
                           ELSE provider_receipt_ref END,
                       provider_receipt_digest = CASE
                           WHEN lifecycle_state = 'DISPATCHED'
                           THEN ?
                           ELSE provider_receipt_digest END,
                       provider_evidence_digest = CASE
                           WHEN lifecycle_state = 'DISPATCHED'
                           THEN ? ELSE provider_evidence_digest END,
                       provider_failure_conclusive = ?,
                       unresolved_external_effect = ?,
                       accepted_count = ?, rejected_count = ?,
                       quarantined_count = ?, reconciliation_digest = CASE
                           WHEN lifecycle_state = 'RECONCILING'
                           THEN ? ELSE reconciliation_digest END,
                       operations_exception_ref = COALESCE(?, operations_exception_ref),
                       object_revision = object_revision + 1, updated_at = ?,
                       completed_at = CASE WHEN ? = 'RECONCILED' THEN ?
                                           ELSE completed_at END
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                   AND provider_idempotency_key = ?
                   AND provider_request_digest = ?
                """, target.name(), evidence.receiptRef(),
                evidence.receiptDigest(), evidenceDigest,
                target == ExecutionState.FAILED,
                target == ExecutionState.RESULT_UNKNOWN,
                evidence.acceptedCount(), evidence.rejectedCount(),
                evidence.quarantinedCount(),
                evidence.reconciliationDigest(), operationsExceptionRef, now,
                target.name(), now, current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision(),
                current.providerIdempotencyKey(), current.providerRequestDigest());
        if (changed != 1) throw conflict("SYS008_PROVIDER_EVIDENCE_CAS_CONFLICT");
        return lockExecution(current.tenantId(), current.executionId());
    }

    private void applyAttemptAndItemEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            String evidenceDigest,
            ExecutionState target,
            OffsetDateTime now) {
        List<AttemptRecord> attempts = currentAttempts(current);
        Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence> itemEvidence =
                normalizedItemEvidence(current, evidence, attempts, target);
        if (current.state() == ExecutionState.DISPATCHED) {
            for (AttemptRecord attempt : attempts) {
                var item = itemEvidence.get(attempt.itemPublicId());
                requireAttemptBinding(attempt, item);
                String attemptTarget = switch (item.outcome()) {
                    case ACKNOWLEDGED -> "ACKNOWLEDGED";
                    case FAILED -> "FAILED";
                    case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
                    default -> throw conflict(
                            "SYS008_ATTEMPT_PROVIDER_OUTCOME_INVALID");
                };
                String receiptRef = item.receiptRef() == null
                        ? evidence.receiptRef() : item.receiptRef();
                String receiptDigest = item.receiptDigest() == null
                        ? evidence.receiptDigest() : item.receiptDigest();
                String failureClass = "FAILED".equals(attemptTarget)
                        ? requiredFailureClass(item, "PROVIDER_FAILURE") : null;
                int attemptChanged = jdbc.update("""
                        UPDATE sys_connector_execution_attempts
                           SET lifecycle_state = ?,
                               provider_receipt_ref = ?,
                               provider_receipt_digest = ?,
                               response_digest = ?, failure_class = ?,
                               acknowledged_at = CASE WHEN ? = 'ACKNOWLEDGED'
                                   THEN ? ELSE acknowledged_at END,
                               completed_at = CASE WHEN ? = 'FAILED'
                                   THEN ? ELSE completed_at END,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_attempt_id = ?
                           AND lifecycle_state = 'DISPATCHED'
                        """, attemptTarget, receiptRef, receiptDigest,
                        evidenceDigest, failureClass, attemptTarget, now,
                        attemptTarget, now, now, current.tenantId(),
                        attempt.attemptId());
                if (attemptChanged != 1) {
                    throw conflict("SYS008_ATTEMPT_PROVIDER_EVIDENCE_CONFLICT");
                }
                String itemTarget = switch (item.outcome()) {
                    case ACKNOWLEDGED -> "ACCEPTED";
                    case FAILED -> item.retryable()
                            ? "REJECTED_RETRYABLE" : "REJECTED_FINAL";
                    case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
                    default -> throw conflict(
                            "SYS008_ITEM_PROVIDER_OUTCOME_INVALID");
                };
                int itemChanged = jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = ?,
                               provider_receipt_ref = ?,
                               provider_receipt_digest = ?,
                               provider_evidence_digest = ?,
                               failure_class = ?,
                               retryable_snapshot = CASE
                                   WHEN ? = 'REJECTED_RETRYABLE' THEN TRUE
                                   WHEN ? = 'REJECTED_FINAL' THEN FALSE
                                   ELSE NULL END,
                               quarantined_snapshot = CASE
                                   WHEN ? IN (
                                       'REJECTED_RETRYABLE', 'REJECTED_FINAL')
                                   THEN ? ELSE FALSE END,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                           AND lifecycle_state = 'DISPATCHED'
                        """, itemTarget, receiptRef, receiptDigest,
                        evidenceDigest, failureClass, itemTarget, itemTarget,
                        itemTarget, item.quarantined(), now,
                        current.tenantId(), attempt.itemId());
                if (itemChanged != 1) {
                    throw conflict("SYS008_ITEM_PROVIDER_EVIDENCE_CONFLICT");
                }
            }
            requireRelationalCounts(current, evidence);
            return;
        }
        if (current.state() != ExecutionState.RECONCILING
                || target == ExecutionState.RESULT_UNKNOWN) {
            return;
        }
        for (AttemptRecord attempt : attempts) {
            var item = itemEvidence.get(attempt.itemPublicId());
            requireAttemptBinding(attempt, item);
            boolean reconciled = item.outcome()
                    == IntegrationRuntimePorts.ProviderEvidence.Outcome.RECONCILED;
            String attemptTarget = reconciled ? "RECONCILED" : "FAILED";
            String itemTarget = reconciled ? "RECONCILED"
                    : item.retryable() ? "REJECTED_RETRYABLE" : "REJECTED_FINAL";
            String receiptRef = item.receiptRef() == null
                    ? current.providerReceiptRef() : item.receiptRef();
            String receiptDigest = item.receiptDigest() == null
                    ? current.providerReceiptDigest() : item.receiptDigest();
            String reconciliationDigest = item.reconciliationDigest() == null
                    ? evidence.reconciliationDigest()
                    : item.reconciliationDigest();
            String failureClass = reconciled ? null
                    : requiredFailureClass(item, "RECONCILIATION_FAILURE");
            int attemptChanged = jdbc.update("""
                    UPDATE sys_connector_execution_attempts
                       SET lifecycle_state = ?, provider_receipt_ref = ?,
                           provider_receipt_digest = ?,
                           reconciliation_digest = ?, failure_class = ?,
                           completed_at = ?,
                           object_revision = object_revision + 1,
                           updated_at = ?
                     WHERE tenant_id = ? AND execution_attempt_id = ?
                       AND lifecycle_state IN ('RESULT_UNKNOWN', 'ACKNOWLEDGED')
                    """, attemptTarget, receiptRef, receiptDigest,
                    reconciliationDigest, failureClass, now, now,
                    current.tenantId(), attempt.attemptId());
            if (attemptChanged != 1) {
                throw conflict("SYS008_ATTEMPT_RECONCILIATION_CONFLICT");
            }
            int itemChanged = jdbc.update("""
                    UPDATE sys_connector_execution_items
                       SET lifecycle_state = ?, provider_receipt_ref = ?,
                           provider_receipt_digest = ?,
                           reconciliation_digest = ?, failure_class = ?,
                           retryable_snapshot = CASE
                               WHEN ? = 'REJECTED_RETRYABLE' THEN TRUE
                               WHEN ? = 'REJECTED_FINAL' THEN FALSE
                               ELSE NULL END,
                           quarantined_snapshot = CASE
                               WHEN ? IN (
                                   'REJECTED_RETRYABLE', 'REJECTED_FINAL')
                               THEN ? ELSE FALSE END,
                           object_revision = object_revision + 1,
                           updated_at = ?
                     WHERE tenant_id = ? AND execution_item_id = ?
                       AND lifecycle_state IN ('RESULT_UNKNOWN', 'ACCEPTED')
                    """, itemTarget, receiptRef, receiptDigest,
                    reconciliationDigest, failureClass, itemTarget, itemTarget,
                    itemTarget, item.quarantined(), now,
                    current.tenantId(), attempt.itemId());
            if (itemChanged != 1) {
                throw conflict("SYS008_ITEM_RECONCILIATION_CONFLICT");
            }
        }
        requireRelationalCounts(current, evidence);
    }

    private Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence>
            normalizedItemEvidence(
                    ExecutionRecord current,
                    IntegrationRuntimePorts.ProviderEvidence evidence,
                    List<AttemptRecord> attempts,
                    ExecutionState target) {
        List<IntegrationRuntimePorts.ProviderItemEvidence> supplied =
                evidence.items();
        if (supplied.isEmpty() && attempts.size() == 1) {
            AttemptRecord attempt = attempts.getFirst();
            boolean failed = target == ExecutionState.FAILED
                    || target == ExecutionState.PARTIAL;
            supplied = List.of(new IntegrationRuntimePorts.ProviderItemEvidence(
                    attempt.itemPublicId(), evidence.outcome(),
                    attempt.providerIdempotencyKey(), attempt.requestDigest(),
                    evidence.receiptRef(), evidence.receiptDigest(),
                    evidence.reconciliationDigest(),
                    evidence.quarantinedCount() > 0,
                    failed ? "PROVIDER_RETRYABLE" : null, failed));
        }
        Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence> indexed =
                new LinkedHashMap<>();
        for (var item : supplied) {
            if (indexed.put(item.itemPublicId(), item) != null) {
                throw conflict("SYS008_PROVIDER_ITEM_EVIDENCE_DUPLICATE");
            }
        }
        Set<UUID> expected = attempts.stream()
                .map(AttemptRecord::itemPublicId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!indexed.keySet().equals(expected)
                || indexed.size() != current.selectedItemCount()) {
            throw conflict("SYS008_PROVIDER_ITEM_EVIDENCE_SET_CHANGED");
        }
        return Map.copyOf(indexed);
    }

    private static void requireAttemptBinding(
            AttemptRecord attempt,
            IntegrationRuntimePorts.ProviderItemEvidence evidence) {
        if (evidence == null
                || !java.util.Objects.equals(
                        attempt.providerIdempotencyKey(),
                        evidence.providerIdempotencyKey())
                || !java.util.Objects.equals(
                        attempt.requestDigest(), evidence.requestDigest())) {
            throw conflict("SYS008_PROVIDER_ITEM_ATTEMPT_BINDING_CHANGED");
        }
    }

    private static String requiredFailureClass(
            IntegrationRuntimePorts.ProviderItemEvidence evidence,
            String fallback) {
        String value = evidence.failureClass() == null
                ? fallback : evidence.failureClass();
        if (!value.matches("^[A-Z][A-Z0-9_]{2,79}$")) {
            throw conflict("SYS008_PROVIDER_FAILURE_CLASS_INVALID");
        }
        return value;
    }

    private void requireRelationalCounts(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence) {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) AS source_count,
                       COUNT(*) FILTER (WHERE lifecycle_state IN (
                           'ACCEPTED', 'RECONCILED')) AS accepted_count,
                       COUNT(*) FILTER (WHERE lifecycle_state IN (
                           'REJECTED_RETRYABLE', 'REJECTED_FINAL')) AS rejected_count,
                       COUNT(*) FILTER (
                           WHERE quarantined_snapshot = TRUE) AS quarantined_count
                  FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_id = ?
                """, current.tenantId(), current.internalId());
        if (((Number) counts.get("source_count")).longValue()
                    != evidence.sourceCount()
                || ((Number) counts.get("accepted_count")).longValue()
                    != evidence.acceptedCount()
                || ((Number) counts.get("rejected_count")).longValue()
                    != evidence.rejectedCount()
                || ((Number) counts.get("quarantined_count")).longValue()
                    != evidence.quarantinedCount()) {
            throw conflict("SYS008_PROVIDER_EVIDENCE_COUNTS_CHANGED");
        }
    }

    @Override
    public List<UUID> retryableItemIds(ExecutionRecord current) {
        return jdbc.query("""
                SELECT public_id
                  FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'REJECTED_RETRYABLE'
                   AND retryable_snapshot = TRUE
                 ORDER BY public_id::text
                """, (rs, row) -> uuid(rs, "public_id"),
                current.tenantId(), current.internalId());
    }

    @Override
    public ExecutionRecord createRetryAttempts(
            ExecutionRecord current,
            List<UUID> exactFailedItemIds,
            String failedItemSetDigest,
            long actorId,
            OffsetDateTime now) {
        long fence = current.attemptFence();
        for (UUID publicItemId : exactFailedItemIds) {
            RetryItem item;
            try {
                item = jdbc.queryForObject("""
                        SELECT execution_item_id, latest_attempt_number,
                               latest_attempt_fence, source_digest,
                               mapping_version_id, mapping_version_number
                          FROM sys_connector_execution_items
                         WHERE tenant_id = ? AND execution_id = ?
                           AND public_id = ?
                           AND lifecycle_state = 'REJECTED_RETRYABLE'
                           AND retryable_snapshot = TRUE
                         FOR UPDATE
                        """, (rs, row) -> new RetryItem(
                        uuid(rs, "execution_item_id"),
                        rs.getInt("latest_attempt_number"),
                        rs.getLong("latest_attempt_fence"),
                        rs.getString("source_digest"),
                        uuid(rs, "mapping_version_id"),
                        rs.getLong("mapping_version_number")),
                        current.tenantId(), current.internalId(), publicItemId);
            } catch (EmptyResultDataAccessException changed) {
                throw conflict("SYS008_RETRY_ITEM_SET_CHANGED");
            }
            fence++;
            int attemptNumber = item.attemptNumber() + 1;
            String providerKey = "sys008-" + IntegrationDomainPolicy.sha256(
                    current.providerIdempotencyKey() + ":retry:"
                            + current.retryGeneration() + 1 + ":" + publicItemId);
            String requestDigest = canonical.digest(
                    current.providerRequestDigest(), item.sourceDigest(),
                    Long.toString(item.mappingVersionNumber()),
                    Integer.toString(attemptNumber));
            String transformedDigest = canonical.digest(
                    item.sourceDigest(), current.mappingDigest());
            jdbc.update("""
                    INSERT INTO sys_connector_execution_attempts (
                        tenant_id, execution_attempt_id, public_id, execution_id,
                        execution_item_id, attempt_number, attempt_fence,
                        execution_revision_snapshot, lifecycle_state,
                        provider_idempotency_key, request_digest,
                        object_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, 1, ?, ?)
                    """, current.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                    current.internalId(), item.executionItemId(), attemptNumber,
                    fence, current.aggregateRevision(), providerKey,
                    requestDigest, now, now);
            int itemChanged = jdbc.update("""
                    UPDATE sys_connector_execution_items
                       SET lifecycle_state = 'DISPATCHED',
                           transformed_digest = COALESCE(transformed_digest, ?),
                           provider_item_key = COALESCE(provider_item_key, ?),
                           quarantined_snapshot = FALSE,
                           latest_attempt_number = ?, latest_attempt_fence = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND execution_item_id = ?
                       AND lifecycle_state = 'REJECTED_RETRYABLE'
                       AND latest_attempt_number = ? AND latest_attempt_fence = ?
                    """, transformedDigest, providerKey,
                    attemptNumber, fence, now, current.tenantId(),
                    item.executionItemId(), item.attemptNumber(), item.attemptFence());
            if (itemChanged != 1) throw conflict("SYS008_RETRY_ITEM_FENCE_CONFLICT");
        }
        int parent = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'ACCEPTED',
                       selected_item_count = ?, selected_item_digest = ?,
                       accepted_count = (
                           SELECT COUNT(*)
                             FROM sys_connector_execution_items item
                            WHERE item.tenant_id = sys_connector_executions.tenant_id
                              AND item.execution_id =
                                  sys_connector_executions.execution_id
                              AND item.lifecycle_state IN (
                                  'ACCEPTED', 'RECONCILED')),
                       rejected_count = (
                           SELECT COUNT(*)
                             FROM sys_connector_execution_items item
                            WHERE item.tenant_id = sys_connector_executions.tenant_id
                              AND item.execution_id =
                                  sys_connector_executions.execution_id
                              AND item.lifecycle_state IN (
                                  'REJECTED_RETRYABLE', 'REJECTED_FINAL')),
                       quarantined_count = (
                           SELECT COUNT(*)
                             FROM sys_connector_execution_items item
                            WHERE item.tenant_id = sys_connector_executions.tenant_id
                              AND item.execution_id =
                                  sys_connector_executions.execution_id
                              AND item.quarantined_snapshot = TRUE),
                       retry_generation = retry_generation + 1,
                       current_attempt_fence = ?,
                       provider_failure_conclusive = FALSE,
                       unresolved_external_effect = FALSE,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN ('FAILED', 'PARTIAL')
                   AND object_revision = ? AND current_attempt_fence = ?
                """, exactFailedItemIds.size(), failedItemSetDigest, fence, now,
                current.tenantId(), current.internalId(),
                current.aggregateRevision(), current.attemptFence());
        if (parent != 1) throw conflict("SYS008_RETRY_PARENT_CAS_CONFLICT");
        return lockExecution(current.tenantId(), current.executionId());
    }

    @Override
    public ExecutionRecord deadLetter(
            ExecutionRecord current,
            long actorId,
            OffsetDateTime now) {
        if (current.state() != ExecutionState.FAILED
                && current.state() != ExecutionState.PARTIAL) {
            throw conflict("SYS008_DEAD_LETTER_STATE_INVALID");
        }
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'DEAD_LETTER',
                       object_revision = object_revision + 1,
                       completed_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, now, now, current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision());
        if (changed != 1) throw conflict("SYS008_DEAD_LETTER_CAS_CONFLICT");
        return lockExecution(current.tenantId(), current.executionId());
    }

    @Override
    public ExecutionRecord cancel(
            ExecutionRecord current,
            String compensationPolicyRef,
            String compensationPolicyDigest,
            String compensationReceiptRef,
            String compensationReceiptDigest,
            long actorId,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(
                current.state(), ExecutionState.CANCELLED);
        int parent = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'CANCELLED',
                       compensation_policy_ref = ?, compensation_policy_digest = ?,
                       compensation_receipt_ref = ?, compensation_receipt_digest = ?,
                       unresolved_external_effect = FALSE,
                       object_revision = object_revision + 1,
                       completed_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, compensationPolicyRef, compensationPolicyDigest,
                compensationReceiptRef, compensationReceiptDigest,
                now, now, current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision());
        if (parent != 1) throw conflict("SYS008_CANCEL_PARENT_CAS_CONFLICT");
        jdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'CANCELLED',
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN ('PENDING', 'TRANSFORMED', 'DISPATCHED',
                       'RESULT_UNKNOWN', 'ACCEPTED', 'REJECTED_RETRYABLE')
                """, now, current.tenantId(), current.internalId());
        jdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'CANCELLED', completed_at = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN (
                       'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED')
                """, now, now, current.tenantId(), current.internalId());
        return lockExecution(current.tenantId(), current.executionId());
    }

    @Override
    public OwnerPage ownerPage(
            TrustedOwnerQuery query,
            String afterItemPublicId,
            List<String> allowedActions) {
        OffsetDateTime asOf = OffsetDateTime.ofInstant(
                query.asOf(), java.time.ZoneOffset.UTC);
        String sortExpression = switch (query.sort().split(":", 2)[0]) {
            case "connectorMetadata" -> "LOWER(connector_key)";
            case "policyVersion" -> "definition_version";
            case "executionPolicy" ->
                    "COALESCE(direction, '') || ':' || "
                            + "COALESCE(compatibility_mode, '')";
            case "effectiveStatus" -> "definition_state";
            default -> "public_id::text";
        };
        boolean descending = query.sort().endsWith(":DESC");
        StringBuilder sql = new StringBuilder("""
                WITH bounds AS (
                    SELECT ?::timestamptz AS as_of
                ), owner_rows AS (
                    SELECT d.public_id, d.connector_key,
                           (1
                            + CASE WHEN v.validated_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.tested_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.submitted_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.approved_at <= b.as_of THEN 1 ELSE 0 END
                            + CASE WHEN v.activated_at <= b.as_of THEN 1 ELSE 0 END
                            + COALESCE((
                                SELECT SUM(1
                                    + CASE WHEN mv.validated_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.dry_run_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.submitted_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.approved_at <= b.as_of
                                        THEN 1 ELSE 0 END
                                    + CASE WHEN mv.activated_at <= b.as_of
                                        THEN 1 ELSE 0 END)
                                  FROM sys_connector_mapping_versions mv
                                 WHERE mv.tenant_id = d.tenant_id
                                   AND mv.definition_id = d.definition_id
                                   AND mv.created_at <= b.as_of), 0))::bigint
                               AS aggregate_revision,
                           GREATEST(d.created_at, v.state_updated_at,
                               COALESCE(m.state_updated_at, d.created_at))
                               AS definition_updated_at,
                           v.connector_type,
                           v.version_number AS definition_version,
                           v.state_as_of AS definition_state,
                           m.version_number AS mapping_version,
                           m.state_as_of AS mapping_state,
                           m.direction, m.compatibility_mode, m.effective_from
                      FROM sys_connector_definitions d
                      CROSS JOIN bounds b
                      JOIN LATERAL (
                           SELECT version.*,
                                  CASE
                                    WHEN version.activated_at <= b.as_of
                                        THEN 'ACTIVE'
                                    WHEN version.approved_at <= b.as_of
                                        THEN 'APPROVED'
                                    WHEN version.submitted_at <= b.as_of
                                        THEN 'PENDING_APPROVAL'
                                    WHEN version.tested_at <= b.as_of
                                        THEN 'TESTED'
                                    WHEN version.validated_at <= b.as_of
                                        THEN 'VALIDATED'
                                    ELSE 'DRAFT'
                                  END AS state_as_of,
                                  GREATEST(version.created_at,
                                    CASE WHEN version.validated_at <= b.as_of
                                        THEN version.validated_at END,
                                    CASE WHEN version.tested_at <= b.as_of
                                        THEN version.tested_at END,
                                    CASE WHEN version.submitted_at <= b.as_of
                                        THEN version.submitted_at END,
                                    CASE WHEN version.approved_at <= b.as_of
                                        THEN version.approved_at END,
                                    CASE WHEN version.activated_at <= b.as_of
                                        THEN version.activated_at END)
                                      AS state_updated_at
                             FROM sys_connector_definition_versions version
                            WHERE version.tenant_id = d.tenant_id
                              AND version.definition_id = d.definition_id
                              AND version.created_at <= b.as_of
                            ORDER BY version.version_number DESC LIMIT 1
                      ) v ON TRUE
                      LEFT JOIN LATERAL (
                           SELECT mapped.*
                             FROM (
                                  SELECT mapping.*,
                                         CASE
                                           WHEN mapping.activated_at <= b.as_of
                                               THEN 'ACTIVE'
                                           WHEN mapping.approved_at <= b.as_of
                                               THEN 'APPROVED'
                                           WHEN mapping.submitted_at <= b.as_of
                                               THEN 'PENDING_APPROVAL'
                                           WHEN mapping.validated_at <= b.as_of
                                               THEN 'VALIDATED'
                                           ELSE 'DRAFT'
                                         END AS state_as_of,
                                         GREATEST(mapping.created_at,
                                           CASE WHEN mapping.validated_at <= b.as_of
                                               THEN mapping.validated_at END,
                                           CASE WHEN mapping.dry_run_at <= b.as_of
                                               THEN mapping.dry_run_at END,
                                           CASE WHEN mapping.submitted_at <= b.as_of
                                               THEN mapping.submitted_at END,
                                           CASE WHEN mapping.approved_at <= b.as_of
                                               THEN mapping.approved_at END,
                                           CASE WHEN mapping.activated_at <= b.as_of
                                               THEN mapping.activated_at END)
                                             AS state_updated_at
                                    FROM sys_connector_mapping_versions mapping
                                   WHERE mapping.tenant_id = d.tenant_id
                                     AND mapping.definition_id = d.definition_id
                                     AND mapping.created_at <= b.as_of
                             ) mapped
                            ORDER BY (mapped.state_as_of = 'ACTIVE') DESC,
                                     mapped.version_number DESC LIMIT 1
                      ) m ON TRUE
                     WHERE d.tenant_id = ? AND d.created_at <= b.as_of
                )
                SELECT * FROM owner_rows WHERE TRUE
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(asOf);
        arguments.add(query.tenantId());
        appendOwnerFilters(sql, arguments, query.filters());
        if (afterItemPublicId != null) {
            String comparator = descending ? " < " : " > ";
            if ("public_id::text".equals(sortExpression)) {
                sql.append(" AND public_id::text").append(comparator)
                        .append("?");
                arguments.add(afterItemPublicId);
            } else {
                sql.append(" AND ((").append(sortExpression).append(')')
                        .append(comparator).append("(SELECT ")
                        .append(sortExpression)
                        .append(" FROM owner_rows WHERE public_id::text = ?)")
                        .append(" OR ((").append(sortExpression)
                        .append(") = (SELECT ").append(sortExpression)
                        .append(" FROM owner_rows WHERE public_id::text = ?)")
                        .append(" AND public_id::text > ?))");
                arguments.add(afterItemPublicId);
                arguments.add(afterItemPublicId);
                arguments.add(afterItemPublicId);
            }
        }
        sql.append(" ORDER BY ").append(sortExpression)
                .append(descending ? " DESC" : " ASC")
                .append(", public_id::text ASC LIMIT ?");
        arguments.add(query.limit() + 1);
        List<OwnerRow> rows = jdbc.query(sql.toString(), (rs, row) -> new OwnerRow(
                uuid(rs, "public_id"), rs.getString("connector_key"),
                rs.getLong("aggregate_revision"),
                rs.getObject("definition_updated_at", OffsetDateTime.class),
                rs.getString("connector_type"),
                rs.getLong("definition_version"),
                rs.getString("definition_state"),
                nullableLong(rs, "mapping_version"),
                rs.getString("mapping_state"), rs.getString("direction"),
                rs.getString("compatibility_mode"),
                rs.getObject("effective_from", OffsetDateTime.class)),
                arguments.toArray());
        boolean hasMore = rows.size() > query.limit();
        List<OwnerRow> selected = rows.stream().limit(query.limit()).toList();
        List<OwnerItem> items = selected.stream().map(row -> {
            EnumMap<FieldGroup, Object> fields = new EnumMap<>(FieldGroup.class);
            fields.put(FieldGroup.CONNECTOR_METADATA, Map.of(
                    "connectorKey", row.connectorKey(),
                    "connectorType", row.connectorType()));
            fields.put(FieldGroup.POLICY_VERSION, Map.of(
                    "definitionVersion", row.definitionVersion(),
                    "mappingVersion", row.mappingVersion() == null
                            ? 0L : row.mappingVersion()));
            fields.put(FieldGroup.EXECUTION_POLICY, Map.of(
                    "direction", row.direction() == null ? "UNAVAILABLE" : row.direction(),
                    "compatibilityMode", row.compatibilityMode() == null
                            ? "UNAVAILABLE" : row.compatibilityMode()));
            fields.put(FieldGroup.EFFECTIVE_STATUS, Map.of(
                    "definitionState", row.definitionState(),
                    "mappingState", row.mappingState() == null
                            ? "UNAVAILABLE" : row.mappingState(),
                    "effectiveOn", row.effectiveFrom() == null
                            ? row.updatedAt().toString()
                            : row.effectiveFrom().toString()));
            return new OwnerItem(row.publicId(), row.revision(), fields,
                    itemActions(row, allowedActions));
        }).toList();
        String receipt = canonical.digest(Map.of(
                "tenantId", query.tenantId(),
                "projection", query.projection().name(),
                "asOf", query.asOf().toString(),
                "items", selected.stream().map(row ->
                        row.publicId() + ":" + row.revision()).toList()));
        return new OwnerPage(items.isEmpty() ? QueryState.EMPTY : QueryState.COMPLETE,
                true, items, receipt,
                hasMore ? selected.getLast().publicId().toString() : null);
    }

    @Override
    public void appendExecutionEventAndAudit(
            CommandSeal command,
            ExecutionRecord execution,
            String transition,
            OffsetDateTime now) {
        Sys008EventContract.Payload payload = new Sys008EventContract.Payload(
                execution.executionId(), execution.state().name(),
                Math.toIntExact(execution.sourceCount()),
                Math.toIntExact(execution.acceptedCount()),
                Math.toIntExact(execution.rejectedCount()),
                Math.toIntExact(execution.quarantinedCount()),
                execution.reconciliationDigest());
        JsonNode payloadNode = json.valueToTree(payload);
        String payloadText = stringify(payloadNode);
        String payloadDigest = IntegrationDomainPolicy.sha256(
                payloadText.getBytes(StandardCharsets.UTF_8));
        UUID eventId = UUID.nameUUIDFromBytes((command.tenantId() + ":"
                + execution.executionId() + ":" + execution.aggregateRevision())
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO sys_domain_event_outbox (
                    outbox_id, event_id, spec_version, event_source, event_type,
                    schema_version, subject, tenant_id, aggregate_type,
                    aggregate_id, aggregate_sequence, correlation_id, payload,
                    payload_sha256, status, available_at, created_at, updated_at)
                VALUES (?, ?, '1.0', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.randomUUID(), eventId, Sys008EventContract.SOURCE,
                Sys008EventContract.EVENT_TYPE,
                command.subjectPrincipalPublicId().toString(),
                command.tenantId(),
                Sys008EventContract.AGGREGATE_TYPE,
                execution.executionId().toString(), execution.aggregateRevision(),
                command.correlationId().toString(), payloadText, payloadDigest,
                now, now, now);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("controlId", "SYS-008");
        audit.put("transition", transition);
        audit.put("executionPublicId", execution.executionId());
        audit.put("executionRevision", execution.aggregateRevision());
        audit.put("state", execution.state().name());
        audit.put("correlationId", command.correlationId());
        audit.put("eventPayloadDigest", payloadDigest);
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), command.tenantId(),
                stringify(json.valueToTree(audit)), now, now, now);
    }

    @Override
    public void appendControlAudit(
            CommandSeal command,
            String aggregateType,
            UUID aggregatePublicId,
            long aggregateRevision,
            String transition,
            Object afterState,
            OffsetDateTime now) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("controlId", "SYS-008");
        audit.put("aggregateType", aggregateType);
        audit.put("aggregatePublicId", aggregatePublicId);
        audit.put("aggregateRevision", aggregateRevision);
        audit.put("transition", transition);
        audit.put("originatingAction", command.originatingAction());
        audit.put("subjectPrincipalPublicId",
                command.subjectPrincipalPublicId());
        audit.put("populationScopeDigest", command.populationScopeDigest());
        audit.put("fieldPolicyRevision", command.fieldPolicyRevision());
        audit.put("authorizationRevision", command.authorizationRevision());
        audit.put("correlationId", command.correlationId());
        audit.put("afterState", afterState);
        String payload = stringify(json.valueToTree(audit));
        UUID eventId = UUID.nameUUIDFromBytes(("SYS008:AUDIT:"
                + command.tenantId() + ":" + aggregateType + ":"
                + aggregatePublicId + ":" + aggregateRevision + ":"
                + transition + ":" + command.idempotencyKey())
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.randomUUID(), eventId, command.tenantId(), payload,
                now, now, now);
    }

    private int incrementDefinitionAggregate(
            DefinitionRecord current, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE sys_connector_definitions
                   SET aggregate_revision = aggregate_revision + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND definition_id = ?
                   AND aggregate_revision = ?
                """, now, current.tenantId(), current.definitionId(),
                current.aggregateRevision());
    }

    private int incrementDefinitionAggregate(
            long tenantId, UUID definitionId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE sys_connector_definitions
                   SET aggregate_revision = aggregate_revision + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND definition_id = ?
                """, now, tenantId, definitionId);
    }

    private DefinitionRecord definition(ResultSet rs) throws SQLException {
        return new DefinitionRecord(
                uuid(rs, "definition_id"), uuid(rs, "definition_version_id"),
                uuid(rs, "connector_public_id"), rs.getLong("tenant_id"),
                rs.getString("connector_key"),
                ConnectorType.valueOf(rs.getString("connector_type")),
                java.net.URI.create(rs.getString("endpoint_uri")),
                rs.getString("secret_ref"), rs.getString("endpoint_policy_key"),
                rs.getLong("version_number"),
                rs.getLong("active_mapping_version"),
                DefinitionState.valueOf(rs.getString("lifecycle_state")),
                rs.getString("definition_digest"),
                rs.getLong("endpoint_policy_revision"),
                rs.getLong("secret_revision"), rs.getLong("aggregate_revision"),
                rs.getLong("subject_revision"), rs.getLong("object_revision"),
                rs.getLong("author_actor_id"), rs.getString("author_scope_digest"),
                nullableLong(rs, "approved_by_actor_id"),
                uuidOrNull(rs, "approval_receipt_id"),
                rs.getString("connection_test_result_ref"),
                rs.getString("connection_test_result_digest"));
    }

    private MappingRecord mapping(ResultSet rs) throws SQLException {
        return new MappingRecord(
                uuid(rs, "mapping_version_id"), rs.getLong("tenant_id"),
                uuid(rs, "definition_id"), uuid(rs, "connector_public_id"),
                rs.getLong("version_number"),
                MappingState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("source_schema_version"),
                rs.getLong("target_schema_version"),
                rs.getString("source_schema_digest"),
                rs.getString("target_schema_digest"),
                rs.getString("mapping_ref"), rs.getString("mapping_digest"),
                CompatibilityMode.valueOf(rs.getString("compatibility_mode")),
                rs.getString("direction"),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                rs.getLong("object_revision"), rs.getLong("subject_revision"),
                rs.getLong("author_actor_id"), rs.getString("author_scope_digest"),
                nullableLong(rs, "approved_by_actor_id"),
                uuidOrNull(rs, "approval_receipt_id"),
                rs.getString("dry_run_result_ref"),
                rs.getString("dry_run_result_digest"));
    }

    private ExecutionRecord execution(ResultSet rs) throws SQLException {
        ExecutionState state = ExecutionState.valueOf(
                rs.getString("lifecycle_state"));
        return new ExecutionRecord(
                uuid(rs, "public_id"), uuid(rs, "execution_id"),
                rs.getLong("tenant_id"), uuid(rs, "definition_id"),
                uuid(rs, "connector_public_id"),
                uuid(rs, "mapping_version_id"),
                rs.getLong("definition_version_number"),
                rs.getLong("mapping_version_number"),
                rs.getString("mapping_ref"), rs.getString("mapping_digest"),
                rs.getString("direction"),
                rs.getString("source_schema_digest"),
                rs.getString("target_schema_digest"),
                java.net.URI.create(rs.getString("endpoint_uri")),
                rs.getString("endpoint_policy_key"), rs.getString("secret_ref"),
                rs.getLong("endpoint_policy_revision"),
                rs.getLong("secret_revision"), state,
                Operation.valueOf(rs.getString("operation")),
                rs.getString("payload_ref"), rs.getString("payload_digest"),
                rs.getString("provider_idempotency_key"),
                rs.getString("provider_request_digest"),
                rs.getLong("source_count"), rs.getLong("accepted_count"),
                rs.getLong("rejected_count"), rs.getLong("quarantined_count"),
                rs.getLong("selected_item_count"),
                rs.getString("selected_item_digest"),
                rs.getString("reconciliation_digest"),
                rs.getLong("retry_generation"), rs.getLong("object_revision"),
                rs.getLong("current_attempt_fence"),
                providerDispatchOccurred(rs, state),
                uuid(rs, "original_command_receipt_id"),
                rs.getLong("authorization_revision"),
                rs.getString("requested_scope_digest"),
                rs.getString("population_kind"),
                uuidOrNull(rs, "legal_entity_public_id"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getLong("requested_by_actor_id"),
                uuid(rs, "correlation_id"),
                rs.getString("owner_idempotency_key"),
                rs.getString("provider_receipt_ref"),
                rs.getString("provider_receipt_digest"),
                rs.getString("compensation_policy_ref"),
                rs.getString("compensation_receipt_digest"));
    }

    private static boolean providerDispatchOccurred(
            ResultSet rs,
            ExecutionState state) throws SQLException {
        boolean unresolved = rs.getBoolean("unresolved_external_effect");
        long acceptedCount = rs.getLong("accepted_count");
        if (state == ExecutionState.FAILED
                && rs.getBoolean("provider_failure_conclusive")
                && !unresolved
                && acceptedCount == 0
                && rs.getString("reconciliation_digest") == null) {
            return false;
        }
        boolean effectState = Set.of(
                ExecutionState.DISPATCHED,
                ExecutionState.RESULT_UNKNOWN,
                ExecutionState.ACKNOWLEDGED,
                ExecutionState.RECONCILING,
                ExecutionState.RECONCILED,
                ExecutionState.PARTIAL).contains(state);
        return effectState
                || unresolved
                || acceptedCount > 0
                || rs.getString("provider_receipt_ref") != null
                || rs.getString("provider_evidence_digest") != null;
    }

    private StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        String requestDigest = rs.getString("request_digest");
        CommandReceipt receipt = new CommandReceipt(
                uuid(rs, "public_id"), rs.getString("command_type"),
                rs.getString("originating_action"),
                uuid(rs, "subject_principal_public_id"),
                rs.getString("population_scope_digest"),
                rs.getLong("field_policy_revision"),
                rs.getString("purpose_code"), rs.getLong("authorization_revision"),
                ReceiptStatus.valueOf(rs.getString("status")),
                uuid(rs, "correlation_id"), requestDigest,
                rs.getObject("created_at", OffsetDateTime.class),
                nullableLong(rs, "aggregate_revision"), rs.getString("result_ref"));
        return new StoredReceipt(
                requestDigest,
                rs.getString("authority_population_scope_digest"),
                rs.getLong("authority_field_policy_revision"),
                rs.getLong("authority_authorization_revision"),
                rs.getString("purpose_code"),
                rs.getString("origin_route_contract_key"),
                rs.getString("origin_context_key"),
                rs.getString("origin_context_scope_key"),
                rs.getString("origin_decision_revision"),
                receipt);
    }

    private static CommandReceipt receipt(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            OffsetDateTime createdAt,
            Long aggregateRevision,
            String resultRef) {
        return new CommandReceipt(receiptId, command.commandType(),
                command.originatingAction(), command.subjectPrincipalPublicId(),
                command.populationScopeDigest(), command.fieldPolicyRevision(),
                command.purposeCode(), command.authorizationRevision(), status,
                command.correlationId(), command.requestDigest(), createdAt,
                aggregateRevision, resultRef);
    }

    private static String receiptError(ReceiptStatus status) {
        return switch (status) {
            case SUCCEEDED -> null;
            case REJECTED -> "COMMAND_REJECTED";
            case FAILED -> "COMMAND_FAILED";
            case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
            default -> throw new IllegalArgumentException(
                    "receipt status is not terminal");
        };
    }

    private static void appendOwnerFilters(
            StringBuilder sql,
            List<Object> arguments,
            Map<String, String> filters) {
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String value = filter.getValue();
            switch (filter.getKey()) {
                case "connectorMetadata" -> {
                    sql.append(" AND (connector_key || ' ' || connector_type) ILIKE ?");
                    arguments.add("%" + value + "%");
                }
                case "policyVersion" -> {
                    long version;
                    try {
                        version = Long.parseLong(value);
                    } catch (NumberFormatException invalid) {
                        throw conflict("SYS008_OWNER_FILTER_INVALID");
                    }
                    if (version < 1) {
                        throw conflict("SYS008_OWNER_FILTER_INVALID");
                    }
                    sql.append(" AND definition_version = ?");
                    arguments.add(version);
                }
                case "executionPolicy" -> {
                    sql.append(" AND (COALESCE(direction, '') || ' ' || "
                            + "COALESCE(compatibility_mode, '')) ILIKE ?");
                    arguments.add("%" + value + "%");
                }
                case "effectiveStatus" -> {
                    sql.append(" AND (definition_state = ? OR mapping_state = ?)");
                    arguments.add(value.toUpperCase(java.util.Locale.ROOT));
                    arguments.add(value.toUpperCase(java.util.Locale.ROOT));
                }
                case "effectiveOn" -> {
                    try {
                        arguments.add(java.time.LocalDate.parse(value));
                    } catch (java.time.format.DateTimeParseException invalid) {
                        throw conflict("SYS008_OWNER_FILTER_INVALID");
                    }
                    sql.append(" AND effective_from::date = ?");
                }
                default -> throw conflict("SYS008_OWNER_FILTER_INVALID");
            }
        }
    }

    private static Set<String> itemActions(
            OwnerRow row, List<String> allowed) {
        Set<String> candidates = new java.util.LinkedHashSet<>();
        switch (row.definitionState()) {
            case "DRAFT" -> candidates.add(IntegrationService.VALIDATE_DEFINITION);
            case "VALIDATED" -> candidates.add(IntegrationService.TEST_CONNECTOR);
            case "TESTED" -> candidates.add(IntegrationService.SUBMIT_DEFINITION);
            case "PENDING_APPROVAL" -> candidates.add(IntegrationService.APPROVE_DEFINITION);
            case "APPROVED" -> candidates.add(IntegrationService.ACTIVATE_DEFINITION);
            case "ACTIVE" -> {
                candidates.add(IntegrationService.CREATE_MAPPING);
                if ("ACTIVE".equals(row.mappingState())) {
                    candidates.add(IntegrationService.CREATE_EXECUTION);
                }
            }
            default -> { }
        }
        if (row.mappingVersion() != null) {
            switch (String.valueOf(row.mappingState())) {
                case "DRAFT" -> candidates.add(IntegrationService.VALIDATE_MAPPING);
                case "VALIDATED" -> {
                    candidates.add(IntegrationService.DRY_RUN_MAPPING);
                    candidates.add(IntegrationService.SUBMIT_MAPPING);
                }
                case "PENDING_APPROVAL" -> candidates.add(IntegrationService.APPROVE_MAPPING);
                case "APPROVED" -> candidates.add(IntegrationService.ACTIVATE_MAPPING);
                default -> { }
            }
        }
        candidates.retainAll(Set.copyOf(allowed));
        return Set.copyOf(candidates);
    }

    private String stringify(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("SYS008_JSON_SERIALIZATION_FAILED", invalid);
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static UUID uuidOrNull(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }

    private record StoredReceipt(
            String requestDigest,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String purposeCode,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            CommandReceipt receipt) {

        boolean matchesCurrentAuthority(CommandSeal command) {
            return java.util.Objects.equals(
                            populationScopeDigest, command.populationScopeDigest())
                    && fieldPolicyRevision == command.fieldPolicyRevision()
                    && authorizationRevision == command.authorizationRevision()
                    && java.util.Objects.equals(purposeCode, command.purposeCode())
                    && java.util.Objects.equals(
                            routeContractKey, command.routeContractKey())
                    && java.util.Objects.equals(contextKey, command.contextKey())
                    && java.util.Objects.equals(
                            contextScopeKey, command.contextScopeKey())
                    && java.util.Objects.equals(
                            decisionRevision, command.decisionRevision());
        }
    }

    private record RetryItem(
            UUID executionItemId,
            int attemptNumber,
            long attemptFence,
            String sourceDigest,
            UUID mappingVersionId,
            long mappingVersionNumber) {}

    private record InitialItem(
            UUID itemId,
            String sourceDigest,
            String providerIdempotencyKey,
            long attemptFence) {}

    private record OwnerRow(
            UUID publicId,
            String connectorKey,
            long revision,
            OffsetDateTime updatedAt,
            String connectorType,
            long definitionVersion,
            String definitionState,
            Long mappingVersion,
            String mappingState,
            String direction,
            String compatibilityMode,
            OffsetDateTime effectiveFrom) {}
}
