package com.dwp.services.platform.hrisconfiguration.automation;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation catalog operations shared by the owner facade. */
final class JdbcAutomationCatalog {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcAutomationCatalog(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


    public CatalogPage catalog(
            long tenantId,
            OffsetDateTime asOf,
            int limit,
            java.util.Set<String> resourceTypes,
            String filter,
            String afterSortTuple) {
        java.util.Set<String> selected = resourceTypes == null || resourceTypes.isEmpty()
                ? java.util.Set.of("AUTOMATION_DEFINITION", "AUTOMATION_VERSION",
                "AUTOMATION_SCHEDULE") : resourceTypes;
        String like = filter == null ? "%" : "%" + filter.replace("%", "\\%")
                .replace("_", "\\_") + "%";
        List<CatalogRow> rows = new ArrayList<>();
        if (selected.contains("AUTOMATION_DEFINITION")) rows.addAll(jdbc.query("""
                SELECT public_id, automation_key, owner_domain, lifecycle_state,
                       active_version_number, object_revision
                  FROM sys_automation_definitions
                 WHERE tenant_id = ? AND updated_at <= ?
                   AND (automation_key ILIKE ? ESCAPE '\\'
                        OR display_name ILIKE ? ESCAPE '\\')
                   AND (('D|' || automation_key || '|' || public_id::text)
                        COLLATE "C") > (COALESCE(?, '') COLLATE "C")
                 ORDER BY automation_key, public_id LIMIT ?
                """, (rs, row) -> new CatalogRow(
                "D|" + rs.getString("automation_key") + "|" + uuid(rs, "public_id"),
                new DefinitionItem("AUTOMATION_DEFINITION", uuid(rs, "public_id"),
                rs.getString("automation_key"),
                OwnerDomain.valueOf(rs.getString("owner_domain")),
                rs.getString("lifecycle_state"),
                nullableLong(rs, "active_version_number"),
                rs.getLong("object_revision"), List.of())), tenantId, asOf, like,
                like, afterSortTuple, limit + 1));
        if (selected.contains("AUTOMATION_VERSION")) rows.addAll(jdbc.query("""
                    SELECT d.public_id AS definition_public_id, v.version_number,
                           v.lifecycle_state, v.handler_key,
                           v.handler_contract_version, v.object_revision
                      FROM sys_automation_versions v
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = v.tenant_id
                       AND d.definition_id = v.definition_id
                     WHERE v.tenant_id = ? AND v.updated_at <= ?
                       AND v.handler_key ILIKE ? ESCAPE '\\'
                       AND (('V|' || d.public_id::text || '|'
                            || lpad(v.version_number::text, 16, '0')) COLLATE "C")
                            > (COALESCE(?, '') COLLATE "C")
                     ORDER BY d.public_id, v.version_number LIMIT ?
                    """, (rs, row) -> new CatalogRow(
                    "V|" + uuid(rs, "definition_public_id") + "|"
                            + String.format("%016d", rs.getLong("version_number")),
                    new VersionItem("AUTOMATION_VERSION",
                    uuid(rs, "definition_public_id"),
                    rs.getLong("version_number"),
                    VersionState.valueOf(rs.getString("lifecycle_state")),
                    rs.getString("handler_key"),
                    rs.getString("handler_contract_version"),
                    rs.getLong("object_revision"), List.of())), tenantId, asOf,
                    like, afterSortTuple, limit + 1));
        if (selected.contains("AUTOMATION_SCHEDULE")) rows.addAll(jdbc.query("""
                    SELECT s.public_id AS schedule_public_id,
                           d.public_id AS definition_public_id,
                           s.pinned_version_number, s.lifecycle_state,
                           s.timezone, s.next_fire_at, s.object_revision
                      FROM sys_automation_schedules s
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = s.tenant_id
                       AND d.definition_id = s.definition_id
                     WHERE s.tenant_id = ? AND s.updated_at <= ?
                       AND (s.timezone ILIKE ? ESCAPE '\\'
                            OR s.schedule_expression ILIKE ? ESCAPE '\\')
                       AND (('S|' || d.public_id::text || '|'
                            || s.public_id::text) COLLATE "C")
                            > (COALESCE(?, '') COLLATE "C")
                     ORDER BY d.public_id, s.public_id LIMIT ?
                    """, (rs, row) -> new CatalogRow(
                    "S|" + uuid(rs, "definition_public_id") + "|"
                            + uuid(rs, "schedule_public_id"),
                    new ScheduleItem("AUTOMATION_SCHEDULE",
                    uuid(rs, "schedule_public_id"),
                    uuid(rs, "definition_public_id"),
                    rs.getLong("pinned_version_number"),
                    ScheduleState.valueOf(rs.getString("lifecycle_state")),
                    rs.getString("timezone"),
                    rs.getObject("next_fire_at", OffsetDateTime.class),
                    rs.getLong("object_revision"), List.of())), tenantId, asOf,
                    like, like, afterSortTuple, limit + 1));
        rows.sort(Comparator.comparing(CatalogRow::sortTuple));
        List<CatalogRow> page = rows.stream()
                .filter(row -> afterSortTuple == null
                        || row.sortTuple().compareTo(afterSortTuple) > 0)
                .limit(limit + 1L).toList();
        String next = page.size() > limit ? page.get(limit - 1).sortTuple() : null;
        return new CatalogPage(page.stream().limit(limit).map(CatalogRow::item).toList(),
                next);
    }

    public Optional<VersionRecord> readVersion(
            long tenantId, UUID definitionPublicId, long versionNumber) {
        return jdbc.query("""
                SELECT v.*, d.public_id AS definition_public_id
                  FROM sys_automation_versions v
                  JOIN sys_automation_definitions d
                    ON d.tenant_id = v.tenant_id
                   AND d.definition_id = v.definition_id
                 WHERE v.tenant_id = ? AND d.public_id = ?
                   AND v.version_number = ?
                """, owner::version, tenantId, definitionPublicId, versionNumber)
                .stream().findFirst();
    }

    public Optional<VersionRecord> findEligibleVersion(
            long tenantId, UUID definitionPublicId, boolean activeOnly) {
        String states = activeOnly
                ? "v.lifecycle_state = 'ACTIVE'"
                : "v.lifecycle_state IN ('APPROVED', 'ACTIVE')";
        String sql = """
                SELECT v.*, d.public_id AS definition_public_id
                  FROM sys_automation_versions v
                  JOIN sys_automation_definitions d
                    ON d.tenant_id = v.tenant_id
                   AND d.definition_id = v.definition_id
                 WHERE v.tenant_id = ? AND d.public_id = ? AND %s
                 ORDER BY CASE WHEN v.lifecycle_state = 'ACTIVE' THEN 0 ELSE 1 END,
                          v.version_number DESC
                 LIMIT 1
                """.formatted(states);
        return jdbc.query(sql, owner::version, tenantId, definitionPublicId)
                .stream().findFirst();
    }

    public Set<String> eligibleCatalogActions(
            long tenantId, UUID subjectPrincipalPublicId, CatalogItem item) {
        LinkedHashSet<String> eligible = new LinkedHashSet<>();
        if (item instanceof DefinitionItem definition) {
            UUID publicId = (UUID) definition.definitionPublicId();
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT lifecycle_state, active_version_number,
                           EXISTS (
                               SELECT 1 FROM sys_automation_versions v
                                WHERE v.tenant_id = d.tenant_id
                                  AND v.definition_id = d.definition_id
                                  AND v.lifecycle_state IN ('APPROVED', 'ACTIVE'))
                               AS has_schedulable_version,
                           EXISTS (
                               SELECT 1 FROM sys_automation_versions v
                                WHERE v.tenant_id = d.tenant_id
                                  AND v.definition_id = d.definition_id
                                  AND v.lifecycle_state = 'ACTIVE'
                                  AND v.version_number = d.active_version_number)
                               AS has_executable_version
                      FROM sys_automation_definitions d
                     WHERE tenant_id = ? AND public_id = ? AND object_revision = ?
                    """, tenantId, publicId,
                    ((Number) definition.objectRevision()).longValue());
            if (rows.size() != 1) return Set.of();
            Map<String, Object> row = rows.getFirst();
            String state = (String) row.get("lifecycle_state");
            if (List.of("DRAFT", "ACTIVE").contains(state)) {
                eligible.add(AutomationService.CREATE_VERSION);
            }
            if (Boolean.TRUE.equals(row.get("has_schedulable_version"))) {
                eligible.add(AutomationService.CREATE_SCHEDULE);
            }
            if ("ACTIVE".equals(state)
                    && Boolean.TRUE.equals(row.get("has_executable_version"))) {
                eligible.add(AutomationService.QUEUE_RUN);
            }
            return Set.copyOf(eligible);
        }
        if (item instanceof VersionItem versionItem) {
            UUID definitionId = (UUID) versionItem.definitionPublicId();
            long versionNumber = ((Number) versionItem.versionNumber()).longValue();
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT v.lifecycle_state, v.validation_state,
                           v.subject_revision, v.author_actor_ref,
                           v.validation_receipt_id, v.validation_evidence_digest,
                           v.compatibility_baseline_version,
                           v.compatibility_baseline_digest,
                           v.submitted_subject_revision, v.submission_receipt_id,
                           v.submission_evidence_digest,
                           v.approved_subject_revision, v.approval_receipt_id,
                           v.approval_evidence_digest,
                           v.approval_step_up_evidence_digest,
                           v.approved_by_actor_ref
                      FROM sys_automation_versions v
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = v.tenant_id
                       AND d.definition_id = v.definition_id
                     WHERE v.tenant_id = ? AND d.public_id = ?
                       AND v.version_number = ? AND v.object_revision = ?
                    """, tenantId, definitionId, versionNumber,
                    ((Number) versionItem.objectRevision()).longValue());
            if (rows.size() != 1) return Set.of();
            Map<String, Object> row = rows.getFirst();
            String state = (String) row.get("lifecycle_state");
            long subjectRevision = ((Number) row.get("subject_revision")).longValue();
            boolean validationCurrent = "VALID".equals(row.get("validation_state"))
                    && complete(row, "validation_receipt_id",
                    "validation_evidence_digest", "compatibility_baseline_version",
                    "compatibility_baseline_digest");
            boolean submissionCurrent = validationCurrent
                    && revision(row.get("submitted_subject_revision"), subjectRevision)
                    && complete(row, "submission_receipt_id",
                    "submission_evidence_digest");
            boolean approvalCurrent = submissionCurrent
                    && revision(row.get("approved_subject_revision"), subjectRevision)
                    && complete(row, "approval_receipt_id",
                    "approval_evidence_digest", "approval_step_up_evidence_digest",
                    "approved_by_actor_ref")
                    && !row.get("author_actor_ref").equals(
                    row.get("approved_by_actor_ref"));
            if ("DRAFT".equals(state)) {
                eligible.add(AutomationService.VALIDATE_VERSION);
            } else if ("VALIDATED".equals(state) && validationCurrent) {
                eligible.add(AutomationService.SUBMIT_VERSION);
            } else if ("PENDING_APPROVAL".equals(state) && submissionCurrent
                    && !row.get("author_actor_ref").equals(subjectPrincipalPublicId)) {
                eligible.add(AutomationService.APPROVE_VERSION);
            } else if ("APPROVED".equals(state) && approvalCurrent) {
                eligible.add(AutomationService.ACTIVATE_VERSION);
            }
            return Set.copyOf(eligible);
        }
        ScheduleItem schedule = (ScheduleItem) item;
        UUID scheduleId = (UUID) schedule.schedulePublicId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.lifecycle_state, s.subject_revision, s.author_actor_ref,
                       s.submitted_subject_revision, s.submission_receipt_id,
                       s.submission_evidence_digest,
                       s.approved_subject_revision, s.approval_receipt_id,
                       s.approval_evidence_digest,
                       s.approval_step_up_evidence_digest,
                       s.approved_by_actor_ref, v.lifecycle_state AS version_state
                  FROM sys_automation_schedules s
                  JOIN sys_automation_versions v
                    ON v.tenant_id = s.tenant_id
                   AND v.definition_id = s.definition_id
                   AND v.version_id = s.version_id
                   AND v.version_number = s.pinned_version_number
                 WHERE s.tenant_id = ? AND s.public_id = ?
                   AND s.object_revision = ?
                """, tenantId, scheduleId,
                ((Number) schedule.objectRevision()).longValue());
        if (rows.size() != 1) return Set.of();
        Map<String, Object> row = rows.getFirst();
        String state = (String) row.get("lifecycle_state");
        String versionState = (String) row.get("version_state");
        long subjectRevision = ((Number) row.get("subject_revision")).longValue();
        boolean pinnedEligible = List.of("APPROVED", "ACTIVE").contains(versionState);
        boolean submissionCurrent = revision(
                row.get("submitted_subject_revision"), subjectRevision)
                && complete(row, "submission_receipt_id",
                "submission_evidence_digest");
        boolean approvalCurrent = submissionCurrent
                && revision(row.get("approved_subject_revision"), subjectRevision)
                && complete(row, "approval_receipt_id", "approval_evidence_digest",
                "approval_step_up_evidence_digest", "approved_by_actor_ref")
                && !row.get("author_actor_ref").equals(row.get("approved_by_actor_ref"));
        if ("DRAFT".equals(state) && pinnedEligible) {
            eligible.add(AutomationService.SUBMIT_SCHEDULE);
        } else if ("PENDING_APPROVAL".equals(state) && pinnedEligible
                && submissionCurrent
                && !row.get("author_actor_ref").equals(subjectPrincipalPublicId)) {
            eligible.add(AutomationService.APPROVE_SCHEDULE);
        } else if ("APPROVED".equals(state) && "ACTIVE".equals(versionState)
                && approvalCurrent) {
            eligible.add(AutomationService.ACTIVATE_SCHEDULE);
        } else if ("ACTIVE".equals(state)) {
            eligible.add(AutomationService.SUSPEND_SCHEDULE);
        }
        return Set.copyOf(eligible);
    }

    public RunDetailResponse runDetail(
            long tenantId,
            UUID runPublicId,
            OffsetDateTime asOf,
            int limit,
            Integer afterAttempt,
            Long afterItem,
            String afterCheckpoint,
            long authorizationRevision,
            UUID correlationId) {
        RunRecord run = owner.readRun(tenantId, runPublicId);
        int checkpointAttempt = 0;
        long checkpointOrdinal = 0;
        if (afterCheckpoint != null) {
            String[] parts = afterCheckpoint.split(":", -1);
            if (parts.length != 2) throw conflict("AUTOMATION_CURSOR_INVALID");
            try {
                checkpointAttempt = Integer.parseInt(parts[0]);
                checkpointOrdinal = Long.parseLong(parts[1]);
            } catch (NumberFormatException invalid) {
                throw conflict("AUTOMATION_CURSOR_INVALID");
            }
        }
        List<AttemptProjection> attempts = jdbc.query("""
                SELECT attempt_number, selection_mode, selected_item_count,
                       selected_item_digest, lifecycle_state, started_at, completed_at
                  FROM sys_automation_run_attempts
                 WHERE tenant_id = ? AND run_id = ? AND created_at <= ?
                   AND attempt_number > ? ORDER BY attempt_number
                 LIMIT ?
                """, (rs, row) -> new AttemptProjection(
                rs.getInt("attempt_number"),
                SelectionMode.valueOf(rs.getString("selection_mode")),
                rs.getLong("selected_item_count"),
                rs.getString("selected_item_digest"),
                RunState.valueOf(rs.getString("lifecycle_state")),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class)),
                tenantId, run.runId(), asOf,
                afterAttempt == null ? 0 : afterAttempt, Math.min(20, limit) + 1);
        List<ItemProjection> items = jdbc.query("""
                SELECT public_id, item_sequence, lifecycle_state, attempt_count,
                       result_digest, error_code, effect_state, compensation_state
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ? AND updated_at <= ?
                   AND item_sequence > ? ORDER BY item_sequence
                 LIMIT ?
                """, (rs, row) -> new ItemProjection(
                uuid(rs, "public_id"), rs.getLong("item_sequence"),
                ItemState.valueOf(rs.getString("lifecycle_state")),
                rs.getInt("attempt_count"), rs.getString("result_digest"),
                rs.getString("error_code"),
                EffectState.valueOf(rs.getString("effect_state")),
                CompensationState.valueOf(rs.getString("compensation_state"))),
                tenantId, run.runId(), asOf, afterItem == null ? 0 : afterItem,
                limit + 1);
        List<CheckpointProjection> checkpoints = jdbc.query("""
                SELECT public_id, attempt_number, checkpoint_ordinal,
                       processed_item_count, processed_item_digest,
                       output_digest, committed_at
                  FROM sys_automation_run_checkpoints
                 WHERE tenant_id = ? AND run_id = ? AND committed_at <= ?
                   AND (attempt_number > ? OR (attempt_number = ?
                        AND checkpoint_ordinal > ?))
                 ORDER BY attempt_number, checkpoint_ordinal LIMIT ?
                """, (rs, row) -> new CheckpointProjection(
                uuid(rs, "public_id"), rs.getInt("attempt_number"),
                rs.getLong("checkpoint_ordinal"),
                rs.getLong("processed_item_count"),
                rs.getString("processed_item_digest"),
                rs.getString("output_digest"),
                rs.getObject("committed_at", OffsetDateTime.class)),
                tenantId, run.runId(), asOf, checkpointAttempt,
                checkpointAttempt, checkpointOrdinal, limit + 1);
        String nextAttempt = attempts.size() > Math.min(20, limit)
                ? Integer.toString(attempts.get(Math.min(20, limit) - 1)
                .attemptNumber()) : null;
        String nextItem = items.size() > limit
                ? Long.toString(items.get(limit - 1).itemSequence()) : null;
        String nextCheckpoint = checkpoints.size() > limit
                ? checkpoints.get(limit - 1).attemptNumber() + ":"
                + checkpoints.get(limit - 1).checkpointOrdinal() : null;
        attempts = List.copyOf(attempts.subList(
                0, Math.min(attempts.size(), Math.min(20, limit))));
        items = List.copyOf(items.subList(0, Math.min(items.size(), limit)));
        checkpoints = List.copyOf(checkpoints.subList(
                0, Math.min(checkpoints.size(), limit)));
        int retryBudget = Math.max(0, run.maxAttempts() - run.currentAttempt());
        RunActionEligibility eligibility = runActionEligibility(run);
        long retryable = eligibility.retryEligibleItemCount();
        CancelEligibility cancel = eligibility.cancelEligibility();
        return new RunDetailResponse(
                run.publicId(), run.definitionPublicId(), run.versionNumber(),
                run.state(), run.objectRevision(), run.selectionMode(),
                run.itemTotal(), run.itemSucceeded(), run.itemFailed(),
                run.outputDigest(), retryBudget, retryable, cancel,
                attempts, items, checkpoints, nextAttempt, nextItem,
                nextCheckpoint, List.of(),
                authorizationRevision, correlationId);
    }

    RunActionEligibility runActionEligibility(RunRecord run) {
        long retryable = 0;
        if (List.of(RunState.PARTIAL, RunState.FAILED).contains(run.state())
                && run.retryMode() != RetryMode.NONE
                && run.currentAttempt() < run.maxAttempts()
                && run.maxAttempts() <= 20) {
            try {
                owner.retrySelection(run);
                Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                          FROM sys_automation_run_items i
                          JOIN LATERAL (
                              SELECT a.receipt_state, a.retryable_snapshot,
                                     a.effect_state
                                FROM sys_automation_item_attempts a
                               WHERE a.tenant_id = i.tenant_id
                                 AND a.run_id = i.run_id
                                 AND a.run_item_id = i.run_item_id
                               ORDER BY a.attempt_ordinal DESC LIMIT 1
                          ) latest ON TRUE
                         WHERE i.tenant_id = ? AND i.run_id = ?
                           AND i.lifecycle_state = 'FAILED_RETRYABLE'
                           AND i.effect_state = 'NO_EFFECT_CONFIRMED'
                           AND latest.receipt_state = 'FAILED_RETRYABLE'
                           AND latest.retryable_snapshot
                           AND latest.effect_state = 'NO_EFFECT_CONFIRMED'
                        """, Long.class, run.tenantId(), run.runId());
                retryable = count == null ? 0 : count;
            } catch (RuntimeException incompleteOrChanged) {
                retryable = 0;
            }
        }

        CancelEligibility cancel = CancelEligibility.INELIGIBLE;
        if (List.of(RunState.QUEUED, RunState.LEASED).contains(run.state())) {
            Long started = jdbc.queryForObject("""
                    SELECT (SELECT COUNT(*) FROM sys_automation_run_items i
                             WHERE i.tenant_id = ? AND i.run_id = ?
                               AND i.effect_state <> 'NOT_STARTED')
                         + (SELECT COUNT(*) FROM sys_automation_item_attempts a
                             WHERE a.tenant_id = ? AND a.run_id = ?
                               AND a.effect_state <> 'NOT_STARTED')
                    """, Long.class, run.tenantId(), run.runId(),
                    run.tenantId(), run.runId());
            if (started != null && started == 0) {
                cancel = CancelEligibility.ELIGIBLE_NO_EFFECT;
            }
        } else if (run.state() == RunState.RUNNING) {
            Long invalid = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM sys_automation_run_items i
                      LEFT JOIN LATERAL (
                          SELECT a.effect_state, a.effect_idempotency_key,
                                 a.effect_request_digest,
                                 a.effect_dispatch_authority_fence,
                                 a.effect_dispatch_control_fence
                            FROM sys_automation_item_attempts a
                           WHERE a.tenant_id = i.tenant_id
                             AND a.run_id = i.run_id
                             AND a.run_item_id = i.run_item_id
                           ORDER BY a.attempt_ordinal DESC LIMIT 1
                      ) latest ON TRUE
                     WHERE i.tenant_id = ? AND i.run_id = ?
                       AND NOT (
                           i.effect_state IN ('NOT_STARTED', 'NO_EFFECT_CONFIRMED')
                           OR (i.effect_state IN ('PREPARED', 'RESULT_UNKNOWN')
                               AND i.effect_idempotency_key IS NOT NULL
                               AND i.effect_request_digest IS NOT NULL
                               AND latest.effect_state = i.effect_state
                               AND latest.effect_idempotency_key
                                   = i.effect_idempotency_key
                               AND latest.effect_request_digest
                                   = i.effect_request_digest
                               AND latest.effect_dispatch_authority_fence IS NOT NULL
                               AND latest.effect_dispatch_control_fence IS NOT NULL)
                           OR (i.effect_state = 'COMMITTED'
                               AND i.effect_receipt_digest IS NOT NULL
                               AND i.compensation_policy_ref IS NOT NULL
                               AND i.compensation_policy_digest IS NOT NULL
                               AND i.compensation_state IN
                                   ('NOT_REQUIRED', 'PENDING', 'RUNNING',
                                    'SUCCEEDED', 'RESULT_UNKNOWN')))
                    """, Long.class, run.tenantId(), run.runId());
            Long uncertain = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_automation_run_items
                     WHERE tenant_id = ? AND run_id = ?
                       AND effect_state IN ('PREPARED', 'RESULT_UNKNOWN')
                    """, Long.class, run.tenantId(), run.runId());
            boolean recognizedReason = run.reconciliationRequiredReason() == null
                    || List.of("RESULT_UNKNOWN_EVIDENCE_REQUIRED",
                    "CANCEL_EFFECT_EVIDENCE_REQUIRED",
                    "CANCEL_COMPENSATION_REQUIRED")
                    .contains(run.reconciliationRequiredReason());
            boolean uncertaintyDeclared = uncertain == null || uncertain == 0
                    || run.reconciliationRequiredReason() != null;
            if (invalid != null && invalid == 0
                    && recognizedReason && uncertaintyDeclared) {
                cancel = CancelEligibility.ELIGIBLE_ALL_REQUIRED_PATHS;
            }
        }
        return new RunActionEligibility(retryable, cancel);
    }
}
