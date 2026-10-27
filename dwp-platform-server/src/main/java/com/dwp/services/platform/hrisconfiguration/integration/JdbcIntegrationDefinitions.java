package com.dwp.services.platform.hrisconfiguration.integration;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration definitions operations shared by the owner facade. */
final class JdbcIntegrationDefinitions {

    private final JdbcIntegrationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcIntegrationDefinitions(
            JdbcIntegrationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


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
                    """, (rs, row) -> owner.definition(rs), tenantId, connectorId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_CONNECTOR_NOT_FOUND");
        }
    }

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
                    """, (rs, row) -> owner.mapping(rs), tenantId, connectorId, version);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_MAPPING_NOT_FOUND");
        }
    }

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

    int incrementDefinitionAggregate(
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

    int incrementDefinitionAggregate(
            long tenantId, UUID definitionId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE sys_connector_definitions
                   SET aggregate_revision = aggregate_revision + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND definition_id = ?
                """, now, tenantId, definitionId);
    }
}
