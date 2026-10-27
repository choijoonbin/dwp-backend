package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation definitions operations shared by the owner facade. */
final class JdbcAutomationDefinitions {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcAutomationDefinitions(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


    public DefinitionRecord lockDefinition(long tenantId, UUID definitionPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT definition_id, public_id, tenant_id, automation_key,
                           owner_domain, lifecycle_state, active_version_number,
                           object_revision, author_actor_ref
                      FROM sys_automation_definitions
                     WHERE tenant_id = ? AND public_id = ? FOR UPDATE
                    """, DEFINITION_MAPPER, tenantId, definitionPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_DEFINITION_NOT_FOUND");
        }
    }

    public VersionRecord lockVersion(
            long tenantId, UUID definitionPublicId, long versionNumber) {
        try {
            return jdbc.queryForObject("""
                    SELECT v.*, d.public_id AS definition_public_id
                      FROM sys_automation_versions v
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = v.tenant_id
                       AND d.definition_id = v.definition_id
                     WHERE v.tenant_id = ? AND d.public_id = ?
                       AND v.version_number = ? FOR UPDATE OF v
                    """, owner::version, tenantId, definitionPublicId,
                    versionNumber);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_VERSION_NOT_FOUND");
        }
    }

    public ScheduleRecord lockSchedule(long tenantId, UUID schedulePublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT s.*, d.public_id AS definition_public_id
                      FROM sys_automation_schedules s
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = s.tenant_id
                       AND d.definition_id = s.definition_id
                     WHERE s.tenant_id = ? AND s.public_id = ? FOR UPDATE OF s
                    """, SCHEDULE_MAPPER, tenantId, schedulePublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_SCHEDULE_NOT_FOUND");
        }
    }

