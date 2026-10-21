package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcAutomationRepository implements AutomationRepository,
        AutomationExecutionRepository {

    private static final String EVENT_TYPE =
            "dwp.hris.automation.run.state-changed.v1";
    private static final String EVENT_SOURCE =
            "/dwp-platform-server/hris/automation";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AutomationCanonicalJson canonical;

    JdbcAutomationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.canonical = new AutomationCanonicalJson(json);
    }

    @Override
    public OffsetDateTime databaseNow() {
        return jdbc.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class);
    }

    @Override
    public void bindTenantPrincipal(long tenantId, UUID subjectPrincipalPublicId) {
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
                       status, correlation_id, created_at, aggregate_revision, result_ref
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND originating_action = ? AND idempotency_key = ?
                """, (rs, row) -> storedReceipt(rs), command.tenantId(),
                command.subjectPrincipalPublicId(), command.originatingAction(),
                command.idempotencyKey());
        if (rows.isEmpty()) return Optional.empty();
        StoredReceipt stored = rows.getFirst();
        if (!stored.requestDigest().equals(command.requestDigest())) {
            throw conflict("AUTOMATION_IDEMPOTENCY_CONFLICT");
        }
        return Optional.of(stored.receipt());
    }

    @Override
    public CommandReceipt beginCommand(CommandSeal command, OffsetDateTime now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, command.tenantId() + "\u001f"
                        + command.subjectPrincipalPublicId() + "\u001f"
                        + command.originatingAction() + "\u001f"
                        + command.idempotencyKey());
        Optional<CommandReceipt> replay = findReplay(command);
        if (replay.isPresent()) return replay.get();
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
        return receipt(command, receiptId, ReceiptStatus.ACCEPTED, now, null, null);
    }

    @Override
    public CommandReceipt completeCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        int running = jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RUNNING', aggregate_public_id = ?,
                       aggregate_revision = ?, result_ref = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'ACCEPTED'
                """, aggregatePublicId, aggregateRevision, resultRef,
                command.tenantId(), receiptId);
        int succeeded = running == 1 ? jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'SUCCEEDED', completed_at = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                """, now, command.tenantId(), receiptId) : 0;
        if (succeeded != 1) throw conflict("AUTOMATION_RECEIPT_CAS_CONFLICT");
        return receipt(command, receiptId, ReceiptStatus.SUCCEEDED, now,
                aggregateRevision, resultRef);
    }

    @Override
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

    @Override
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
                """, this::version, tenantId, definitionPublicId, versionNumber)
                .stream().findFirst();
    }

    @Override
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
        return jdbc.query(sql, this::version, tenantId, definitionPublicId)
                .stream().findFirst();
    }

    @Override
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

    private static boolean complete(Map<String, Object> row, String... fields) {
        return Arrays.stream(fields).allMatch(field -> row.get(field) != null);
    }

    private static boolean revision(Object candidate, long expected) {
        return candidate instanceof Number number && number.longValue() == expected;
    }

    @Override
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

    @Override
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
                    """, this::version, tenantId, definitionPublicId,
                    versionNumber);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_VERSION_NOT_FOUND");
        }
    }

    @Override
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

    @Override
    public RunRecord lockRun(long tenantId, UUID runPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT r.*, d.public_id AS definition_public_id
                      FROM sys_automation_runs r
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = r.tenant_id
                       AND d.definition_id = r.definition_id
                     WHERE r.tenant_id = ? AND r.public_id = ? FOR UPDATE OF r
                    """, RUN_MAPPER, tenantId, runPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_RUN_NOT_FOUND");
        }
    }

    private RunRecord readRun(long tenantId, UUID runPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT r.*, d.public_id AS definition_public_id
                      FROM sys_automation_runs r
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = r.tenant_id
                       AND d.definition_id = r.definition_id
                     WHERE r.tenant_id = ? AND r.public_id = ?
                    """, RUN_MAPPER, tenantId, runPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_RUN_NOT_FOUND");
        }
    }

    @Override
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

    @Override
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
                """, this::version, definition.tenantId(), definition.definitionId(),
                versionNumber, stringify(command.definitionPayload()),
                command.definitionDigest(), stringify(command.executionPolicy()),
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

    @Override
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

    @Override
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

    @Override
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

    @Override
    public RunRecord insertRun(
            DefinitionRecord definition,
            VersionRecord version,
            UUID actor,
            UUID commandReceiptId,
            QueueRunCommand command,
            String concurrencyKeyDigest,
            OffsetDateTime now) {
        UUID runId = UUID.randomUUID();
        UUID publicId = UUID.randomUUID();
        try {
            RunRecord run = jdbc.queryForObject("""
                    INSERT INTO sys_automation_runs (
                        run_id, public_id, tenant_id, definition_id, version_id,
                        version_number, scope_type, scope_id, requested_at,
                        requested_by_actor_ref, idempotency_key, correlation_id,
                        input_ref, input_digest, expected_item_count,
                        concurrency_key_digest, retry_mode, max_attempts,
                        item_total, command_receipt_id, created_by_actor_ref,
                        created_at, updated_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, r.idempotency_key,
                           r.correlation_id, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?
                      FROM sys_hris_command_receipts r
                     WHERE r.tenant_id = ? AND r.public_id = ?
                    RETURNING *, ?::uuid AS definition_public_id
                    """, RUN_MAPPER, runId, publicId, definition.tenantId(),
                    definition.definitionId(), version.versionId(),
                    version.versionNumber(), command.scopeType(), command.scopeId(),
                    now, "principal:" + actor, command.inputReference(),
                    command.inputDigest(), command.expectedItemCount(),
                    concurrencyKeyDigest, version.retryMode().name(),
                    version.maxAttempts(), commandReceiptId, actor, now, now,
                    definition.tenantId(), commandReceiptId, definition.publicId());
            jdbc.update("""
                    INSERT INTO sys_automation_run_attempts (
                        tenant_id, run_id, attempt_number, selection_mode,
                        selected_item_count, selected_item_digest, created_at)
                    VALUES (?, ?, 1, 'ALL_ITEMS', 0, ?, ?)
                    """, definition.tenantId(), runId,
                    emptySelectionDigest(publicId), now);
            return run;
        } catch (DuplicateKeyException duplicate) {
            throw conflict("AUTOMATION_CONCURRENCY_CONFLICT");
        }
    }

    @Override
    public RunRecord retryRun(
            RunRecord run,
            UUID actor,
            UUID commandReceiptId,
            SelectionMode selectionMode,
            long selectedItemCount,
            String selectedItemDigest,
            OffsetDateTime now) {
        if (!List.of(RunState.PARTIAL, RunState.FAILED).contains(run.state())
                || run.retryMode() == RetryMode.NONE
                || run.currentAttempt() >= run.maxAttempts()
                || selectedItemCount < 1) {
            throw conflict("AUTOMATION_RUN_NOT_RETRYABLE");
        }
        int next = run.currentAttempt() + 1;
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE sys_automation_runs
                       SET lifecycle_state = 'QUEUED', selection_mode = ?,
                           current_attempt = ?, object_revision = object_revision + 1,
                           output_digest = NULL,
                           reconciliation_required_reason = NULL,
                           updated_at = ?, completed_at = NULL
                     WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                       AND lifecycle_state IN ('PARTIAL', 'FAILED')
                    """, selectionMode.name(), next, now, run.tenantId(),
                    run.runId(), run.objectRevision());
        } catch (DuplicateKeyException concurrency) {
            throw conflict("AUTOMATION_CONCURRENCY_CONFLICT");
        }
        if (updated != 1) throw conflict("AUTOMATION_RUN_CAS_CONFLICT");
        Long resumeAfter = jdbc.queryForObject("""
                SELECT COALESCE(MAX(checkpoint_ordinal), 0)
                  FROM sys_automation_run_checkpoints
                 WHERE tenant_id = ? AND run_id = ?
                """, Long.class, run.tenantId(), run.runId());
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    tenant_id, run_id, attempt_number, selection_mode,
                    selected_item_count, selected_item_digest,
                    resume_after_checkpoint_ordinal, worker_fence_epoch,
                    reconciliation_fence_epoch, created_at)
                SELECT tenant_id, run_id, ?, ?, ?, ?, ?, worker_fence_epoch,
                       reconciliation_fence_epoch, ?
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ?
                """, next, selectionMode.name(), selectedItemCount,
                selectedItemDigest, resumeAfter == null ? 0 : resumeAfter, now,
                run.tenantId(), run.runId());
        List<RetryItem> frozen = jdbc.query("""
                SELECT i.run_item_id, i.public_id, i.lifecycle_state,
                       i.result_digest, i.error_code,
                       latest.item_attempt_id AS prior_item_attempt_id,
                       latest.effect_state, latest.effect_receipt_digest,
                       r.correlation_id
                  FROM sys_automation_run_items i
                  JOIN sys_automation_runs r
                    ON r.tenant_id = i.tenant_id AND r.run_id = i.run_id
                  LEFT JOIN LATERAL (
                      SELECT a.item_attempt_id, a.effect_state,
                             a.effect_receipt_digest
                        FROM sys_automation_item_attempts a
                       WHERE a.tenant_id = i.tenant_id
                         AND a.run_item_id = i.run_item_id
                       ORDER BY a.attempt_ordinal DESC LIMIT 1
                  ) latest ON TRUE
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND (? = 'ALL_ITEMS'
                        OR i.lifecycle_state = 'FAILED_RETRYABLE')
                 ORDER BY i.public_id
                """, (rs, row) -> new RetryItem(
                uuid(rs, "run_item_id"), uuid(rs, "public_id"),
                rs.getString("lifecycle_state"), rs.getString("result_digest"),
                rs.getString("error_code"), uuidNullable(rs, "prior_item_attempt_id"),
                rs.getString("effect_state"), rs.getString("effect_receipt_digest"),
                uuid(rs, "correlation_id")), run.tenantId(), run.runId(),
                selectionMode.name());
        if (frozen.size() != selectedItemCount
                || !selectionDigest(frozen.stream().map(RetryItem::publicId).toList())
                .equals(selectedItemDigest)) {
            throw conflict("AUTOMATION_RETRY_SELECTION_CHANGED");
        }
        for (RetryItem item : frozen) {
            boolean dedupedSuccess = "SUCCEEDED".equals(item.state());
            if (dedupedSuccess && (!"COMMITTED".equals(item.effectState())
                    || item.priorAttemptId() == null
                    || item.effectReceiptDigest() == null)) {
                throw conflict("AUTOMATION_RETRY_DEDUPE_EVIDENCE_MISSING");
            }
            jdbc.update("""
                    INSERT INTO sys_automation_item_attempts (
                        tenant_id, run_id, run_item_id, attempt_ordinal,
                        run_revision_snapshot, deduped_from_item_attempt_id,
                        receipt_state, accepted_at, result_digest, error_code,
                        retryable_snapshot, correlation_id, effect_state,
                        started_at, completed_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?,
                            'NOT_STARTED', ?, ?, ?)
                    """, run.tenantId(), run.runId(), item.runItemId(), next,
                    run.objectRevision() + 1,
                    dedupedSuccess ? item.priorAttemptId() : null,
                    dedupedSuccess ? "SUCCEEDED" : "ACCEPTED", now,
                    dedupedSuccess ? item.resultDigest() : null,
                    dedupedSuccess ? item.errorCode() : null,
                    item.correlationId(), dedupedSuccess ? now : null,
                    dedupedSuccess ? now : null, now);
            if (!dedupedSuccess) {
                int reset = jdbc.update("""
                        UPDATE sys_automation_run_items
                           SET lifecycle_state = 'PENDING', result_digest = NULL,
                               error_code = NULL, effect_state = 'NOT_STARTED',
                               effect_idempotency_key = NULL,
                               effect_request_digest = NULL,
                               effect_receipt_digest = NULL, updated_at = ?
                         WHERE tenant_id = ? AND run_id = ? AND run_item_id = ?
                           AND lifecycle_state = 'FAILED_RETRYABLE'
                           AND effect_state = 'NO_EFFECT_CONFIRMED'
                        """, now, run.tenantId(), run.runId(), item.runItemId());
                if (reset != 1) {
                    throw conflict("AUTOMATION_RETRY_EFFECT_NOT_CONFIRMED_SAFE");
                }
            }
        }
        return lockRun(run.tenantId(), run.publicId());
    }

    @Override
    public RetrySelection retrySelection(RunRecord run) {
        if (!List.of(RunState.PARTIAL, RunState.FAILED).contains(run.state())
                || run.retryMode() == RetryMode.NONE
                || run.currentAttempt() >= run.maxAttempts()
                || run.maxAttempts() > 20) {
            throw conflict("AUTOMATION_RUN_NOT_RETRYABLE");
        }
        Integer unsafe = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_automation_run_items i
                  LEFT JOIN LATERAL (
                      SELECT a.receipt_state, a.retryable_snapshot,
                             a.effect_state, a.effect_receipt_digest
                        FROM sys_automation_item_attempts a
                       WHERE a.tenant_id = i.tenant_id
                         AND a.run_id = i.run_id
                         AND a.run_item_id = i.run_item_id
                       ORDER BY a.attempt_ordinal DESC LIMIT 1
                  ) latest ON TRUE
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND (
                       (i.lifecycle_state = 'FAILED_RETRYABLE' AND NOT (
                           i.effect_state = 'NO_EFFECT_CONFIRMED'
                           AND latest.receipt_state = 'FAILED_RETRYABLE'
                           AND latest.retryable_snapshot
                           AND latest.effect_state = 'NO_EFFECT_CONFIRMED'))
                       OR (? = 'WHOLE_INVOCATION' AND (
                           (i.lifecycle_state = 'SUCCEEDED' AND NOT (
                               i.effect_state = 'COMMITTED'
                               AND i.effect_receipt_digest IS NOT NULL
                               AND latest.receipt_state = 'SUCCEEDED'
                               AND latest.effect_state = 'COMMITTED'
                               AND latest.effect_receipt_digest
                                   = i.effect_receipt_digest))
                           OR i.lifecycle_state NOT IN
                               ('SUCCEEDED', 'FAILED_RETRYABLE'))))
                """, Integer.class, run.tenantId(), run.runId(),
                run.retryMode().name());
        if (unsafe == null || unsafe > 0) {
            throw conflict("AUTOMATION_RETRY_EVIDENCE_INCOMPLETE");
        }
        String statePredicate = run.retryMode() == RetryMode.FAILED_ITEMS_ONLY
                ? " AND lifecycle_state = 'FAILED_RETRYABLE'" : "";
        List<UUID> selected = jdbc.query(
                "SELECT public_id FROM sys_automation_run_items"
                        + " WHERE tenant_id = ? AND run_id = ?" + statePredicate
                        + " ORDER BY public_id",
                (rs, row) -> uuid(rs, "public_id"), run.tenantId(), run.runId());
        if (selected.isEmpty()) {
            throw conflict("AUTOMATION_RETRY_SELECTION_EMPTY");
        }
        Integer retryable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                   AND lifecycle_state = 'FAILED_RETRYABLE'
                """, Integer.class, run.tenantId(), run.runId());
        if (retryable == null || retryable < 1) {
            throw conflict("AUTOMATION_RETRY_SELECTION_EMPTY");
        }
        String digest = selectionDigest(selected);
        if (run.retryMode() == RetryMode.WHOLE_INVOCATION) {
            Integer exactOriginal = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_automation_run_attempts
                     WHERE tenant_id = ? AND run_id = ? AND attempt_number = 1
                       AND selection_mode = 'ALL_ITEMS'
                       AND selected_item_count = ? AND selected_item_digest = ?
                    """, Integer.class, run.tenantId(), run.runId(),
                    selected.size(), digest);
            if (exactOriginal == null || exactOriginal != 1) {
                throw conflict("AUTOMATION_RETRY_ORIGINAL_SELECTION_MISMATCH");
            }
        }
        return new RetrySelection(
                run.retryMode() == RetryMode.FAILED_ITEMS_ONLY
                        ? SelectionMode.FAILED_ITEMS_ONLY : SelectionMode.ALL_ITEMS,
                selected.size(), digest);
    }

    @Override
    public RunRecord cancelRun(
            RunRecord run, String reason, OffsetDateTime now) {
        Integer nonCompensatable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                   AND effect_state = 'COMMITTED'
                   AND compensation_policy_ref IS NULL
                """, Integer.class, run.tenantId(), run.runId());
        if (nonCompensatable != null && nonCompensatable > 0) {
            throw conflict("AUTOMATION_CANCEL_NON_COMPENSATABLE_EFFECT");
        }
        Map<String, Object> effects = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE effect_state IN
                           ('PREPARED', 'RESULT_UNKNOWN')) AS uncertain,
                       COUNT(*) FILTER (WHERE effect_state = 'COMMITTED'
                           AND compensation_state <> 'SUCCEEDED') AS committed,
                       COUNT(*) FILTER (WHERE compensation_state IN
                           ('PENDING', 'RUNNING', 'RESULT_UNKNOWN')) AS pending
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, run.tenantId(), run.runId());
        long uncertain = ((Number) effects.get("uncertain")).longValue();
        long committed = ((Number) effects.get("committed")).longValue();
        long pending = ((Number) effects.get("pending")).longValue();
        boolean immediate = uncertain == 0 && committed == 0 && pending == 0;
        Map<String, Object> lease = jdbc.queryForMap("""
                SELECT worker_lease_token, reconciliation_lease_token
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ? FOR UPDATE
                """, run.tenantId(), run.runId());
        UUID workerToken = (UUID) lease.get("worker_lease_token");
        UUID reconcilerToken = (UUID) lease.get("reconciliation_lease_token");
        if (workerToken != null && reconcilerToken != null) {
            throw conflict("AUTOMATION_AUTHORITY_LEASES_NOT_EXCLUSIVE");
        }
        long nextRevision = run.objectRevision() + 1;
        int updated = jdbc.update("""
                UPDATE sys_automation_runs
                   SET lifecycle_state = 'RUNNING', cancellation_requested_at = ?,
                       cancellation_reason = ?, control_fence_epoch = control_fence_epoch + 1,
                       worker_fence_epoch = worker_fence_epoch +
                           CASE WHEN worker_lease_token IS NULL THEN 0 ELSE 1 END,
                       reconciliation_fence_epoch = reconciliation_fence_epoch +
                           CASE WHEN reconciliation_lease_token IS NULL THEN 0 ELSE 1 END,
                       worker_revoked_at = CASE WHEN worker_lease_token IS NULL
                           THEN worker_revoked_at ELSE ? END,
                       reconciliation_revoked_at = CASE
                           WHEN reconciliation_lease_token IS NULL
                           THEN reconciliation_revoked_at ELSE ? END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_snapshot = NULL,
                       worker_control_fence_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_snapshot = NULL,
                       reconciliation_control_fence_snapshot = NULL,
                       reconciliation_required_reason = CASE
                           WHEN ? > 0 THEN 'CANCEL_EFFECT_EVIDENCE_REQUIRED'
                           WHEN ? > 0 OR ? > 0 THEN 'CANCEL_COMPENSATION_REQUIRED'
                           ELSE reconciliation_required_reason END,
                       output_digest = NULL, completed_at = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND lifecycle_state IN ('QUEUED', 'LEASED', 'RUNNING')
                """, now, reason, now, now, uncertain, committed, pending, now,
                run.tenantId(), run.runId(), run.objectRevision());
        if (updated != 1) throw conflict("AUTOMATION_RUN_CAS_CONFLICT");
        jdbc.update("""
                UPDATE sys_automation_run_items
                   SET compensation_state = 'PENDING', updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND effect_state = 'COMMITTED'
                   AND compensation_policy_ref IS NOT NULL
                   AND compensation_state = 'NOT_REQUIRED'
                """, now, run.tenantId(), run.runId());
        jdbc.update("""
                UPDATE sys_automation_item_attempts a
                   SET receipt_state = 'CANCELLED',
                       run_revision_snapshot = ?,
                       started_at = COALESCE(started_at, ?), completed_at = ?,
                       retryable_snapshot = FALSE,
                       error_code = COALESCE(a.error_code, 'RUN_CANCELLED')
                  FROM sys_automation_run_items i
                 WHERE a.tenant_id = ? AND a.run_id = ?
                   AND a.run_item_id = i.run_item_id
                   AND a.completed_at IS NULL
                   AND a.effect_state IN ('NOT_STARTED', 'NO_EFFECT_CONFIRMED')
                """, nextRevision, now, now,
                run.tenantId(), run.runId());
        jdbc.update("""
                UPDATE sys_automation_run_items
                   SET lifecycle_state = 'CANCELLED', updated_at = ?
                 WHERE tenant_id = ? AND run_id = ?
                   AND effect_state IN ('NOT_STARTED', 'NO_EFFECT_CONFIRMED')
                   AND lifecycle_state NOT IN
                       ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                """, now, run.tenantId(), run.runId());
        if (immediate) {
            String outputDigest = currentReceiptSet(
                    run.tenantId(), run.runId(), run.publicId(), nextRevision).digest();
            int terminal = jdbc.update("""
                    UPDATE sys_automation_runs
                       SET lifecycle_state = 'CANCELLED', output_digest = ?,
                           completed_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                       AND lifecycle_state = 'RUNNING'
                    """, outputDigest, now, now, run.tenantId(), run.runId(),
                    nextRevision);
            if (terminal != 1) {
                throw conflict("AUTOMATION_RUN_CANCEL_FINALIZE_CONFLICT");
            }
        }
        jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET worker_fence_epoch = worker_fence_epoch
                           + CASE WHEN worker_lease_token IS NULL THEN 0 ELSE 1 END,
                       reconciliation_fence_epoch = reconciliation_fence_epoch
                           + CASE WHEN reconciliation_lease_token IS NULL THEN 0 ELSE 1 END,
                       worker_lease_revoked_at = CASE WHEN worker_lease_token IS NULL
                           THEN worker_lease_revoked_at ELSE ? END,
                       reconciliation_lease_revoked_at = CASE
                           WHEN reconciliation_lease_token IS NULL
                           THEN reconciliation_lease_revoked_at ELSE ? END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_epoch_snapshot = NULL,
                       worker_control_fence_epoch_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_epoch_snapshot = NULL,
                       reconciliation_control_fence_epoch_snapshot = NULL,
                       reconciliation_required_at = CASE WHEN ? OR ?
                           THEN COALESCE(reconciliation_required_at, ?) ELSE NULL END,
                       reconciliation_required_reason = CASE
                           WHEN ? > 0 THEN 'CANCEL_EFFECT_EVIDENCE_REQUIRED'
                           WHEN ? > 0 OR ? > 0 THEN 'CANCEL_COMPENSATION_REQUIRED'
                           ELSE NULL END,
                       lifecycle_state = CASE WHEN ? THEN 'CANCELLED'
                           ELSE lifecycle_state END,
                       started_at = CASE WHEN ? THEN COALESCE(started_at, ?)
                           ELSE started_at END,
                       completed_at = CASE WHEN ? THEN ? ELSE completed_at END
                 WHERE tenant_id = ? AND run_id = ? AND attempt_number = ?
                """, now, now, uncertain > 0, committed > 0 || pending > 0, now,
                uncertain, committed, pending, immediate, immediate, now,
                immediate, now, run.tenantId(), run.runId(),
                run.currentAttempt());
        return lockRun(run.tenantId(), run.publicId());
    }

    @Override
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
                    signedManifest(rs), rs.getString("definition_digest"),
                    rs.getString("execution_policy_digest"),
                    rs.getString("compatibility_baseline_digest"),
                    rs.getString("manifest_retry_mode"),
                    rs.getInt("manifest_max_attempts")), tenantId, schedulePublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_SCHEDULE_NOT_FOUND");
        }
    }

    @Override
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

    @Override
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
                stringify(payload), now, now, now);
    }

    @Override
    public RuntimeRun lockRuntimeRun(long tenantId, UUID runPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT r.*, d.public_id AS definition_public_id,
                           a.run_attempt_id, a.attempt_number,
                           a.selected_item_digest, a.selected_item_count,
                           a.worker_fence_epoch AS a_worker_fence_epoch,
                           a.worker_lease_token AS a_worker_lease_token,
                           a.worker_leased_until AS a_worker_leased_until,
                           a.worker_control_fence_epoch_snapshot AS a_worker_control,
                           a.reconciliation_fence_epoch AS a_recon_fence_epoch,
                           a.reconciliation_lease_token AS a_recon_lease_token,
                           a.reconciliation_leased_until AS a_recon_leased_until,
                           a.reconciliation_control_fence_epoch_snapshot AS a_recon_control,
                           a.reconciliation_required_at AS a_recon_required_at,
                           a.reconciliation_required_reason AS a_recon_reason,
                           v.definition_digest, v.execution_policy_digest,
                           v.compatibility_baseline_digest, v.compensation_policy_ref,
                           v.compensation_policy_digest, v.handler_owner_service,
                           v.handler_key, v.handler_contract_version,
                           v.handler_signature_digest, v.allowed_scope_types,
                           v.manifest_retry_mode, v.manifest_max_attempts,
                           v.handler_manifest_state, v.handler_signature_key_id,
                           v.input_schema_ref, v.input_schema_digest,
                           v.receipt_schema_ref, v.receipt_schema_digest,
                           v.handler_manifest_revision
                      FROM sys_automation_runs r
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = r.tenant_id
                       AND d.definition_id = r.definition_id
                      JOIN sys_automation_versions v
                        ON v.tenant_id = r.tenant_id
                       AND v.definition_id = r.definition_id
                       AND v.version_id = r.version_id
                       AND v.version_number = r.version_number
                      JOIN sys_automation_run_attempts a
                        ON a.tenant_id = r.tenant_id AND a.run_id = r.run_id
                       AND a.attempt_number = r.current_attempt
                     WHERE r.tenant_id = ? AND r.public_id = ?
                     FOR UPDATE OF r, a
                    """, (rs, row) -> runtimeRun(rs), tenantId, runPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_RUN_NOT_FOUND");
        }
    }

    @Override
    public void assertLiveOriginalAuthority(
            RuntimeRun run, LeaseGrant authority, PreparedItem item) {
        Boolean live = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM sys_automation_runs r
                      JOIN sys_automation_run_attempts a
                        ON a.tenant_id = r.tenant_id AND a.run_id = r.run_id
                       AND a.attempt_number = r.current_attempt
                      JOIN sys_automation_run_items i
                        ON i.tenant_id = r.tenant_id AND i.run_id = r.run_id
                      JOIN sys_automation_item_attempts ia
                        ON ia.tenant_id = i.tenant_id
                       AND ia.run_id = i.run_id
                       AND ia.run_item_id = i.run_item_id
                       AND ia.attempt_ordinal = a.attempt_number
                     WHERE r.tenant_id = ? AND r.run_id = ? AND i.public_id = ?
                       AND r.object_revision = ? AND r.current_attempt = ?
                       AND i.item_sequence = ? AND i.subject_ref = ?
                       AND ia.run_revision_snapshot = r.object_revision
                       AND ia.accepted_at = ? AND ia.correlation_id = r.correlation_id
                       AND r.worker_lease_token = ? AND a.worker_lease_token = ?
                       AND r.worker_leased_until > statement_timestamp()
                       AND a.worker_leased_until > statement_timestamp()
                       AND r.worker_fence_epoch = ? AND a.worker_fence_epoch = ?
                       AND r.control_fence_epoch = ?
                       AND a.worker_control_fence_epoch_snapshot = ?
                       AND r.reconciliation_lease_token IS NULL
                       AND a.reconciliation_lease_token IS NULL
                       AND ia.effect_state = 'PREPARED'
                       AND ia.effect_idempotency_key = ?
                       AND ia.effect_request_digest = ?)
                """, Boolean.class, run.run().tenantId(), run.run().runId(),
                item.itemPublicId(), run.run().objectRevision(), run.attemptNumber(),
                item.itemSequence(), item.subjectRef(),
                OffsetDateTime.ofInstant(item.acceptedAt(), ZoneOffset.UTC),
                authority.token(), authority.token(), authority.authorityFenceEpoch(),
                authority.authorityFenceEpoch(), authority.controlFenceEpoch(),
                authority.controlFenceEpoch(), item.effectIdempotencyKey(),
                item.effectRequestDigest());
        if (!Boolean.TRUE.equals(live)) {
            throw conflict("AUTOMATION_STALE_EFFECT_AUTHORITY");
        }
    }

    @Override
    public void assertLiveCompensationAuthority(
            RuntimeRun run, LeaseGrant authority,
            PreparedCompensation compensation) {
        Boolean live = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM sys_automation_runs r
                      JOIN sys_automation_run_attempts a
                        ON a.tenant_id = r.tenant_id AND a.run_id = r.run_id
                       AND a.attempt_number = r.current_attempt
                      JOIN sys_automation_run_items i
                        ON i.tenant_id = r.tenant_id AND i.run_id = r.run_id
                      JOIN sys_automation_item_attempts ia
                        ON ia.tenant_id = i.tenant_id
                       AND ia.run_id = i.run_id
                       AND ia.run_item_id = i.run_item_id
                       AND ia.effect_receipt_digest = i.effect_receipt_digest
                     WHERE r.tenant_id = ? AND r.run_id = ? AND i.public_id = ?
                       AND r.object_revision = ? AND r.current_attempt = ?
                       AND i.item_sequence = ? AND i.subject_ref = ?
                       AND ia.accepted_at = ?
                       AND r.reconciliation_lease_token = ?
                       AND a.reconciliation_lease_token = ?
                       AND r.reconciliation_leased_until > statement_timestamp()
                       AND a.reconciliation_leased_until > statement_timestamp()
                       AND r.reconciliation_fence_epoch = ?
                       AND a.reconciliation_fence_epoch = ?
                       AND r.control_fence_epoch = ?
                       AND a.reconciliation_control_fence_epoch_snapshot = ?
                       AND r.worker_lease_token IS NULL
                       AND a.worker_lease_token IS NULL
                       AND i.effect_idempotency_key = ?
                       AND i.effect_receipt_digest = ?
                       AND ia.effect_state = 'COMMITTED'
                       AND ia.effect_idempotency_key = i.effect_idempotency_key
                       AND ia.completed_at IS NOT NULL
                       AND ia.receipt_state IN ('SUCCEEDED', 'FAILED_RETRYABLE',
                           'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                       AND i.compensation_effect_state = 'PREPARED'
                       AND i.compensation_idempotency_key = ?
                       AND i.compensation_request_digest = ?)
                """, Boolean.class, run.run().tenantId(), run.run().runId(),
                compensation.itemPublicId(), compensation.runRevision(),
                compensation.attemptNumber(), compensation.itemSequence(),
                compensation.subjectRef(), OffsetDateTime.ofInstant(
                        compensation.acceptedAt(), ZoneOffset.UTC),
                authority.token(), authority.token(), authority.authorityFenceEpoch(),
                authority.authorityFenceEpoch(), authority.controlFenceEpoch(),
                authority.controlFenceEpoch(),
                compensation.originalEffectIdempotencyKey(),
                compensation.originalEffectReceiptDigest(),
                compensation.compensationIdempotencyKey(),
                compensation.compensationRequestDigest());
        if (!Boolean.TRUE.equals(live)) {
            throw conflict("AUTOMATION_STALE_EFFECT_AUTHORITY");
        }
    }

    @Override
    public LeaseGrant acquireWorkerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        if (!List.of(RunState.QUEUED, RunState.LEASED).contains(run.run().state())
                || run.workerLeaseToken() != null || run.reconcilerLeaseToken() != null
                || run.reconciliationRequiredAt() != null
                || run.reconciliationRequiredReason() != null) {
            throw conflict("AUTOMATION_WORKER_LEASE_UNAVAILABLE");
        }
        long nextFence = run.workerFenceEpoch() + 1;
        int attempt = jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET lifecycle_state = 'LEASED', worker_fence_epoch = ?,
                       worker_lease_token = ?, worker_leased_until = ?,
                       worker_lease_fence_epoch_snapshot = ?,
                       worker_control_fence_epoch_snapshot = ?,
                       worker_lease_revoked_at = NULL,
                       started_at = COALESCE(started_at, ?)
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_at IS NULL
                   AND reconciliation_required_reason IS NULL
                   AND lifecycle_state IN ('QUEUED', 'LEASED')
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), now, run.run().tenantId(),
                run.runAttemptId());
        if (attempt != 1) throw conflict("AUTOMATION_WORKER_LEASE_CAS_CONFLICT");
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs
                   SET lifecycle_state = 'LEASED', worker_fence_epoch = ?,
                       worker_lease_token = ?, worker_leased_until = ?,
                       worker_lease_fence_snapshot = ?,
                       worker_control_fence_snapshot = ?, worker_revoked_at = NULL,
                       output_digest = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_reason IS NULL
                   AND lifecycle_state IN ('QUEUED', 'LEASED')
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), now, run.run().tenantId(), run.run().runId(),
                run.run().objectRevision());
        if (aggregate != 1) throw conflict("AUTOMATION_WORKER_LEASE_CAS_CONFLICT");
        return new LeaseGrant(AutomationDomainPolicy.AuthorityKind.WORKER,
                run.run().tenantId(), run.run().publicId(), run.attemptNumber(),
                leaseToken, nextFence, run.controlFenceEpoch(), leasedUntil);
    }

    @Override
    public LeaseGrant renewWorkerLease(
            RuntimeRun run,
            LeaseGrant current,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        int attempt = jdbc.update("""
                UPDATE sys_automation_run_attempts SET worker_leased_until = ?
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND worker_lease_token = ? AND worker_fence_epoch = ?
                   AND worker_control_fence_epoch_snapshot = ?
                   AND reconciliation_lease_token IS NULL
                   AND worker_leased_until > ?
                """, leasedUntil, run.run().tenantId(), run.runAttemptId(),
                current.token(), current.authorityFenceEpoch(),
                current.controlFenceEpoch(), now);
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs SET worker_leased_until = ?, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND worker_lease_token = ?
                   AND worker_fence_epoch = ? AND control_fence_epoch = ?
                   AND reconciliation_lease_token IS NULL
                   AND worker_leased_until > ?
                """, leasedUntil, now, run.run().tenantId(), run.run().runId(),
                current.token(), current.authorityFenceEpoch(),
                current.controlFenceEpoch(), now);
        if (attempt != 1 || aggregate != 1) {
            throw conflict("AUTOMATION_WORKER_LEASE_CAS_CONFLICT");
        }
        return new LeaseGrant(AutomationDomainPolicy.AuthorityKind.WORKER,
                current.tenantId(), current.runPublicId(), current.attemptNumber(),
                current.token(), current.authorityFenceEpoch(),
                current.controlFenceEpoch(), leasedUntil);
    }

    @Override
    public void materializeItems(
            RuntimeRun run,
            LeaseGrant worker,
            List<AutomationRuntimePorts.AutomationItemPlan> items,
            OffsetDateTime now) {
        requireWorkerFence(run, worker, now);
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, Integer.class, run.run().tenantId(), run.run().runId());
        if (existing == null || existing != 0
                || items.size() != run.expectedItemCount()) {
            throw conflict("AUTOMATION_ITEM_MATERIALIZATION_CONFLICT");
        }
        List<AutomationRuntimePorts.AutomationItemPlan> ordered = items.stream()
                .sorted(Comparator.comparingLong(
                        AutomationRuntimePorts.AutomationItemPlan::itemSequence))
                .toList();
        String selectionDigest = selectionDigest(
                ordered.stream().map(AutomationRuntimePorts.AutomationItemPlan::itemId)
                        .toList());
        int updatedAttempt = jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET selected_item_count = ?, selected_item_digest = ?
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND selected_item_count = 0
                """, ordered.size(), selectionDigest, run.run().tenantId(),
                run.runAttemptId());
        int updatedRun = jdbc.update("""
                UPDATE sys_automation_runs
                   SET item_total = ?, lifecycle_state = 'RUNNING',
                       output_digest = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND worker_lease_token = ? AND worker_fence_epoch = ?
                   AND control_fence_epoch = ?
                """, ordered.size(), now, run.run().tenantId(), run.run().runId(),
                run.run().objectRevision(), worker.token(),
                worker.authorityFenceEpoch(), worker.controlFenceEpoch());
        if (updatedAttempt != 1 || updatedRun != 1) {
            throw conflict("AUTOMATION_ITEM_MATERIALIZATION_CAS_CONFLICT");
        }
        long materializedRevision = run.run().objectRevision() + 1;
        for (AutomationRuntimePorts.AutomationItemPlan item : ordered) {
            UUID internal = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sys_automation_run_items (
                        run_item_id, public_id, tenant_id, run_id, item_sequence,
                        subject_ref, subject_reference_digest, compensation_policy_ref,
                        compensation_policy_digest, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, internal, item.itemId(), run.run().tenantId(),
                    run.run().runId(), item.itemSequence(), item.subjectRef(),
                    AutomationDomainPolicy.sha256(
                            item.subjectRef().getBytes(StandardCharsets.UTF_8)),
                    run.compensationPolicyRef(), run.compensationPolicyDigest(), now);
            jdbc.update("""
                    INSERT INTO sys_automation_item_attempts (
                        tenant_id, run_id, run_item_id, attempt_ordinal,
                        run_revision_snapshot, receipt_state, accepted_at,
                        retryable_snapshot, correlation_id, created_at)
                    VALUES (?, ?, ?, ?, ?, 'ACCEPTED', ?, FALSE, ?, ?)
                    """, run.run().tenantId(), run.run().runId(), internal,
                    run.attemptNumber(), materializedRevision, now,
                    run.correlationId(), now);
        }
    }

    @Override
    public PreparedOriginal prepareOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            String requestDigest,
            OffsetDateTime now) {
        requireWorkerFence(run, worker, now);
        List<PreparedItem> items = jdbc.query("""
                SELECT i.run_item_id, i.public_id, i.item_sequence, i.subject_ref,
                       a.accepted_at
                  FROM sys_automation_run_items i
                  JOIN sys_automation_item_attempts a
                    ON a.tenant_id = i.tenant_id
                   AND a.run_item_id = i.run_item_id
                   AND a.attempt_ordinal = ?
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND i.lifecycle_state NOT IN
                       ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                   AND a.effect_state = 'NOT_STARTED'
                 ORDER BY i.item_sequence
                """, (rs, row) -> {
            UUID publicId = uuid(rs, "public_id");
            String key = AutomationDomainPolicy.sha256(("original\u001f"
                    + run.run().publicId() + "\u001f" + publicId + "\u001f"
                    + run.attemptNumber()).getBytes(StandardCharsets.UTF_8));
            String itemDigest = AutomationDomainPolicy.sha256((requestDigest
                    + "\u001f" + publicId).getBytes(StandardCharsets.UTF_8));
            return new PreparedItem(publicId, rs.getLong("item_sequence"),
                    rs.getString("subject_ref"),
                    rs.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                    key, itemDigest);
        }, run.attemptNumber(), run.run().tenantId(), run.run().runId());
        if (items.isEmpty()) throw conflict("AUTOMATION_DISPATCH_SELECTION_EMPTY");
        for (PreparedItem item : items) {
            int attempt = jdbc.update("""
                    UPDATE sys_automation_item_attempts a
                       SET receipt_state = 'RUNNING', started_at = ?,
                           run_revision_snapshot = ?,
                           effect_state = 'PREPARED', effect_authority_kind = 'WORKER',
                           effect_idempotency_key = ?, effect_request_digest = ?,
                           effect_dispatch_authority_fence = ?,
                           effect_dispatch_control_fence = ?
                      FROM sys_automation_run_items i
                     WHERE a.tenant_id = ? AND a.run_id = ?
                       AND a.run_item_id = i.run_item_id
                       AND a.attempt_ordinal = ? AND i.public_id = ?
                       AND a.effect_state = 'NOT_STARTED'
                    """, now, run.run().objectRevision(),
                    item.effectIdempotencyKey(), item.effectRequestDigest(),
                    worker.authorityFenceEpoch(), worker.controlFenceEpoch(),
                    run.run().tenantId(), run.run().runId(), run.attemptNumber(),
                    item.itemPublicId());
            int projection = jdbc.update("""
                    UPDATE sys_automation_run_items
                       SET lifecycle_state = 'RUNNING',
                           attempt_count = attempt_count + 1,
                           effect_state = 'PREPARED', effect_idempotency_key = ?,
                           effect_request_digest = ?, updated_at = ?
                     WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                       AND effect_state = 'NOT_STARTED'
                    """, item.effectIdempotencyKey(), item.effectRequestDigest(), now,
                    run.run().tenantId(), run.run().runId(), item.itemPublicId());
            if (attempt != 1 || projection != 1) {
                throw conflict("AUTOMATION_EFFECT_PREPARE_CAS_CONFLICT");
            }
        }
        return new PreparedOriginal(invocation(run), run.manifest(), now.plusMinutes(1),
                items);
    }

    @Override
    public RuntimeRun finalizeOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            PreparedOriginal prepared,
            List<AutomationRunItemReceiptV1> receipts,
            OffsetDateTime now) {
        requireWorkerFence(run, worker, now);
        Map<UUID, PreparedItem> preparedById = prepared.items().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        PreparedItem::itemPublicId, item -> item));
        for (AutomationRunItemReceiptV1 rawReceipt : receipts) {
            AutomationRunItemReceiptV1 receipt =
                    Sys006TypedArtifactContract.requireInteroperable(rawReceipt);
            PreparedItem item = preparedById.get(receipt.itemId());
            if (item == null || !receipt.runId().equals(run.run().publicId())
                    || receipt.itemSequence() != item.itemSequence()
                    || !receipt.subjectRef().equals(item.subjectRef())
                    || !receipt.acceptedAt().equals(item.acceptedAt())
                    || receipt.runRevision() != run.run().objectRevision()
                    || receipt.attemptNumber() != run.attemptNumber()
                    || !receipt.correlationId().equals(run.correlationId())
                    || receipt.completedAt() == null
                    || !Set.of("SUCCEEDED", "FAILED", "CANCELED",
                    "DEAD_LETTERED").contains(receipt.state())) {
                throw conflict("AUTOMATION_EFFECT_RECEIPT_SUBSTITUTED");
            }
            if ("FAILED".equals(receipt.state())
                    && (run.run().retryMode() == RetryMode.NONE
                    || !receipt.retryable()
                    || run.attemptNumber() >= run.run().maxAttempts())) {
                throw conflict("AUTOMATION_EFFECT_RECEIPT_NOT_OWNER_NORMALIZED");
            }
            String originalReceiptState = mapReceiptState(receipt);
            String receiptState = originalReceiptState;
            if (List.of("FAILED_RETRYABLE", "FAILED_FINAL")
                    .contains(receiptState)
                    && (run.run().retryMode() == RetryMode.NONE
                    || !receipt.retryable()
                    || run.attemptNumber() >= run.run().maxAttempts())) {
                receiptState = "DEAD_LETTER";
            }
            boolean retryableSnapshot = "FAILED_RETRYABLE".equals(receiptState);
            String effectState = switch (originalReceiptState) {
                case "SUCCEEDED" -> "COMMITTED";
                case "CANCELLED" -> "NO_EFFECT_CONFIRMED";
                case "DEAD_LETTER" -> receipt.resultDigest() == null
                        ? "RESULT_UNKNOWN" : "COMMITTED";
                default -> receipt.resultDigest() == null
                        ? "RESULT_UNKNOWN" : "COMMITTED";
            };
            UUID receiptId = UUID.randomUUID();
            String receiptDigest = canonical.digest(receipt);
            int attempt = jdbc.update("""
                    UPDATE sys_automation_item_attempts a
                       SET receipt_state = ?, completed_at = ?, result_ref = ?,
                           run_revision_snapshot = ?, result_digest = ?,
                           error_code = ?, retryable_snapshot = ?,
                           next_attempt_at = ?, effect_state = ?, effect_receipt_id = ?,
                           effect_receipt_digest = ?, effect_resolution_authority_fence = ?,
                           effect_resolution_control_fence = ?,
                           effect_resolution_mode = 'DISPATCH_FINALIZE',
                           effect_committed_at = CASE WHEN ? = 'COMMITTED' THEN ? ELSE NULL END
                      FROM sys_automation_run_items i
                     WHERE a.tenant_id = ? AND a.run_id = ?
                       AND a.run_item_id = i.run_item_id
                       AND a.attempt_ordinal = ? AND i.public_id = ?
                       AND a.effect_state = 'PREPARED'
                       AND a.effect_idempotency_key = ?
                       AND a.effect_request_digest = ?
                       AND a.effect_dispatch_authority_fence = ?
                       AND a.effect_dispatch_control_fence = ?
                    """, receiptState,
                    OffsetDateTime.ofInstant(receipt.completedAt(), ZoneOffset.UTC),
                    receipt.resultRef(), run.run().objectRevision(),
                    receipt.resultDigest(), receipt.errorCode(),
                    retryableSnapshot, !retryableSnapshot
                    || receipt.nextAttemptAt() == null ? null
                    : OffsetDateTime.ofInstant(receipt.nextAttemptAt(), ZoneOffset.UTC),
                    effectState, receiptId, receiptDigest,
                    worker.authorityFenceEpoch(), worker.controlFenceEpoch(),
                    effectState, now, run.run().tenantId(), run.run().runId(),
                    run.attemptNumber(), receipt.itemId(), item.effectIdempotencyKey(),
                    item.effectRequestDigest(), worker.authorityFenceEpoch(),
                    worker.controlFenceEpoch());
            int projection = jdbc.update("""
                    UPDATE sys_automation_run_items
                       SET lifecycle_state = ?, result_digest = ?, error_code = ?,
                           effect_state = ?, effect_receipt_digest = ?, updated_at = ?
                     WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                       AND effect_state = 'PREPARED'
                       AND effect_idempotency_key = ? AND effect_request_digest = ?
                    """, receiptState, receipt.resultDigest(), receipt.errorCode(),
                    effectState, receiptDigest, now, run.run().tenantId(),
                    run.run().runId(), receipt.itemId(), item.effectIdempotencyKey(),
                    item.effectRequestDigest());
            if (attempt != 1 || projection != 1) {
                throw conflict("AUTOMATION_EFFECT_FINALIZE_CAS_CONFLICT");
            }
            AutomationRunItemReceiptV1 reloaded = loadOriginalReceipt(
                    run, receipt.itemId(), run.attemptNumber());
            String storedDigest = jdbc.queryForObject("""
                    SELECT a.effect_receipt_digest
                      FROM sys_automation_item_attempts a
                      JOIN sys_automation_run_items i
                        ON i.tenant_id = a.tenant_id
                       AND i.run_id = a.run_id
                       AND i.run_item_id = a.run_item_id
                     WHERE a.tenant_id = ? AND a.run_id = ?
                       AND a.attempt_ordinal = ? AND i.public_id = ?
                    """, String.class, run.run().tenantId(), run.run().runId(),
                    run.attemptNumber(), receipt.itemId());
            String reloadedDigest = canonical.digest(reloaded);
            if (!receipt.equals(reloaded) || !receiptDigest.equals(storedDigest)
                    || !receiptDigest.equals(reloadedDigest)) {
                throw conflict("AUTOMATION_EFFECT_RECEIPT_PERSISTENCE_MISMATCH");
            }
            if ("RESULT_UNKNOWN".equals(effectState)) {
                jdbc.update("""
                        UPDATE sys_automation_run_attempts
                           SET reconciliation_required_at = ?,
                               reconciliation_required_reason =
                                   'RESULT_UNKNOWN_EVIDENCE_REQUIRED'
                         WHERE tenant_id = ? AND run_attempt_id = ?
                        """, now, run.run().tenantId(), run.runAttemptId());
                jdbc.update("""
                        UPDATE sys_automation_runs
                           SET reconciliation_required_reason =
                                   'RESULT_UNKNOWN_EVIDENCE_REQUIRED',
                               updated_at = ?
                         WHERE tenant_id = ? AND run_id = ?
                        """, now, run.run().tenantId(), run.run().runId());
            }
        }
        return lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }

    @Override
    public LeaseGrant acquireReconcilerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        if (run.workerLeaseToken() != null || run.reconcilerLeaseToken() != null
                || run.reconciliationRequiredAt() == null) {
            throw conflict("AUTOMATION_RECONCILER_LEASE_UNAVAILABLE");
        }
        long nextFence = run.reconcilerFenceEpoch() + 1;
        int attempt = jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET reconciliation_fence_epoch = ?,
                       reconciliation_lease_token = ?,
                       reconciliation_leased_until = ?,
                       reconciliation_lease_fence_epoch_snapshot = ?,
                       reconciliation_control_fence_epoch_snapshot = ?,
                       reconciliation_lease_revoked_at = NULL
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_at IS NOT NULL
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), run.run().tenantId(), run.runAttemptId());
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs
                   SET reconciliation_fence_epoch = ?,
                       reconciliation_lease_token = ?,
                       reconciliation_leased_until = ?,
                       reconciliation_lease_fence_snapshot = ?,
                       reconciliation_control_fence_snapshot = ?,
                       reconciliation_revoked_at = NULL,
                       output_digest = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_reason IS NOT NULL
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), now, run.run().tenantId(), run.run().runId(),
                run.run().objectRevision());
        if (attempt != 1 || aggregate != 1) {
            throw conflict("AUTOMATION_RECONCILER_LEASE_CAS_CONFLICT");
        }
        return new LeaseGrant(AutomationDomainPolicy.AuthorityKind.RECONCILER,
                run.run().tenantId(), run.run().publicId(), run.attemptNumber(),
                leaseToken, nextFence, run.controlFenceEpoch(), leasedUntil);
    }

    @Override
    public RecoveryResult recoverAuthority(
            RuntimeRun run,
            AutomationDomainPolicy.RecoveryDecision decision,
            UUID suppliedToken,
            boolean explicitlyRevoked,
            String reason,
            OffsetDateTime now) {
        Long unresolvedEffects = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*)
                          FROM sys_automation_item_attempts a
                         WHERE a.tenant_id = ? AND a.run_id = ?
                           AND a.attempt_ordinal = ?
                           AND a.effect_state IN ('PREPARED', 'RESULT_UNKNOWN'))
                     + (SELECT COUNT(*)
                          FROM sys_automation_run_items i
                         WHERE i.tenant_id = ? AND i.run_id = ?
                           AND i.compensation_state IN
                               ('PENDING', 'RUNNING', 'RESULT_UNKNOWN'))
                """, Long.class, run.run().tenantId(), run.run().runId(),
                run.attemptNumber(), run.run().tenantId(), run.run().runId());
        boolean reconciliationRequired = (unresolvedEffects != null
                && unresolvedEffects > 0)
                || run.reconciliationRequiredAt() != null
                || run.reconciliationRequiredReason() != null
                || decision.branch()
                == AutomationDomainPolicy.RecoveryBranch.RECONCILER_LEASE;
        String attemptAssignments;
        String runAssignments;
        List<Object> attemptValues = new ArrayList<>();
        List<Object> runValues = new ArrayList<>();
        switch (decision.branch()) {
            case NO_ACTIVE_LEASE -> {
                attemptAssignments = reconciliationRequired ? """
                        reconciliation_required_at = COALESCE(
                            reconciliation_required_at, ?),
                        reconciliation_required_reason = COALESCE(
                            reconciliation_required_reason, ?)
                        """ : """
                        lifecycle_state = 'QUEUED',
                        reconciliation_required_at = NULL,
                        reconciliation_required_reason = NULL
                        """;
                runAssignments = reconciliationRequired ? """
                        control_fence_epoch = ?,
                        reconciliation_required_reason = COALESCE(
                            reconciliation_required_reason, ?), updated_at = ?
                        """ : """
                        lifecycle_state = 'QUEUED', control_fence_epoch = ?,
                        reconciliation_required_reason = NULL, updated_at = ?
                        """;
                if (reconciliationRequired) {
                    attemptValues.add(now);
                    attemptValues.add(reason);
                }
                runValues.add(decision.nextControlFence());
                if (reconciliationRequired) runValues.add(reason);
                runValues.add(now);
            }
            case WORKER_LEASE -> {
                attemptAssignments = """
                        worker_fence_epoch = ?, worker_lease_revoked_at = ?,
                        worker_lease_token = NULL, worker_leased_until = NULL,
                        worker_lease_fence_epoch_snapshot = NULL,
                        worker_control_fence_epoch_snapshot = NULL,
                        lifecycle_state = CASE WHEN ? THEN lifecycle_state
                            ELSE 'QUEUED' END,
                        reconciliation_required_at = COALESCE(
                            reconciliation_required_at,
                            CASE WHEN ? THEN ? ELSE NULL END),
                        reconciliation_required_reason = CASE WHEN ?
                            THEN COALESCE(reconciliation_required_reason, ?)
                            ELSE NULL END
                        """;
                runAssignments = """
                        control_fence_epoch = ?, worker_fence_epoch = ?,
                        worker_revoked_at = ?, worker_lease_token = NULL,
                        worker_leased_until = NULL, worker_lease_fence_snapshot = NULL,
                        worker_control_fence_snapshot = NULL,
                        lifecycle_state = CASE WHEN ? THEN lifecycle_state
                            ELSE 'QUEUED' END,
                        reconciliation_required_reason = CASE WHEN ?
                            THEN COALESCE(reconciliation_required_reason, ?)
                            ELSE NULL END, updated_at = ?
                        """;
                attemptValues.add(decision.nextWorkerFence());
                attemptValues.add(now);
                attemptValues.add(reconciliationRequired);
                attemptValues.add(reconciliationRequired);
                attemptValues.add(now);
                attemptValues.add(reconciliationRequired);
                attemptValues.add(reason);
                runValues.add(decision.nextControlFence());
                runValues.add(decision.nextWorkerFence());
                runValues.add(now);
                runValues.add(reconciliationRequired);
                runValues.add(reconciliationRequired);
                runValues.add(reason);
                runValues.add(now);
            }
            case RECONCILER_LEASE -> {
                attemptAssignments = """
                        reconciliation_fence_epoch = ?,
                        reconciliation_lease_revoked_at = ?,
                        reconciliation_lease_token = NULL,
                        reconciliation_leased_until = NULL,
                        reconciliation_lease_fence_epoch_snapshot = NULL,
                        reconciliation_control_fence_epoch_snapshot = NULL
                        """;
                runAssignments = """
                        control_fence_epoch = ?, reconciliation_fence_epoch = ?,
                        reconciliation_revoked_at = ?,
                        reconciliation_lease_token = NULL,
                        reconciliation_leased_until = NULL,
                        reconciliation_lease_fence_snapshot = NULL,
                        reconciliation_control_fence_snapshot = NULL, updated_at = ?
                        """;
                attemptValues.add(decision.nextReconcilerFence());
                attemptValues.add(now);
                runValues.add(decision.nextControlFence());
                runValues.add(decision.nextReconcilerFence());
                runValues.add(now);
                runValues.add(now);
            }
            default -> throw new IllegalStateException();
        }
        attemptValues.add(run.run().tenantId());
        attemptValues.add(run.runAttemptId());
        runValues.add(run.run().tenantId());
        runValues.add(run.run().runId());
        runValues.add(run.run().objectRevision());
        String branchPredicate = switch (decision.branch()) {
            case NO_ACTIVE_LEASE -> " AND worker_lease_token IS NULL"
                    + " AND reconciliation_lease_token IS NULL";
            case WORKER_LEASE -> " AND worker_lease_token = ?"
                    + " AND (worker_leased_until <= statement_timestamp() OR ?)";
            case RECONCILER_LEASE -> " AND reconciliation_lease_token = ?"
                    + " AND (reconciliation_leased_until <= statement_timestamp() OR ?)";
        };
        if (decision.branch() != AutomationDomainPolicy.RecoveryBranch.NO_ACTIVE_LEASE) {
            attemptValues.add(suppliedToken);
            attemptValues.add(explicitlyRevoked);
            runValues.add(suppliedToken);
            runValues.add(explicitlyRevoked);
        }
        int attempt = jdbc.update("UPDATE sys_automation_run_attempts SET "
                + attemptAssignments + " WHERE tenant_id = ? AND run_attempt_id = ?"
                + branchPredicate, attemptValues.toArray());
        int aggregate = jdbc.update("UPDATE sys_automation_runs SET "
                + runAssignments
                + ", output_digest = NULL"
                + ", object_revision = object_revision + 1"
                + " WHERE tenant_id = ? AND run_id = ? AND object_revision = ?"
                + branchPredicate, runValues.toArray());
        if (attempt != 1 || aggregate != 1) {
            throw conflict("AUTOMATION_OWNER_RECOVERY_CAS_CONFLICT");
        }
        return new RecoveryResult(
                decision.branch(), decision.nextControlFence(),
                decision.nextWorkerFence(), decision.nextReconcilerFence(),
                reconciliationRequired, false);
    }

    @Override
    public RuntimeRun reconcileOriginal(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String effectIdempotencyKey,
            String effectRequestDigest,
            AutomationRuntimePorts.ReconciliationEvidence evidence,
            OffsetDateTime now) {
        requireReconcilerFence(run, reconciler, now);
        String effectState = evidence.outcome();
        int attempt = jdbc.update("""
                UPDATE sys_automation_item_attempts a
                   SET effect_state = ?, effect_authority_kind = 'RECONCILER',
                       run_revision_snapshot = CASE WHEN a.completed_at IS NULL
                           THEN r.object_revision ELSE a.run_revision_snapshot END,
                       effect_receipt_digest = CASE WHEN a.completed_at IS NULL
                           THEN COALESCE(?, a.effect_receipt_digest)
                           ELSE a.effect_receipt_digest END,
                       effect_resolution_authority_fence = ?,
                       effect_resolution_control_fence = ?,
                       effect_resolution_mode = 'EVIDENCE_RECONCILE',
                       effect_trusted_evidence_digest = ?,
                       effect_committed_at = CASE WHEN ? = 'COMMITTED'
                           THEN COALESCE(effect_committed_at, ?) ELSE effect_committed_at END,
                       receipt_state = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN CASE
                                   WHEN r.cancellation_requested_at IS NOT NULL
                                       THEN 'CANCELLED'
                                   WHEN r.retry_mode <> 'NONE'
                                       AND r.current_attempt < r.max_attempts
                                       THEN 'FAILED_RETRYABLE'
                                   ELSE 'DEAD_LETTER' END
                           ELSE a.receipt_state END,
                       retryable_snapshot = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN r.cancellation_requested_at IS NULL
                                   AND r.retry_mode <> 'NONE'
                                   AND r.current_attempt < r.max_attempts
                           ELSE a.retryable_snapshot END,
                       error_code = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN 'EXTERNAL_NO_EFFECT_CONFIRMED'
                           ELSE a.error_code END,
                       completed_at = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN ? ELSE a.completed_at END
                  FROM sys_automation_run_items i, sys_automation_runs r
                 WHERE a.tenant_id = ? AND a.run_id = ?
                   AND a.run_item_id = i.run_item_id
                   AND r.tenant_id = a.tenant_id AND r.run_id = a.run_id
                   AND a.attempt_ordinal = ? AND i.public_id = ?
                   AND a.effect_idempotency_key = ?
                   AND a.effect_request_digest = ?
                   AND a.effect_state IN ('PREPARED', 'RESULT_UNKNOWN')
                   AND (a.effect_resolution_mode IS NULL
                        OR a.effect_resolution_mode IN
                            ('DISPATCH_FINALIZE', 'EVIDENCE_RECONCILE'))
                """, effectState, evidence.receiptDigest(),
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(),
                evidence.evidenceDigest(), effectState, now, effectState,
                effectState, effectState, effectState, now,
                run.run().tenantId(), run.run().runId(), run.attemptNumber(),
                itemPublicId, effectIdempotencyKey, effectRequestDigest);
        int projection = jdbc.update("""
                UPDATE sys_automation_run_items i
                   SET effect_state = ?,
                       effect_receipt_digest = CASE WHEN i.lifecycle_state IN
                           ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                           THEN i.effect_receipt_digest
                           ELSE COALESCE(?, i.effect_receipt_digest) END,
                       lifecycle_state = CASE
                           WHEN ? = 'COMMITTED' AND EXISTS (
                               SELECT 1 FROM sys_automation_item_attempts terminal
                                WHERE terminal.tenant_id = i.tenant_id
                                  AND terminal.run_id = i.run_id
                                  AND terminal.run_item_id = i.run_item_id
                                  AND terminal.completed_at IS NOT NULL
                                  AND terminal.receipt_state IN
                                     ('SUCCEEDED', 'FAILED_RETRYABLE',
                                      'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER'))
                               AND i.lifecycle_state NOT IN
                                  ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED',
                                   'DEAD_LETTER') THEN 'SUCCEEDED'
                           WHEN ? <> 'NO_EFFECT_CONFIRMED'
                               OR i.lifecycle_state IN
                                  ('FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                               THEN i.lifecycle_state
                           WHEN r.cancellation_requested_at IS NOT NULL
                               THEN 'CANCELLED'
                           WHEN r.retry_mode <> 'NONE'
                               AND r.current_attempt < r.max_attempts
                               THEN 'FAILED_RETRYABLE'
                           ELSE 'DEAD_LETTER' END,
                       error_code = CASE WHEN ? = 'NO_EFFECT_CONFIRMED'
                           THEN COALESCE(i.error_code,
                               'EXTERNAL_NO_EFFECT_CONFIRMED')
                           ELSE i.error_code END,
                       updated_at = ?
                  FROM sys_automation_runs r
                 WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                   AND i.effect_idempotency_key = ?
                   AND i.effect_request_digest = ?
                   AND r.tenant_id = i.tenant_id AND r.run_id = i.run_id
                   AND i.effect_state IN ('PREPARED', 'RESULT_UNKNOWN')
                """, effectState, evidence.receiptDigest(), effectState, effectState,
                effectState, now, run.run().tenantId(), run.run().runId(), itemPublicId,
                effectIdempotencyKey, effectRequestDigest);
        if (attempt != 1 || projection != 1) {
            throw conflict("AUTOMATION_ORIGINAL_RECONCILIATION_CAS_CONFLICT");
        }
        if ("COMMITTED".equals(effectState)) {
            jdbc.update("""
                    UPDATE sys_automation_run_items i
                       SET compensation_state = 'PENDING', updated_at = ?
                      FROM sys_automation_runs r
                     WHERE i.tenant_id = ? AND i.run_id = ?
                       AND i.public_id = ?
                       AND r.tenant_id = i.tenant_id AND r.run_id = i.run_id
                       AND r.cancellation_requested_at IS NOT NULL
                       AND i.compensation_policy_ref IS NOT NULL
                       AND i.compensation_policy_digest IS NOT NULL
                       AND i.compensation_state = 'NOT_REQUIRED'
                       AND EXISTS (
                           SELECT 1
                             FROM sys_automation_item_attempts terminal
                            WHERE terminal.tenant_id = i.tenant_id
                              AND terminal.run_id = i.run_id
                              AND terminal.run_item_id = i.run_item_id
                              AND terminal.effect_state = 'COMMITTED'
                              AND terminal.effect_receipt_digest =
                                  i.effect_receipt_digest
                              AND terminal.completed_at IS NOT NULL
                              AND terminal.receipt_state IN
                                 ('SUCCEEDED', 'FAILED_RETRYABLE',
                                  'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER'))
                    """, now, run.run().tenantId(), run.run().runId(), itemPublicId);
        }
        return lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }

    @Override
    public PreparedCompensation prepareCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String compensationKey,
            String requestDigest,
            OffsetDateTime now) {
        requireReconcilerFence(run, reconciler, now);
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT effect_idempotency_key, effect_receipt_digest,
                           compensation_policy_ref, compensation_policy_digest,
                           compensation_state, compensation_effect_state
                      FROM sys_automation_run_items
                     WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                       AND effect_state = 'COMMITTED' FOR UPDATE
                    """, run.run().tenantId(), run.run().runId(), itemPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw conflict("AUTOMATION_COMPENSATION_NOT_ELIGIBLE");
        }
        String originalKey = (String) row.get("effect_idempotency_key");
        String originalReceipt = (String) row.get("effect_receipt_digest");
        if (originalKey == null || originalReceipt == null
                || compensationKey.equals(originalKey)
                || row.get("compensation_policy_ref") == null
                || row.get("compensation_policy_digest") == null) {
            throw conflict("AUTOMATION_COMPENSATION_EVIDENCE_INVALID");
        }
        AutomationRunItemReceiptV1 original =
                loadCommittedOriginalReceipt(run, itemPublicId);
        if (!canonical.digest(original).equals(originalReceipt)) {
            throw conflict("AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_INVALID");
        }
        int updated = jdbc.update("""
                UPDATE sys_automation_run_items
                   SET compensation_state = 'RUNNING',
                       compensation_effect_state = 'PREPARED',
                       compensation_authority_kind = 'RECONCILER',
                       compensation_dispatch_reconciliation_fence = ?,
                       compensation_dispatch_control_fence = ?,
                       compensation_idempotency_key = ?,
                       compensation_request_digest = ?, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                   AND effect_state = 'COMMITTED'
                   AND compensation_state = 'PENDING'
                   AND compensation_effect_state = 'NOT_STARTED'
                """, reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(),
                compensationKey, requestDigest, now, run.run().tenantId(),
                run.run().runId(), itemPublicId);
        if (updated != 1) throw conflict("AUTOMATION_COMPENSATION_PREPARE_CONFLICT");
        return new PreparedCompensation(invocation(run), run.manifest(),
                now.plusMinutes(1), itemPublicId, original.itemSequence(),
                original.subjectRef(), original.acceptedAt(),
                run.run().objectRevision(), run.attemptNumber(),
                compensationKey, requestDigest, originalKey, originalReceipt);
    }

    @Override
    public AutomationRunItemReceiptV1 loadCommittedOriginalReceipt(
            RuntimeRun run, UUID itemPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT i.public_id, i.item_sequence, i.subject_ref,
                           a.run_revision_snapshot, a.receipt_state,
                           a.attempt_ordinal, a.accepted_at,
                           a.completed_at, a.result_ref, a.result_digest,
                           a.error_code, a.retryable_snapshot, a.next_attempt_at,
                           a.correlation_id
                      FROM sys_automation_run_items i
                      JOIN sys_automation_item_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.run_id = i.run_id
                       AND a.run_item_id = i.run_item_id
                     WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                       AND i.effect_state = 'COMMITTED'
                       AND a.effect_state = 'COMMITTED'
                       AND a.effect_receipt_digest = i.effect_receipt_digest
                       AND a.completed_at IS NOT NULL
                       AND a.receipt_state IN ('SUCCEEDED', 'FAILED_RETRYABLE',
                           'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                     ORDER BY a.attempt_ordinal DESC
                     LIMIT 1
                    """, (rs, row) -> new AutomationRunItemReceiptV1(
                            run.run().publicId(), rs.getLong("run_revision_snapshot"),
                            uuid(rs, "public_id"), rs.getLong("item_sequence"),
                            rs.getString("subject_ref"),
                            canonicalReceiptState(rs.getString("receipt_state")),
                            rs.getInt("attempt_ordinal"),
                            rs.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                            rs.getString("result_ref"), rs.getString("result_digest"),
                            rs.getString("error_code"),
                            rs.getBoolean("retryable_snapshot"),
                            rs.getObject("next_attempt_at", OffsetDateTime.class) == null
                                    ? null : rs.getObject("next_attempt_at",
                                    OffsetDateTime.class).toInstant(),
                            uuid(rs, "correlation_id")),
                    run.run().tenantId(), run.run().runId(), itemPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw conflict("AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_MISSING");
        }
    }

    private AutomationRunItemReceiptV1 loadOriginalReceipt(
            RuntimeRun run, UUID itemPublicId, int attemptNumber) {
        try {
            return jdbc.queryForObject("""
                    SELECT i.public_id, i.item_sequence, i.subject_ref,
                           a.run_revision_snapshot, a.receipt_state,
                           a.attempt_ordinal, a.accepted_at,
                           a.completed_at, a.result_ref, a.result_digest,
                           a.error_code, a.retryable_snapshot, a.next_attempt_at,
                           a.correlation_id
                      FROM sys_automation_run_items i
                      JOIN sys_automation_item_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.run_id = i.run_id
                       AND a.run_item_id = i.run_item_id
                     WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                       AND a.attempt_ordinal = ?
                    """, (rs, row) -> new AutomationRunItemReceiptV1(
                            run.run().publicId(),
                            rs.getLong("run_revision_snapshot"),
                            uuid(rs, "public_id"), rs.getLong("item_sequence"),
                            rs.getString("subject_ref"),
                            canonicalReceiptState(rs.getString("receipt_state")),
                            rs.getInt("attempt_ordinal"),
                            rs.getObject("accepted_at", OffsetDateTime.class)
                                    .toInstant(),
                            rs.getObject("completed_at", OffsetDateTime.class)
                                    .toInstant(),
                            rs.getString("result_ref"),
                            rs.getString("result_digest"),
                            rs.getString("error_code"),
                            rs.getBoolean("retryable_snapshot"),
                            rs.getObject("next_attempt_at") == null ? null
                                    : rs.getObject("next_attempt_at",
                                    OffsetDateTime.class).toInstant(),
                            uuid(rs, "correlation_id")),
                    run.run().tenantId(), run.run().runId(), itemPublicId,
                    attemptNumber);
        } catch (EmptyResultDataAccessException missing) {
            throw conflict("AUTOMATION_ORIGINAL_RECEIPT_MISSING");
        }
    }

    @Override
    public RuntimeRun finalizeCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            PreparedCompensation prepared,
            String outcome,
            String receiptDigest,
            AutomationRuntimePorts.ReconciliationEvidence trustedEvidence,
            OffsetDateTime now) {
        requireReconcilerFence(run, reconciler, now);
        if (!List.of("COMMITTED", "RESULT_UNKNOWN").contains(outcome)
                || receiptDigest == null
                || !receiptDigest.matches("^[a-f0-9]{64}$")) {
            throw conflict("AUTOMATION_COMPENSATION_RECEIPT_INVALID");
        }
        boolean evidenceMode = trustedEvidence != null;
        String mode = evidenceMode ? "EVIDENCE_RECONCILE" : "DISPATCH_FINALIZE";
        String state = "COMMITTED".equals(outcome) ? "SUCCEEDED" : "RESULT_UNKNOWN";
        String sql = """
                UPDATE sys_automation_run_items
                   SET compensation_state = ?, compensation_effect_state = ?,
                       compensation_receipt_id = ?, compensation_receipt_digest = ?,
                       compensation_resolution_reconciliation_fence = ?,
                       compensation_resolution_control_fence = ?,
                       compensation_resolution_mode = ?,
                       compensation_trusted_evidence_digest = ?,
                       compensation_completed_at = CASE WHEN ? = 'COMMITTED'
                           THEN ? ELSE NULL END, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                   AND compensation_idempotency_key = ?
                   AND compensation_request_digest = ?
                """ + (evidenceMode
                ? " AND compensation_effect_state IN ('PREPARED', 'RESULT_UNKNOWN')"
                + " AND compensation_dispatch_reconciliation_fence <> ?"
                : " AND compensation_effect_state = 'PREPARED'"
                + " AND compensation_dispatch_reconciliation_fence = ?"
                + " AND compensation_dispatch_control_fence = ?");
        List<Object> values = new ArrayList<>(List.of(
                state, outcome, UUID.randomUUID(), receiptDigest,
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(),
                mode));
        values.add(evidenceMode ? trustedEvidence.evidenceDigest() : null);
        values.add(outcome);
        values.add(now);
        values.add(now);
        values.add(run.run().tenantId());
        values.add(run.run().runId());
        values.add(prepared.itemPublicId());
        values.add(prepared.compensationIdempotencyKey());
        values.add(prepared.compensationRequestDigest());
        values.add(reconciler.authorityFenceEpoch());
        if (!evidenceMode) values.add(reconciler.controlFenceEpoch());
        int updated = jdbc.update(sql, values.toArray());
        if (updated != 1) {
            throw conflict("AUTOMATION_COMPENSATION_FINALIZE_CAS_CONFLICT");
        }
        if ("RESULT_UNKNOWN".equals(outcome)) {
            jdbc.update("""
                    UPDATE sys_automation_run_attempts
                       SET reconciliation_required_at = ?,
                           reconciliation_required_reason =
                               'RESULT_UNKNOWN_EVIDENCE_REQUIRED'
                     WHERE tenant_id = ? AND run_attempt_id = ?
                    """, now, run.run().tenantId(), run.runAttemptId());
        }
        return lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }

    @Override
    public RuntimeRun appendCheckpointAndRecalculate(
            RuntimeRun run,
            LeaseGrant authority,
            String processedItemDigest,
            String trustedReceiptDigest,
            OffsetDateTime now) {
        if (authority.authorityKind() == AutomationDomainPolicy.AuthorityKind.WORKER) {
            requireWorkerFence(run, authority, now);
        } else if (authority.authorityKind()
                == AutomationDomainPolicy.AuthorityKind.RECONCILER) {
            requireReconcilerFence(run, authority, now);
            if (trustedReceiptDigest == null) {
                throw conflict("AUTOMATION_RECOVERY_CHECKPOINT_EVIDENCE_REQUIRED");
            }
        } else {
            throw conflict("AUTOMATION_CHECKPOINT_AUTHORITY_REJECTED");
        }
        Long prior = jdbc.queryForObject("""
                SELECT GREATEST(a.resume_after_checkpoint_ordinal,
                           COALESCE(MAX(c.checkpoint_ordinal), 0))
                  FROM sys_automation_run_attempts a
                  LEFT JOIN sys_automation_run_checkpoints c
                    ON c.tenant_id = a.tenant_id
                   AND c.run_attempt_id = a.run_attempt_id
                 WHERE a.tenant_id = ? AND a.run_attempt_id = ?
                 GROUP BY a.resume_after_checkpoint_ordinal
                """, Long.class, run.run().tenantId(), run.runAttemptId());
        long ordinal = (prior == null ? 0 : prior) + 1;
        Long processed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_item_attempts
                 WHERE tenant_id = ? AND run_id = ? AND attempt_ordinal = ?
                   AND completed_at IS NOT NULL
                """, Long.class, run.run().tenantId(), run.run().runId(),
                run.attemptNumber());
        if (processedItemDigest == null
                || !processedItemDigest.matches("^[a-f0-9]{64}$")
                || (trustedReceiptDigest != null
                && !trustedReceiptDigest.matches("^[a-f0-9]{64}$"))) {
            throw conflict("AUTOMATION_CHECKPOINT_DIGEST_INVALID");
        }
        if (authority.authorityKind() == AutomationDomainPolicy.AuthorityKind.WORKER) {
            String canonicalProcessedItemDigest = currentAttemptReceiptSet(
                    run.run().tenantId(), run.run().runId(), run.run().publicId(),
                    run.attemptNumber(), run.run().objectRevision()).digest();
            if (!processedItemDigest.equals(canonicalProcessedItemDigest)) {
                throw conflict("AUTOMATION_CHECKPOINT_PROCESSED_DIGEST_MISMATCH");
            }
        }
        ReceiptSet receiptSet = currentReceiptSet(
                run.run().tenantId(), run.run().runId(), run.run().publicId(),
                run.run().objectRevision() + 1);
        jdbc.update("""
                INSERT INTO sys_automation_run_checkpoints (
                    tenant_id, run_id, run_attempt_id, attempt_number,
                    checkpoint_ordinal, processed_item_count,
                    processed_item_digest, output_digest, writer_authority_kind,
                    authority_fence_epoch, control_fence_epoch,
                    trusted_effect_receipt_digest, committed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.run().tenantId(), run.run().runId(), run.runAttemptId(),
                run.attemptNumber(), ordinal, processed == null ? 0 : processed,
                processedItemDigest, receiptSet.digest(),
                authority.authorityKind().name(), authority.authorityFenceEpoch(),
                authority.controlFenceEpoch(), trustedReceiptDigest, now);
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'SUCCEEDED') AS succeeded,
                       COUNT(*) FILTER (WHERE lifecycle_state IN
                           ('FAILED_RETRYABLE', 'FAILED_FINAL', 'DEAD_LETTER')) AS failed,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'FAILED_RETRYABLE')
                           AS retryable,
                       COUNT(*) FILTER (WHERE effect_state IN
                           ('PREPARED', 'RESULT_UNKNOWN')) AS uncertain,
                       COUNT(*) FILTER (WHERE compensation_state IN
                           ('PENDING', 'RUNNING', 'RESULT_UNKNOWN')) AS compensation_pending
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, run.run().tenantId(), run.run().runId());
        long total = ((Number) counts.get("total")).longValue();
        long succeeded = ((Number) counts.get("succeeded")).longValue();
        long failed = ((Number) counts.get("failed")).longValue();
        long retryable = ((Number) counts.get("retryable")).longValue();
        long uncertain = ((Number) counts.get("uncertain")).longValue();
        long compensationPending = ((Number) counts.get("compensation_pending")).longValue();
        Boolean cancellationRequested = jdbc.queryForObject("""
                SELECT cancellation_requested_at IS NOT NULL
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ?
                """, Boolean.class, run.run().tenantId(), run.run().runId());
        RunState state;
        if (receiptSet.itemCount() != total) {
            throw conflict("AUTOMATION_RECEIPT_SET_CARDINALITY_MISMATCH");
        }
        if (uncertain > 0 || compensationPending > 0) {
            state = RunState.RUNNING;
        } else if (Boolean.TRUE.equals(cancellationRequested)) {
            state = RunState.CANCELLED;
        } else if (succeeded + failed < total) {
            state = RunState.QUEUED;
        } else if (total == succeeded) {
            state = RunState.SUCCEEDED;
        } else if (retryable > 0 && run.attemptNumber() < run.run().maxAttempts()) {
            state = succeeded > 0 ? RunState.PARTIAL : RunState.FAILED;
        } else {
            state = RunState.DEAD_LETTER;
        }
        boolean finalState = List.of(
                RunState.SUCCEEDED, RunState.CANCELLED, RunState.DEAD_LETTER)
                .contains(state);
        boolean reconciliationRequired = uncertain > 0 || compensationPending > 0;
        if (finalState && receiptSet.terminalCount() != total) {
            throw conflict("AUTOMATION_TERMINAL_RECEIPT_SET_INCOMPLETE");
        }
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs
                   SET lifecycle_state = ?, item_total = ?, item_succeeded = ?,
                       item_failed = ?, output_digest = ?,
                       object_revision = object_revision + 1,
                       completed_at = CASE WHEN ? THEN ? ELSE NULL END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_snapshot = NULL,
                       worker_control_fence_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_snapshot = NULL,
                       reconciliation_control_fence_snapshot = NULL,
                       reconciliation_required_reason = CASE WHEN ?
                           THEN reconciliation_required_reason ELSE NULL END,
                       updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                """, state.name(), total, succeeded, failed, receiptSet.digest(),
                finalState, now, reconciliationRequired, now,
                run.run().tenantId(), run.run().runId(),
                run.run().objectRevision());
        if (aggregate != 1) throw conflict("AUTOMATION_RUN_CHECKPOINT_CAS_CONFLICT");
        jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET lifecycle_state = ?, completed_at = CASE WHEN ? <> 'RUNNING'
                           THEN ? ELSE completed_at END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_epoch_snapshot = NULL,
                       worker_control_fence_epoch_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_epoch_snapshot = NULL,
                       reconciliation_control_fence_epoch_snapshot = NULL,
                       reconciliation_required_at = CASE WHEN ?
                           THEN reconciliation_required_at ELSE NULL END,
                       reconciliation_required_reason = CASE WHEN ?
                           THEN reconciliation_required_reason ELSE NULL END
                 WHERE tenant_id = ? AND run_attempt_id = ?
                """, state.name(), state.name(), now,
                reconciliationRequired, reconciliationRequired,
                run.run().tenantId(), run.runAttemptId());
        return lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }

    @Override
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
        RunRecord run = readRun(tenantId, runPublicId);
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

    private RunActionEligibility runActionEligibility(RunRecord run) {
        long retryable = 0;
        if (List.of(RunState.PARTIAL, RunState.FAILED).contains(run.state())
                && run.retryMode() != RetryMode.NONE
                && run.currentAttempt() < run.maxAttempts()
                && run.maxAttempts() <= 20) {
            try {
                retrySelection(run);
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

    @Override
    public void appendRunEventAndAudit(
            CommandSeal command,
            RunRecord run,
            String transition,
            OffsetDateTime now) {
        validateRunEvent(run);
        JsonNode payload = json.valueToTree(new RunEventPayload(
                run.publicId(), run.state(), run.itemTotal(),
                run.itemSucceeded(), run.itemFailed(), run.outputDigest()));
        String payloadText = canonical.text(payload);
        String payloadDigest = AutomationDomainPolicy.sha256(
                payloadText.getBytes(StandardCharsets.UTF_8));
        UUID eventId = UUID.nameUUIDFromBytes((command.tenantId() + ":"
                + run.publicId() + ":" + run.objectRevision())
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO sys_domain_event_outbox (
                    outbox_id, event_id, spec_version, event_source, event_type,
                    schema_version, subject, tenant_id, aggregate_type,
                    aggregate_id, aggregate_sequence, correlation_id, payload,
                    payload_sha256, status, available_at, created_at, updated_at)
                VALUES (?, ?, '1.0', ?, ?, 1, NULL, ?, 'HRIS_AUTOMATION_RUN',
                        ?, ?, ?, ?::jsonb, ?, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), eventId, EVENT_SOURCE, EVENT_TYPE,
                command.tenantId(), run.publicId().toString(),
                run.objectRevision(), command.correlationId().toString(),
                payloadText, payloadDigest, now, now, now);
        JsonNode audit = json.valueToTree(new AuditPayload(
                "SYS-006", transition, run.publicId(), run.objectRevision(),
                run.state(), command.correlationId(), payloadDigest));
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), command.tenantId(),
                stringify(audit), now, now, now);
    }

    private void validateRunEvent(RunRecord run) {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'SUCCEEDED') AS succeeded,
                       COUNT(*) FILTER (WHERE lifecycle_state IN
                           ('FAILED_RETRYABLE', 'FAILED_FINAL', 'DEAD_LETTER')) AS failed,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'FAILED_RETRYABLE')
                           AS retryable,
                       COUNT(*) FILTER (WHERE effect_state IN
                           ('PREPARED', 'RESULT_UNKNOWN')) AS uncertain,
                       COUNT(*) FILTER (WHERE compensation_state IN
                           ('PENDING', 'RUNNING', 'RESULT_UNKNOWN')) AS compensation_pending
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, run.tenantId(), run.runId());
        long total = ((Number) counts.get("total")).longValue();
        long succeeded = ((Number) counts.get("succeeded")).longValue();
        long failed = ((Number) counts.get("failed")).longValue();
        long retryable = ((Number) counts.get("retryable")).longValue();
        long uncertain = ((Number) counts.get("uncertain")).longValue();
        long compensationPending = ((Number) counts.get("compensation_pending"))
                .longValue();
        if (run.itemTotal() != total || run.itemSucceeded() != succeeded
                || run.itemFailed() != failed || succeeded + failed > total) {
            throw conflict("AUTOMATION_RUN_EVENT_COUNT_MISMATCH");
        }
        boolean validState = switch (run.state()) {
            case QUEUED, LEASED, RUNNING -> succeeded + failed <= total;
            case SUCCEEDED -> succeeded == total && failed == 0;
            case PARTIAL -> succeeded > 0 && failed > 0
                    && succeeded + failed == total && retryable > 0
                    && run.currentAttempt() < run.maxAttempts();
            case FAILED -> succeeded == 0 && failed == total && total > 0
                    && retryable > 0 && run.currentAttempt() < run.maxAttempts();
            case CANCELLED -> uncertain == 0 && compensationPending == 0;
            case DEAD_LETTER -> failed > 0 && succeeded + failed == total
                    && (retryable == 0 || run.currentAttempt() >= run.maxAttempts());
        };
        if (!validState) {
            throw conflict("AUTOMATION_RUN_EVENT_STATE_INVALID");
        }
        boolean terminal = List.of(
                RunState.SUCCEEDED, RunState.CANCELLED, RunState.DEAD_LETTER)
                .contains(run.state());
        if (run.outputDigest() != null || terminal) {
            ReceiptSet receiptSet = currentReceiptSet(
                    run.tenantId(), run.runId(), run.publicId(), run.objectRevision());
            if (!receiptSet.digest().equals(run.outputDigest())
                    || receiptSet.itemCount() != total
                    || (terminal && receiptSet.terminalCount() != total)) {
                throw conflict("AUTOMATION_RUN_EVENT_OUTPUT_DIGEST_INVALID");
            }
        }
    }

    private VersionRecord version(ResultSet rs, int row) throws SQLException {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                rs.getString("handler_key"),
                rs.getString("handler_owner_service"),
                rs.getString("handler_contract_version"),
                rs.getString("input_schema_ref"),
                rs.getString("receipt_schema_ref"),
                rs.getString("handler_signature_digest"),
                textArray(rs, "allowed_scope_types"),
                rs.getString("manifest_retry_mode"),
                rs.getInt("manifest_max_attempts"),
                rs.getString("handler_manifest_state"));
        SignedManifest signed = new SignedManifest(
                manifest, rs.getString("handler_signature_key_id"),
                rs.getString("input_schema_digest"),
                rs.getString("receipt_schema_digest"),
                rs.getString("compensation_policy_digest"),
                rs.getLong("handler_manifest_revision"));
        return new VersionRecord(
                uuid(rs, "version_id"), rs.getLong("tenant_id"),
                uuid(rs, "definition_id"), uuid(rs, "definition_public_id"),
                rs.getLong("version_number"),
                VersionState.valueOf(rs.getString("lifecycle_state")),
                readJson(rs.getString("definition_payload")),
                rs.getString("definition_digest"),
                readJson(rs.getString("execution_policy")),
                rs.getString("execution_policy_digest"),
                rs.getString("manual_concurrency_key_template"),
                rs.getString("compatibility_baseline_digest"), signed,
                rs.getLong("subject_revision"), rs.getLong("object_revision"),
                uuid(rs, "author_actor_ref"), uuidNullable(rs, "approved_by_actor_ref"),
                rs.getInt("manifest_max_attempts"),
                RetryMode.valueOf(rs.getString("manifest_retry_mode")));
    }

    private RuntimeRun runtimeRun(ResultSet rs) throws SQLException {
        RunRecord run = RUN_MAPPER.mapRow(rs, 0);
        return new RuntimeRun(
                run, signedManifest(rs), rs.getString("definition_digest"),
                rs.getString("execution_policy_digest"),
                rs.getString("compatibility_baseline_digest"),
                rs.getString("scope_type"), uuidNullable(rs, "scope_id"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getString("requested_by_actor_ref"),
                rs.getString("idempotency_key"), rs.getString("input_ref"),
                rs.getString("input_digest"), rs.getInt("expected_item_count"),
                uuid(rs, "correlation_id"), rs.getInt("attempt_number"),
                uuid(rs, "run_attempt_id"), rs.getString("selected_item_digest"),
                rs.getLong("selected_item_count"),
                rs.getString("compensation_policy_ref"),
                rs.getString("compensation_policy_digest"),
                rs.getLong("a_worker_fence_epoch"),
                uuidNullable(rs, "a_worker_lease_token"),
                rs.getObject("a_worker_leased_until", OffsetDateTime.class),
                nullableLong(rs, "a_worker_control"),
                rs.getLong("a_recon_fence_epoch"),
                uuidNullable(rs, "a_recon_lease_token"),
                rs.getObject("a_recon_leased_until", OffsetDateTime.class),
                nullableLong(rs, "a_recon_control"),
                rs.getLong("control_fence_epoch"),
                rs.getObject("a_recon_required_at", OffsetDateTime.class),
                rs.getString("a_recon_reason"));
    }

    private SignedManifest signedManifest(ResultSet rs) throws SQLException {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                rs.getString("handler_key"),
                rs.getString("handler_owner_service"),
                rs.getString("handler_contract_version"),
                rs.getString("input_schema_ref"),
                rs.getString("receipt_schema_ref"),
                rs.getString("handler_signature_digest"),
                textArray(rs, "allowed_scope_types"),
                rs.getString("manifest_retry_mode"),
                rs.getInt("manifest_max_attempts"),
                rs.getString("handler_manifest_state"));
        return new SignedManifest(
                manifest, rs.getString("handler_signature_key_id"),
                rs.getString("input_schema_digest"),
                rs.getString("receipt_schema_digest"),
                rs.getString("compensation_policy_digest"),
                rs.getLong("handler_manifest_revision"));
    }

    private static AutomationInvocationV1 invocation(RuntimeRun run) {
        AutomationHandlerManifestV1 manifest = run.manifest().manifest();
        return new AutomationInvocationV1(
                run.run().publicId(), run.run().definitionPublicId(),
                run.run().versionNumber(), manifest.handlerKey(),
                manifest.contractVersion(), run.scopeType(), run.scopeId(),
                run.requestedAt().toInstant(), run.requestedByActorRef(),
                run.idempotencyKey(), run.inputReference(), run.inputDigest(),
                run.expectedItemCount(), run.correlationId());
    }

    private static void requireWorkerFence(
            RuntimeRun run, LeaseGrant worker, OffsetDateTime now) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.WORKER,
                run.workerLease(), run.reconcilerLease(), worker.token(),
                worker.authorityFenceEpoch(), worker.controlFenceEpoch(), now,
                false, "ORIGINAL");
    }

    private static void requireReconcilerFence(
            RuntimeRun run, LeaseGrant reconciler, OffsetDateTime now) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.RECONCILER,
                run.workerLease(), run.reconcilerLease(), reconciler.token(),
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(), now,
                false, "COMPENSATION");
    }

    private static String selectionDigest(List<UUID> itemIds) {
        String material = itemIds.stream().map(UUID::toString).sorted()
                .collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
        return AutomationDomainPolicy.sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String mapReceiptState(AutomationRunItemReceiptV1 receipt) {
        if ("FAILED".equals(receipt.state())) {
            return receipt.retryable() ? "FAILED_RETRYABLE" : "FAILED_FINAL";
        }
        return switch (receipt.state()) {
            case "CANCELED" -> "CANCELLED";
            case "DEAD_LETTERED" -> "DEAD_LETTER";
            default -> receipt.state();
        };
    }

    private static String canonicalReceiptState(String receiptState) {
        return switch (receiptState) {
            case "FAILED_RETRYABLE", "FAILED_FINAL" -> "FAILED";
            case "CANCELLED" -> "CANCELED";
            case "DEAD_LETTER" -> "DEAD_LETTERED";
            default -> receiptState;
        };
    }

    private static final RowMapper<DefinitionRecord> DEFINITION_MAPPER =
            (rs, row) -> new DefinitionRecord(
                    uuid(rs, "definition_id"), uuid(rs, "public_id"),
                    rs.getLong("tenant_id"), rs.getString("automation_key"),
                    OwnerDomain.valueOf(rs.getString("owner_domain")),
                    rs.getString("lifecycle_state"),
                    nullableLong(rs, "active_version_number"),
                    rs.getLong("object_revision"), uuid(rs, "author_actor_ref"));

    private static final RowMapper<ScheduleRecord> SCHEDULE_MAPPER =
            (rs, row) -> new ScheduleRecord(
                    uuid(rs, "schedule_id"), uuid(rs, "public_id"),
                    rs.getLong("tenant_id"), uuid(rs, "definition_id"),
                    uuid(rs, "definition_public_id"), uuid(rs, "version_id"),
                    rs.getLong("pinned_version_number"),
                    ScheduleState.valueOf(rs.getString("lifecycle_state")),
                    rs.getString("schedule_expression"), rs.getString("timezone"),
                    rs.getString("timezone_database_revision"),
                    rs.getString("schedule_policy_digest"),
                    rs.getLong("subject_revision"), rs.getLong("object_revision"),
                    rs.getLong("fire_cursor_revision"),
                    rs.getObject("next_fire_at", OffsetDateTime.class),
                    uuid(rs, "author_actor_ref"), uuidNullable(rs, "approved_by_actor_ref"));

    private static final RowMapper<RunRecord> RUN_MAPPER =
            (rs, row) -> new RunRecord(
                    uuid(rs, "run_id"), uuid(rs, "public_id"),
                    rs.getLong("tenant_id"), uuid(rs, "definition_id"),
                    uuid(rs, "definition_public_id"), uuid(rs, "version_id"),
                    rs.getLong("version_number"),
                    RunState.valueOf(rs.getString("lifecycle_state")),
                    SelectionMode.valueOf(rs.getString("selection_mode")),
                    rs.getString("concurrency_key_digest"),
                    rs.getInt("current_attempt"), rs.getInt("max_attempts"),
                    RetryMode.valueOf(rs.getString("retry_mode")),
                    rs.getLong("item_total"), rs.getLong("item_succeeded"),
                    rs.getLong("item_failed"), rs.getString("output_digest"),
                    rs.getLong("object_revision"),
                    rs.getString("reconciliation_required_reason"));

    private StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        Long aggregateRevision = nullableLong(rs, "aggregate_revision");
        return new StoredReceipt(rs.getString("request_digest"), new CommandReceipt(
                uuid(rs, "public_id"), rs.getString("command_type"),
                rs.getString("originating_action"),
                uuid(rs, "subject_principal_public_id"),
                rs.getString("population_scope_digest"),
                rs.getLong("field_policy_revision"), rs.getString("purpose_code"),
                rs.getLong("authorization_revision"),
                ReceiptStatus.valueOf(rs.getString("status")),
                uuid(rs, "correlation_id"), rs.getString("request_digest"),
                rs.getObject("created_at", OffsetDateTime.class),
                aggregateRevision, rs.getString("result_ref")));
    }

    private CommandReceipt receipt(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            OffsetDateTime createdAt,
            Long revision,
            String resultRef) {
        return new CommandReceipt(
                receiptId, command.commandType(), command.originatingAction(),
                command.subjectPrincipalPublicId(), command.populationScopeDigest(),
                command.fieldPolicyRevision(), command.purposeCode(),
                command.authorizationRevision(), status, command.correlationId(),
                command.requestDigest(), createdAt, revision, resultRef);
    }

    private JsonNode readJson(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted automation JSON is invalid", exception);
        }
    }

    private String stringify(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Automation JSON is invalid", exception);
        }
    }

    private ReceiptSet currentReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            long runRevision) {
        List<ReceiptDigestItem> receipts = jdbc.query("""
                SELECT i.public_id, i.item_sequence, a.receipt_state,
                       a.attempt_ordinal, a.result_digest, a.error_code,
                       i.compensation_receipt_digest
                  FROM sys_automation_run_items i
                  JOIN LATERAL (
                       SELECT ia.receipt_state, ia.attempt_ordinal,
                              ia.result_digest, ia.error_code
                         FROM sys_automation_item_attempts ia
                        WHERE ia.tenant_id = i.tenant_id
                          AND ia.run_id = i.run_id
                          AND ia.run_item_id = i.run_item_id
                          AND ia.run_revision_snapshot <= ?
                        ORDER BY ia.run_revision_snapshot DESC,
                                 ia.attempt_ordinal DESC
                        LIMIT 1
                  ) a ON TRUE
                 WHERE i.tenant_id = ? AND i.run_id = ?
                 ORDER BY i.item_sequence, i.public_id
                """, (rs, row) -> new ReceiptDigestItem(
                uuid(rs, "public_id"), rs.getLong("item_sequence"),
                rs.getString("receipt_state"), rs.getInt("attempt_ordinal"),
                rs.getString("result_digest"), rs.getString("error_code"),
                rs.getString("compensation_receipt_digest")),
                runRevision, tenantId, runId);
        List<Map<String, Object>> items = receipts.stream().map(receipt -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemPublicId", receipt.itemPublicId());
            item.put("itemSequence", Long.toString(receipt.itemSequence()));
            item.put("receiptState", receipt.receiptState());
            item.put("attemptNumber", receipt.attemptNumber());
            item.put("resultDigest", receipt.resultDigest());
            item.put("errorCode", receipt.errorCode());
            item.put("compensationReceiptDigest",
                    receipt.compensationReceiptDigest());
            return item;
        }).toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("runPublicId", runPublicId);
        document.put("runRevision", Long.toString(runRevision));
        document.put("items", items);
        long terminal = receipts.stream().filter(receipt -> List.of(
                "SUCCEEDED", "FAILED_RETRYABLE", "FAILED_FINAL", "CANCELLED",
                "DEAD_LETTER").contains(receipt.receiptState())).count();
        return new ReceiptSet(canonical.digest(document), receipts.size(), terminal);
    }

    private ReceiptSet currentAttemptReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            int attemptNumber,
            long runRevision) {
        List<ReceiptDigestItem> receipts = jdbc.query("""
                SELECT i.public_id, i.item_sequence, a.receipt_state,
                       a.attempt_ordinal, a.result_digest, a.error_code,
                       NULL::TEXT AS compensation_receipt_digest
                  FROM sys_automation_run_items i
                  JOIN sys_automation_item_attempts a
                    ON a.tenant_id = i.tenant_id
                   AND a.run_id = i.run_id
                   AND a.run_item_id = i.run_item_id
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND a.attempt_ordinal = ?
                   AND a.run_revision_snapshot = ?
                   AND a.completed_at IS NOT NULL
                 ORDER BY i.item_sequence, i.public_id
                """, (rs, row) -> new ReceiptDigestItem(
                uuid(rs, "public_id"), rs.getLong("item_sequence"),
                rs.getString("receipt_state"), rs.getInt("attempt_ordinal"),
                rs.getString("result_digest"), rs.getString("error_code"),
                rs.getString("compensation_receipt_digest")),
                tenantId, runId, attemptNumber, runRevision);
        List<Map<String, Object>> items = receipts.stream().map(receipt -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemPublicId", receipt.itemPublicId());
            item.put("itemSequence", Long.toString(receipt.itemSequence()));
            item.put("receiptState", receipt.receiptState());
            item.put("attemptNumber", receipt.attemptNumber());
            item.put("resultDigest", receipt.resultDigest());
            item.put("errorCode", receipt.errorCode());
            item.put("compensationReceiptDigest",
                    receipt.compensationReceiptDigest());
            return item;
        }).toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("runPublicId", runPublicId);
        document.put("runRevision", Long.toString(runRevision));
        document.put("items", items);
        long terminal = receipts.stream().filter(receipt -> List.of(
                "SUCCEEDED", "FAILED_RETRYABLE", "FAILED_FINAL", "CANCELLED",
                "DEAD_LETTER").contains(receipt.receiptState())).count();
        return new ReceiptSet(canonical.digest(document), receipts.size(), terminal);
    }

    private static List<String> textArray(ResultSet rs, String name)
            throws SQLException {
        Array array = rs.getArray(name);
        if (array == null) return List.of();
        return List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private static UUID uuidNullable(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name) == null ? null : rs.getObject(name, UUID.class);
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name) == null ? null : rs.getLong(name);
    }

    private static String emptySelectionDigest(UUID runPublicId) {
        return AutomationDomainPolicy.sha256(("{\"items\":[],\"runPublicId\":\""
                + runPublicId + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    private record StoredReceipt(String requestDigest, CommandReceipt receipt) {
    }

    private record CatalogRow(String sortTuple, CatalogItem item) {
    }

    private record RunActionEligibility(
            long retryEligibleItemCount,
            CancelEligibility cancelEligibility) {
    }

    private record RetryItem(
            UUID runItemId,
            UUID publicId,
            String state,
            String resultDigest,
            String errorCode,
            UUID priorAttemptId,
            String effectState,
            String effectReceiptDigest,
            UUID correlationId) {
    }

    private record ReceiptDigestItem(
            UUID itemPublicId,
            long itemSequence,
            String receiptState,
            int attemptNumber,
            String resultDigest,
            String errorCode,
            String compensationReceiptDigest) {
    }

    private record ReceiptSet(String digest, long itemCount, long terminalCount) {
    }

    private record RunEventPayload(
            UUID runPublicId,
            RunState state,
            long itemTotal,
            long itemSucceeded,
            long itemFailed,
            String outputDigest) {
    }

    private record AuditPayload(
            String slice,
            String transition,
            UUID runPublicId,
            long runRevision,
            RunState state,
            UUID correlationId,
            String eventPayloadDigest) {
    }
}
