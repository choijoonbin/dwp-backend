package com.dwp.services.platform.hrisconfiguration.automation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation schedules operations shared by the owner facade. */
final class JdbcAutomationSchedules {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcAutomationSchedules(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


    public ScheduleExecution lockScheduleForFire(
            long tenantId, UUID schedulePublicId, OffsetDateTime databaseNow) {
        try {
            return jdbc.queryForObject("""
                    SELECT s.schedule_id, s.public_id AS schedule_public_id,
                           s.tenant_id, s.definition_id,
                           d.public_id AS definition_public_id, s.version_id,
                           s.pinned_version_number,
                           s.lifecycle_state AS schedule_state,
                           v.lifecycle_state AS version_state,
                           s.schedule_expression, s.timezone,
                           s.timezone_database_revision, s.input_template_ref,
                           s.input_template_digest, s.concurrency_key_template,
                           s.schedule_policy_digest, s.approved_subject_revision,
                           s.object_revision, s.fire_cursor_revision, s.next_fire_at,
                           v.definition_digest, v.execution_policy_digest,
                           v.compatibility_baseline_digest,
                           v.handler_owner_service, v.handler_key,
                           v.handler_contract_version, v.handler_signature_digest,
                           v.allowed_scope_types, v.manifest_retry_mode,
                           v.manifest_max_attempts, v.handler_manifest_state,
                           v.handler_signature_key_id, v.input_schema_ref,
                           v.input_schema_digest, v.receipt_schema_ref,
                           v.receipt_schema_digest, v.compensation_policy_digest,
                           v.handler_manifest_revision
                      FROM sys_automation_schedules s
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = s.tenant_id
                       AND d.definition_id = s.definition_id
                      JOIN sys_automation_versions v
                        ON v.tenant_id = s.tenant_id
                       AND v.definition_id = s.definition_id
                       AND v.version_id = s.version_id
                       AND v.version_number = s.pinned_version_number
                     WHERE s.tenant_id = ? AND s.public_id = ?
                     FOR UPDATE OF s, v
                    """, (rs, row) -> new ScheduleExecution(
                    rs.getLong("tenant_id"), uuid(rs, "schedule_id"),
                    uuid(rs, "schedule_public_id"), uuid(rs, "definition_id"),
                    uuid(rs, "definition_public_id"), uuid(rs, "version_id"),
                    rs.getLong("pinned_version_number"),
                    rs.getString("schedule_state"), rs.getString("version_state"),
                    rs.getString("schedule_expression"), rs.getString("timezone"),
                    rs.getString("timezone_database_revision"),
                    rs.getString("input_template_ref"),
                    rs.getString("input_template_digest"),
                    rs.getString("concurrency_key_template"),
                    rs.getString("schedule_policy_digest"),
                    rs.getLong("approved_subject_revision"),
                    rs.getLong("object_revision"),
                    rs.getLong("fire_cursor_revision"),
                    rs.getObject("next_fire_at", OffsetDateTime.class),
                    owner.signedManifest(rs), rs.getString("definition_digest"),
                    rs.getString("execution_policy_digest"),
                    rs.getString("compatibility_baseline_digest"),
                    rs.getString("manifest_retry_mode"),
                    rs.getInt("manifest_max_attempts")), tenantId, schedulePublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_SCHEDULE_NOT_FOUND");
        }
    }

    public ScheduledRun fireSchedule(
            ScheduleExecution schedule,
            CommandSeal command,
            UUID receiptId,
            String fireKey,
            OffsetDateTime scheduledInstantUtc,
            OffsetDateTime nextFireAt,
            String inputReference,
            String inputDigest,
            String scopeType,
            UUID scopeId,
            long expectedItemCount,
            String concurrencyKeyDigest,
            UUID technicalActor,
            OffsetDateTime now) {
        int cursor = jdbc.update("""
                UPDATE sys_automation_schedules
                   SET fire_cursor_revision = fire_cursor_revision + 1,
                       last_scheduled_instant_utc = ?, next_fire_at = ?,
                       updated_at = ?
                 WHERE tenant_id = ? AND schedule_id = ?
                   AND lifecycle_state = 'ACTIVE' AND object_revision = ?
                   AND approved_subject_revision = ?
                   AND schedule_policy_digest = ? AND fire_cursor_revision = ?
                   AND next_fire_at = ?
                """, scheduledInstantUtc, nextFireAt, now, schedule.tenantId(),
                schedule.scheduleId(), schedule.objectRevision(),
                schedule.approvedSubjectRevision(), schedule.schedulePolicyDigest(),
                schedule.fireCursorRevision(), schedule.nextFireAt());
        if (cursor != 1) throw conflict("AUTOMATION_SCHEDULE_FIRE_CAS_CONFLICT");
        UUID runId = UUID.randomUUID();
        UUID publicId = UUID.randomUUID();
        RunRecord run;
        try {
            run = jdbc.queryForObject("""
                    INSERT INTO sys_automation_runs (
                        run_id, public_id, tenant_id, definition_id, version_id,
                        version_number, schedule_id, scheduled_instant_utc,
                        schedule_fire_key, scope_type, scope_id, requested_at,
                        requested_by_actor_ref, idempotency_key, correlation_id,
                        input_ref, input_digest, expected_item_count,
                        concurrency_key_digest, retry_mode, max_attempts,
                        command_receipt_id, created_by_actor_ref, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?)
                    RETURNING *, ?::uuid AS definition_public_id
                    """, RUN_MAPPER, runId, publicId, schedule.tenantId(),
                    schedule.definitionId(), schedule.versionId(),
                    schedule.pinnedVersionNumber(), schedule.scheduleId(),
                    scheduledInstantUtc, fireKey, scopeType, scopeId, now,
                    "scheduler:" + technicalActor, command.idempotencyKey(),
                    command.correlationId(), inputReference, inputDigest,
                    expectedItemCount, concurrencyKeyDigest, schedule.retryMode(),
                    schedule.maxAttempts(), receiptId, technicalActor, now, now,
                    schedule.definitionPublicId());
        } catch (DuplicateKeyException duplicate) {
            throw conflict("AUTOMATION_SCHEDULE_FIRE_OR_CONCURRENCY_CONFLICT");
        }
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    tenant_id, run_id, attempt_number, selection_mode,
                    selected_item_count, selected_item_digest, created_at)
                VALUES (?, ?, 1, 'ALL_ITEMS', 0, ?, ?)
                """, schedule.tenantId(), runId, emptySelectionDigest(publicId), now);
        return new ScheduledRun(run, schedule.fireCursorRevision() + 1);
    }

    public void skipMissedFire(
            ScheduleExecution schedule,
            String fireKey,
            OffsetDateTime missedInstantUtc,
            OffsetDateTime nextFireAt,
            UUID correlationId,
            OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE sys_automation_schedules
                   SET fire_cursor_revision = fire_cursor_revision + 1,
                       last_scheduled_instant_utc = ?, next_fire_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND schedule_id = ?
                   AND lifecycle_state = 'ACTIVE' AND object_revision = ?
                   AND approved_subject_revision = ?
                   AND schedule_policy_digest = ? AND fire_cursor_revision = ?
                   AND next_fire_at = ?
                """, missedInstantUtc, nextFireAt, now, schedule.tenantId(),
                schedule.scheduleId(), schedule.objectRevision(),
                schedule.approvedSubjectRevision(), schedule.schedulePolicyDigest(),
                schedule.fireCursorRevision(), schedule.nextFireAt());
        if (updated != 1) throw conflict("AUTOMATION_SCHEDULE_FIRE_CAS_CONFLICT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("controlId", "SYS-006");
        payload.put("decision", "SKIP_MISSED");
        payload.put("schedulePublicId", schedule.schedulePublicId());
        payload.put("scheduledInstantUtc", missedInstantUtc);
        payload.put("nextFireAt", nextFireAt);
        payload.put("scheduleFireKey", fireKey);
        payload.put("correlationId", correlationId);
        UUID eventId = UUID.nameUUIDFromBytes(("SYS006:SKIP_MISSED:" + fireKey)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.randomUUID(), eventId, schedule.tenantId(),
                owner.stringify(payload), now, now, now);
    }
}