    public DefinitionRecord insertDefinition(
            long tenantId,
            UUID author,
            CreateDefinitionCommand command,
            OffsetDateTime now) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO sys_automation_definitions (
                        tenant_id, automation_key, display_name, description,
                        owner_domain, author_actor_ref, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING definition_id, public_id, tenant_id, automation_key,
                              owner_domain, lifecycle_state, active_version_number,
                              object_revision, author_actor_ref
                    """, DEFINITION_MAPPER, tenantId, command.automationKey(),
                    command.displayName(), command.description(),
                    command.ownerDomain().name(), author, now, now);
        } catch (DuplicateKeyException duplicate) {
            throw conflict("AUTOMATION_KEY_ALREADY_EXISTS");
        }
    }

    public VersionRecord insertVersion(
            DefinitionRecord definition,
            UUID author,
            CreateVersionCommand command,
            VerifiedArtifact artifact,
            OffsetDateTime now) {
        long versionNumber = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                  FROM sys_automation_versions
                 WHERE tenant_id = ? AND definition_id = ?
                """, Long.class, definition.tenantId(), definition.definitionId());
        SignedManifest signed = artifact.signedManifest();
        AutomationHandlerManifestV1 manifest = signed.manifest();
        return jdbc.queryForObject("""
                INSERT INTO sys_automation_versions (
                    tenant_id, definition_id, version_number, definition_payload,
                    definition_digest, execution_policy, execution_policy_digest,
                    manual_concurrency_key_template, compensation_policy_ref,
                    compensation_policy_digest, manifest_retry_mode,
                    manifest_max_attempts, handler_owner_service, handler_key,
                    handler_contract_version, allowed_scope_types,
                    handler_manifest_state, handler_manifest_revision,
                    handler_manifest_digest, handler_signature_key_id,
                    handler_signature, handler_signature_digest, input_schema_ref,
                    input_schema_digest, receipt_schema_ref, receipt_schema_digest,
                    author_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *, ?::uuid AS definition_public_id
                """, owner::version, definition.tenantId(), definition.definitionId(),
                versionNumber, owner.stringify(command.definitionPayload()),
                command.definitionDigest(), owner.stringify(command.executionPolicy()),
                command.executionPolicyDigest(),
                command.manualConcurrencyKeyTemplate(),
                command.compensationPolicyRef(), command.compensationPolicyDigest(),
                command.retryMode().name(), command.maxAttempts(),
                manifest.ownerService(), manifest.handlerKey(),
                manifest.contractVersion(), command.allowedScopeTypes().toArray(String[]::new),
                manifest.state(), signed.manifestRevision(),
                command.handlerManifestDigest(), signed.signatureKeyId(),
                command.signature(), command.signatureDigest(),
                manifest.inputSchemaRef(), signed.inputSchemaDigest(),
                manifest.receiptSchemaRef(), signed.receiptSchemaDigest(),
                author, now, now, definition.publicId());
    }

    public VersionRecord transitionVersion(
            VersionRecord version,
            VersionState target,
            UUID actor,
            UUID receiptId,
            String evidenceDigest,
            String stepUpDigest,
            VerifiedArtifact currentArtifact,
            OffsetDateTime now) {
        String assignments = switch (target) {
            case VALIDATED -> """
                    lifecycle_state = 'VALIDATED', validation_state = 'VALID',
                    validated_by_actor_ref = ?, validated_at = ?,
                    validation_receipt_id = ?, validation_evidence_digest = ?,
                    compatibility_baseline_version = ?,
                    compatibility_baseline_digest = ?,
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            case PENDING_APPROVAL -> """
                    lifecycle_state = 'PENDING_APPROVAL',
                    submitted_by_actor_ref = ?, submitted_at = ?,
                    submission_receipt_id = ?, submission_evidence_digest = ?,
                    submitted_subject_revision = subject_revision,
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            case APPROVED -> """
                    lifecycle_state = 'APPROVED', approved_by_actor_ref = ?,
                    approved_at = ?, approval_receipt_id = ?,
                    approval_evidence_digest = ?, approval_step_up_evidence_digest = ?,
                    approved_subject_revision = subject_revision,
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            case ACTIVE -> """
                    lifecycle_state = 'ACTIVE', activated_by_actor_ref = ?,
                    activated_at = ?, activation_receipt_id = ?,
                    activation_evidence_digest = ?,
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            default -> throw conflict("AUTOMATION_VERSION_TRANSITION_REJECTED");
        };
        Object[] values = switch (target) {
            case VALIDATED -> new Object[]{actor, now, receiptId,
                    currentArtifact.verificationEvidenceDigest(),
                    currentArtifact.compatibilityBaselineVersion(),
                    currentArtifact.compatibilityBaselineDigest(), now};
            case PENDING_APPROVAL -> new Object[]{actor, now, receiptId,
                    evidenceDigest, now};
            case APPROVED -> new Object[]{actor, now, receiptId,
                    evidenceDigest, stepUpDigest, now};
            case ACTIVE -> new Object[]{actor, now, receiptId,
                    currentArtifact.verificationEvidenceDigest(), now};
            default -> new Object[0];
        };
        if (target == VersionState.ACTIVE) {
            Integer activeSchedules = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_automation_schedules
                     WHERE tenant_id = ? AND definition_id = ?
                       AND lifecycle_state = 'ACTIVE'
                       AND version_id <> ?
                    """, Integer.class, version.tenantId(), version.definitionId(),
                    version.versionId());
            if (activeSchedules != null && activeSchedules > 0) {
                throw conflict("ACTIVE_SCHEDULE_PINS_PREVIOUS_VERSION");
            }
            jdbc.update("""
                    UPDATE sys_automation_versions
                       SET lifecycle_state = 'RETIRED', object_revision = object_revision + 1,
                           updated_at = ?
                     WHERE tenant_id = ? AND definition_id = ?
                       AND lifecycle_state = 'ACTIVE' AND version_id <> ?
                    """, now, version.tenantId(), version.definitionId(),
                    version.versionId());
        }
        List<Object> parameters = new ArrayList<>(Arrays.asList(values));
        parameters.add(version.tenantId());
        parameters.add(version.versionId());
        parameters.add(version.state().name());
        parameters.add(version.objectRevision());
        int updated = jdbc.update("UPDATE sys_automation_versions SET " + assignments
                        + " WHERE tenant_id = ? AND version_id = ?"
                        + " AND lifecycle_state = ? AND object_revision = ?",
                parameters.toArray());
        if (updated != 1) throw conflict("AUTOMATION_VERSION_CAS_CONFLICT");
        if (target == VersionState.ACTIVE) {
            jdbc.update("""
                    UPDATE sys_automation_definitions
                       SET lifecycle_state = 'ACTIVE', active_version_number = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND definition_id = ?
                    """, version.versionNumber(), now, version.tenantId(),
                    version.definitionId());
        }
        return lockVersion(
                version.tenantId(), version.definitionPublicId(), version.versionNumber());
    }

    public ScheduleRecord insertSchedule(
            DefinitionRecord definition,
            VersionRecord version,
            UUID author,
            CreateScheduleCommand command,
            OffsetDateTime now) {
        if (!List.of(VersionState.APPROVED, VersionState.ACTIVE)
                .contains(version.state())) {
            throw conflict("AUTOMATION_SCHEDULE_VERSION_NOT_APPROVED");
        }
        return jdbc.queryForObject("""
                INSERT INTO sys_automation_schedules (
                    tenant_id, definition_id, version_id, pinned_version_number,
                    schedule_expression, timezone, timezone_database_revision,
                    input_template_ref, input_template_digest,
                    concurrency_key_template, schedule_policy_digest,
                    author_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *, ?::uuid AS definition_public_id
                """, SCHEDULE_MAPPER, definition.tenantId(), definition.definitionId(),
                version.versionId(), version.versionNumber(), command.expression(),
                command.timezone(), command.timezoneDatabaseRevision(),
                command.inputTemplateRef(), command.inputTemplateDigest(),
                command.concurrencyKeyTemplate(), command.schedulePolicyDigest(),
                author, now, now, definition.publicId());
    }

    public ScheduleRecord transitionSchedule(
            ScheduleRecord schedule,
            ScheduleState target,
            UUID actor,
            UUID receiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now) {
        String assignments = switch (target) {
            case PENDING_APPROVAL -> """
                    lifecycle_state = 'PENDING_APPROVAL', submitted_by_actor_ref = ?,
                    submitted_at = ?, submitted_subject_revision = subject_revision,
                    submission_receipt_id = ?, submission_evidence_digest = ?,
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            case APPROVED -> """
                    lifecycle_state = 'APPROVED', approved_by_actor_ref = ?,
                    approved_at = ?, approved_subject_revision = subject_revision,
                    approval_receipt_id = ?, approval_evidence_digest = ?,
                    approval_step_up_evidence_digest = ?,
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            case ACTIVE -> """
                    lifecycle_state = 'ACTIVE', activated_by_actor_ref = ?,
                    activated_at = ?, activation_receipt_id = ?,
                    activation_evidence_digest = ?,
                    next_fire_at = ?,
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            case SUSPENDED -> """
                    lifecycle_state = 'SUSPENDED',
                    object_revision = object_revision + 1, updated_at = ?
                    """;
            default -> throw conflict("AUTOMATION_SCHEDULE_TRANSITION_REJECTED");
        };
        if (target == ScheduleState.ACTIVE) {
            String versionState = jdbc.queryForObject("""
                    SELECT lifecycle_state FROM sys_automation_versions
                     WHERE tenant_id = ? AND version_id = ? FOR UPDATE
                    """, String.class, schedule.tenantId(), schedule.versionId());
            if (!"ACTIVE".equals(versionState)) {
                throw conflict("AUTOMATION_SCHEDULE_PINNED_VERSION_NOT_ACTIVE");
            }
        }
        List<Object> values = new ArrayList<>();
        switch (target) {
            case PENDING_APPROVAL -> values.addAll(List.of(
                    actor, now, receiptId, evidenceDigest, now));
            case APPROVED -> values.addAll(List.of(
                    actor, now, receiptId, evidenceDigest, stepUpDigest, now));
            case ACTIVE -> values.addAll(List.of(
                    actor, now, receiptId, evidenceDigest,
                    AutomationDomainPolicy.nextScheduleFire(
                            schedule.expression(), schedule.timezone(),
                            schedule.timezoneDatabaseRevision(), now), now));
            case SUSPENDED -> values.add(now);
            default -> { }
        }
        values.add(schedule.tenantId());
        values.add(schedule.scheduleId());
        values.add(schedule.state().name());
        values.add(schedule.objectRevision());
        int updated = jdbc.update("UPDATE sys_automation_schedules SET " + assignments
                        + " WHERE tenant_id = ? AND schedule_id = ?"
                        + " AND lifecycle_state = ? AND object_revision = ?",
                values.toArray());
        if (updated != 1) throw conflict("AUTOMATION_SCHEDULE_CAS_CONFLICT");
        return lockSchedule(schedule.tenantId(), schedule.publicId());
    }
}
