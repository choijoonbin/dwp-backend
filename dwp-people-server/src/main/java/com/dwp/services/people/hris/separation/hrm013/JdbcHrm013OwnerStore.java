package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.services.people.hris.separation.hrm013.Hrm013OwnerContracts.CaseCreation;
import com.dwp.services.people.hris.separation.hrm013.Hrm013OwnerContracts.PlannedDispatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant-scoped aggregate CAS for the V61 separation owner. */
public final class JdbcHrm013OwnerStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate mandatory;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcHrm013OwnerStore(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            ObjectMapper mapper, Clock clock) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        mandatory = new TransactionTemplate(manager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    public OwnerTask requireTaskForUpdate(
            long tenantId, UUID casePublicId, UUID taskPublicId) {
        OwnerTask result = mandatory.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, tenantId);
            List<OwnerTask> rows = jdbc.query("""
                    SELECT c.separation_case_id,c.public_id case_public_id,
                           c.lifecycle_state case_state,c.maker_principal_public_id,
                           c.correlation_id,c.version case_version,
                           t.separation_task_id,t.public_id task_public_id,t.task_type,
                           t.owner_workload_id,t.lifecycle_state task_state,
                           t.owner_revision,t.payload_hash,t.version task_version
                      FROM ppl_separation_cases c
                      JOIN ppl_separation_tasks t ON t.tenant_id=c.tenant_id
                       AND t.separation_case_id=c.separation_case_id
                     WHERE c.tenant_id=:tenant AND c.public_id=:casePublic
                       AND t.public_id=:taskPublic
                     FOR UPDATE OF c,t
                    """, identity(tenantId, casePublicId, taskPublicId),
                    (row, ignored) -> new OwnerTask(
                            tenantId, row.getLong("separation_case_id"),
                            row.getObject("case_public_id", UUID.class),
                            row.getString("case_state"),
                            row.getObject("maker_principal_public_id", UUID.class),
                            row.getObject("correlation_id", UUID.class),
                            row.getLong("case_version"),
                            row.getLong("separation_task_id"),
                            row.getObject("task_public_id", UUID.class),
                            row.getString("task_type"), row.getString("owner_workload_id"),
                            row.getString("task_state"),
                            nullableLong(row, "owner_revision"),
                            row.getString("payload_hash"), row.getLong("task_version")));
            if (rows.size() != 1) throw Hrm013SafeErrorV1.notFound();
            return rows.getFirst();
        });
        if (result == null) throw Hrm013SafeErrorV1.notFound();
        return result;
    }

    /** Returns true only when the complete root command already exists byte-for-byte. */
    public boolean createCaseAndTasks(
            CaseCreation command,
            List<PlannedDispatch> plans) {
        Hrm013TenantScope.setLocal(jdbc, command.tenantId());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))",
                new MapSqlParameterSource().addValue("value", "hrm013-case\n"
                        + command.tenantId() + "\n" + command.casePublicId()), row -> null);
        List<java.util.Map<String, Object>> existing = jdbc.queryForList("""
                SELECT separation_case_id,worker_id,work_relationship_id,
                       separation_type,reason_code,effective_date,last_working_date,
                       lifecycle_state,input_snapshot_hash,task_template_public_id,
                       task_template_revision,task_template_hash,
                       maker_principal_public_id,correlation_id,version,created_by
                  FROM ppl_separation_cases
                 WHERE tenant_id=:tenant AND public_id=:casePublic FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", command.tenantId())
                .addValue("casePublic", command.casePublicId()));
        if (!existing.isEmpty()) {
            long existingCaseId = ((Number) existing.getFirst()
                    .get("separation_case_id")).longValue();
            if (existing.size() != 1 || !sameCase(existing.getFirst(), command)
                    || !sameTasks(command.tenantId(), existingCaseId, plans)) {
                throw Hrm013SafeErrorV1.digestConflict();
            }
            return true;
        }
        Long caseId = jdbc.queryForObject("""
                INSERT INTO ppl_separation_cases(
                    public_id,tenant_id,worker_id,work_relationship_id,separation_type,
                    reason_code,effective_date,last_working_date,lifecycle_state,
                    input_snapshot_hash,task_template_public_id,task_template_revision,
                    task_template_hash,maker_principal_public_id,correlation_id,version,
                    created_at,created_by,updated_at,updated_by)
                VALUES(:casePublic,:tenant,:worker,:relationship,:type,:reason,
                    :effective,:lastWorking,'DRAFT',:inputHash,:templatePublic,
                    :templateRevision,:templateHash,:maker,:correlation,0,
                    :created,:actor,:created,:actor)
                RETURNING separation_case_id
                """, caseParameters(command), Long.class);
        if (caseId == null) throw Hrm013SafeErrorV1.invalidState();
        for (PlannedDispatch plan : plans) {
            int inserted = jdbc.update("""
                    INSERT INTO ppl_separation_tasks(
                        public_id,tenant_id,separation_case_id,task_type,
                        owner_workload_id,mandatory,irreversible,lifecycle_state,
                        version,created_at,created_by,updated_at,updated_by)
                    VALUES(:taskPublic,:tenant,:caseId,:taskType,:owner,
                        :mandatory,:irreversible,'PENDING',0,:created,:actor,:created,:actor)
                    """, new MapSqlParameterSource()
                    .addValue("taskPublic", plan.body().taskPublicId())
                    .addValue("tenant", command.tenantId()).addValue("caseId", caseId)
                    .addValue("taskType", plan.profile().taskType())
                    .addValue("owner", plan.profile().ownerWorkloadId())
                    .addValue("mandatory", plan.mandatory())
                    .addValue("irreversible", plan.irreversible())
                    .addValue("created", Timestamp.from(command.createdAt()))
                    .addValue("actor", command.actorId()));
            if (inserted != 1) throw Hrm013SafeErrorV1.invalidState();
        }
        int activated = jdbc.update("""
                UPDATE ppl_separation_cases
                   SET lifecycle_state='TASKS_ACTIVE',version=1,
                       updated_at=:created,updated_by=:actor
                 WHERE tenant_id=:tenant AND separation_case_id=:caseId
                   AND lifecycle_state='DRAFT' AND version=0
                """, caseParameters(command).addValue("caseId", caseId));
        if (activated != 1) throw Hrm013SafeErrorV1.revisionConflict();
        return false;
    }

    public long requireCaseIdForUpdate(long tenantId, UUID casePublicId) {
        Long result = mandatory.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, tenantId);
            List<Long> rows = jdbc.queryForList("""
                    SELECT separation_case_id FROM ppl_separation_cases
                     WHERE tenant_id=:tenant AND public_id=:casePublic FOR UPDATE
                    """, new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("casePublic", casePublicId), Long.class);
            if (rows.size() != 1) throw Hrm013SafeErrorV1.notFound();
            return rows.getFirst();
        });
        if (result == null) throw Hrm013SafeErrorV1.notFound();
        return result;
    }

    public Instant requireDispatchOccurredAt(long tenantId, UUID dispatchEventId) {
        Hrm013TenantScope.setLocal(jdbc, tenantId);
        List<Instant> rows = jdbc.query("""
                SELECT occurred_at FROM sys_people_outbox_events
                 WHERE tenant_id=:tenant AND event_id=:event
                   AND aggregate_type='SEPARATION_TASK'
                 FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("event", dispatchEventId),
                (row, ignored) -> row.getTimestamp(1).toInstant());
        if (rows.size() != 1) throw Hrm013SafeErrorV1.authorityDenied();
        return rows.getFirst();
    }

    public void requireDatabaseTimeBefore(long tenantId, Instant deadline) {
        Hrm013TenantScope.setLocal(jdbc, tenantId);
        Boolean before = jdbc.queryForObject(
                "SELECT pg_catalog.clock_timestamp() < :deadline",
                new MapSqlParameterSource()
                        .addValue("deadline", Timestamp.from(deadline)), Boolean.class);
        if (!Boolean.TRUE.equals(before)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    public Optional<SeparationReconciliationPort.Result>
            findReconciliationAdmissionForUpdate(
                    SeparationReconciliationPort.Command command,
                    long tenantGrantRevision, long authorizationRevision) {
        Hrm013TenantScope.setLocal(jdbc, command.tenantId());
        lockReconciliation(command.tenantId(), command.consumerEventId());
        List<String> rows = jdbc.queryForList("""
                SELECT after_snapshot::text FROM sys_people_audit_events
                 WHERE tenant_id=:tenant
                   AND action='HRM013_RECONCILIATION_ADMITTED'
                   AND target_id=:event
                 ORDER BY occurred_at,audit_event_id FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", command.tenantId())
                .addValue("event", command.consumerEventId().toString()), String.class);
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) throw Hrm013SafeErrorV1.invalidState();
        try {
            var value = mapper.readTree(rows.getFirst());
            boolean same = command.casePublicId().toString().equals(
                    value.path("casePublicId").textValue())
                    && command.sourceWorkloadId().equals(
                    value.path("sourceWorkloadId").textValue())
                    && command.sourcePayloadSha256().equals(
                    value.path("sourcePayloadSha256").textValue())
                    && tenantGrantRevision
                    == value.path("tenantGrantRevision").longValue()
                    && authorizationRevision
                    == value.path("authorizationRevision").longValue()
                    && command.correlationId().toString().equals(
                    value.path("correlationId").textValue())
                    && command.occurredAt().toString().equals(
                    value.path("occurredAt").textValue());
            if (!same) throw Hrm013SafeErrorV1.digestConflict();
            return Optional.of(new SeparationReconciliationPort.Result(
                    command.consumerEventId(), command.casePublicId(),
                    "REPLAYED", 1, command.correlationId()));
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception malformed) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    public SeparationReconciliationPort.Result appendReconciliationAdmission(
            SeparationReconciliationPort.Command command,
            long tenantGrantRevision, long authorizationRevision,
            Instant admittedAt) {
        String snapshot;
        try {
            snapshot = mapper.writeValueAsString(Map.of(
                    "casePublicId", command.casePublicId().toString(),
                    "sourceWorkloadId", command.sourceWorkloadId(),
                    "sourcePayloadSha256", command.sourcePayloadSha256(),
                    "tenantGrantRevision", tenantGrantRevision,
                    "authorizationRevision", authorizationRevision,
                    "correlationId", command.correlationId().toString(),
                    "occurredAt", command.occurredAt().toString(),
                    "ownerRevision", 1));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("reconciliation serialization failed", failure);
        }
        int inserted = jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:audit,:tenant,'SERVICE','dwp-people-server',
                    'HRM013_RECONCILIATION_ADMITTED','SEPARATION_RECONCILIATION',
                    :event,'SUCCESS',:correlation,CAST(:snapshot AS jsonb),:occurred)
                """, new MapSqlParameterSource().addValue("audit", UUID.randomUUID())
                .addValue("tenant", command.tenantId())
                .addValue("event", command.consumerEventId().toString())
                .addValue("correlation", command.correlationId())
                .addValue("snapshot", snapshot)
                .addValue("occurred", Timestamp.from(admittedAt)));
        if (inserted != 1) throw Hrm013SafeErrorV1.invalidState();
        return new SeparationReconciliationPort.Result(
                command.consumerEventId(), command.casePublicId(),
                "ADMITTED", 1, command.correlationId());
    }

    private void lockReconciliation(long tenantId, UUID consumerEventId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))",
                new MapSqlParameterSource().addValue("value",
                        "hrm013-reconciliation\n" + tenantId + "\n" + consumerEventId),
                row -> null);
    }

    public OwnerTask activateDispatch(
            OwnerTask current, String payloadSha256, long expectedVersion,
            long expectedOwnerRevision) {
        if (current.taskVersion() != expectedVersion
                || expectedOwnerRevision != 1
                || !"PENDING".equals(current.taskState())) {
            throw Hrm013SafeErrorV1.revisionConflict();
        }
        int updated = jdbc.update("""
                UPDATE ppl_separation_tasks
                   SET lifecycle_state='IN_PROGRESS',owner_revision=:ownerRevision,
                       payload_hash=:payload,version=version+1,updated_by=created_by
                 WHERE tenant_id=:tenant AND separation_task_id=:taskId
                   AND version=:version AND lifecycle_state='PENDING'
                   AND owner_revision IS NULL
                """, new MapSqlParameterSource().addValue("tenant", current.tenantId())
                .addValue("taskId", current.separationTaskId())
                .addValue("version", expectedVersion)
                .addValue("ownerRevision", expectedOwnerRevision)
                .addValue("payload", payloadSha256));
        if (updated != 1) throw Hrm013SafeErrorV1.revisionConflict();
        return requireTaskForUpdate(
                current.tenantId(), current.casePublicId(), current.taskPublicId());
    }

    public void applyTaskReceipt(
            OwnerTask current,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 receipt) {
        requireOwnerRevision(current, receipt.ownerRevision(), receipt.originatingPayloadSha256());
        String state = "COMPLETED".equals(receipt.status()) ? "COMPLETED" : "FAILED";
        int updated = jdbc.update("""
                UPDATE ppl_separation_tasks
                   SET lifecycle_state=:state,owner_revision=:ownerRevision,
                       external_receipt_public_id=:externalReceipt,
                       payload_hash=:payload,completed_at=:completed,
                       version=version+1,updated_by=created_by
                 WHERE tenant_id=:tenant AND separation_task_id=:taskId
                   AND version=:version AND lifecycle_state IN ('PENDING','IN_PROGRESS','FAILED')
                """, new MapSqlParameterSource().addValue("state", state)
                .addValue("ownerRevision", receipt.ownerRevision())
                .addValue("externalReceipt", "COMPLETED".equals(state)
                        ? receipt.externalReceiptPublicId() : null)
                .addValue("payload", receipt.payloadSha256())
                .addValue("completed", "COMPLETED".equals(state)
                        ? Timestamp.from(receipt.occurredAt()) : null)
                .addValue("tenant", current.tenantId())
                .addValue("taskId", current.separationTaskId())
                .addValue("version", current.taskVersion()));
        if (updated != 1) throw Hrm013SafeErrorV1.revisionConflict();
        advanceCaseIfReady(current.tenantId(), current.separationCaseId());
    }

    public void applyWaiverDecision(
            OwnerTask current,
            Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 decision) {
        requireOwnerRevision(current, decision.ownerRevision(),
                decision.originatingPayloadSha256());
        if (decision.decisionActorPublicId().equals(current.caseMakerPublicId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        if ("REJECTED".equals(decision.decision())) return;
        int updated = jdbc.update("""
                UPDATE ppl_separation_tasks
                   SET lifecycle_state='WAIVED',owner_revision=:ownerRevision,
                       waiver_decision_event_id=:event,
                       waiver_actor_public_id=:actor,waiver_reason_code=:reason,
                       waiver_approval_request_id=:approval,waiver_decision='APPROVED',
                       waiver_decision_revision=:decisionRevision,
                       waiver_step_up_evidence_id=:stepUp,payload_hash=:payload,
                       completed_at=:completed,version=version+1,updated_by=created_by
                 WHERE tenant_id=:tenant AND separation_task_id=:taskId
                   AND version=:version AND lifecycle_state IN ('PENDING','IN_PROGRESS','FAILED')
                """, new MapSqlParameterSource()
                .addValue("ownerRevision", decision.ownerRevision())
                .addValue("event", decision.callbackEventId())
                .addValue("actor", decision.decisionActorPublicId())
                .addValue("reason", decision.reasonCode())
                .addValue("approval", decision.approvalRequestId())
                .addValue("decisionRevision", decision.decisionRevision())
                .addValue("stepUp", decision.stepUpEvidenceId())
                .addValue("payload", decision.payloadSha256())
                .addValue("completed", Timestamp.from(decision.occurredAt()))
                .addValue("tenant", current.tenantId())
                .addValue("taskId", current.separationTaskId())
                .addValue("version", current.taskVersion()));
        if (updated != 1) throw Hrm013SafeErrorV1.revisionConflict();
        advanceCaseIfReady(current.tenantId(), current.separationCaseId());
    }

    public void applyReconciliation(
            long tenantId,
            Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 result) {
        Hrm013TenantScope.setLocal(jdbc, tenantId);
        List<Long> versions = jdbc.queryForList("""
                SELECT version FROM ppl_separation_cases
                 WHERE tenant_id=:tenant AND public_id=:casePublic FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("casePublic", result.casePublicId()), Long.class);
        if (versions.size() != 1) throw Hrm013SafeErrorV1.notFound();
        if ("CORRECTION_REQUIRED".equals(result.result())) {
            int updated = jdbc.update("""
                    UPDATE ppl_separation_cases
                       SET lifecycle_state='CORRECTION_REQUIRED',version=version+1,
                           updated_by=created_by
                     WHERE tenant_id=:tenant AND public_id=:casePublic
                       AND version=:version AND lifecycle_state='PUBLISHED'
                    """, new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("casePublic", result.casePublicId())
                    .addValue("version", versions.getFirst()));
            if (updated != 1) throw Hrm013SafeErrorV1.invalidState();
        }
    }

    public void quarantine(
            long tenantId, UUID eventId, UUID correlationId,
            String requestBodySha256, String commandDigest) {
        Hrm013TenantScope.setLocal(jdbc, tenantId);
        String snapshot;
        try {
            snapshot = mapper.writeValueAsString(java.util.Map.of(
                    "requestBodySha256", requestBodySha256,
                    "commandDigest", commandDigest));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("quarantine serialization failed", failure);
        }
        jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:audit,:tenant,'SERVICE','dwp-people-server',
                    'HRM013_CALLBACK_DIGEST_CONFLICT','SEPARATION_CALLBACK',:target,
                    'DENIED',:correlation,CAST(:snapshot AS jsonb),:occurred)
                """, new MapSqlParameterSource().addValue("audit", UUID.randomUUID())
                .addValue("tenant", tenantId).addValue("target", eventId.toString())
                .addValue("correlation", correlationId.toString())
                .addValue("snapshot", snapshot)
                .addValue("occurred", Timestamp.from(clock.instant())));
    }

    private void advanceCaseIfReady(long tenantId, long caseId) {
        Integer blocking = jdbc.queryForObject("""
                SELECT count(*) FROM ppl_separation_tasks
                 WHERE tenant_id=:tenant AND separation_case_id=:caseId
                   AND mandatory AND lifecycle_state NOT IN ('COMPLETED','WAIVED')
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("caseId", caseId), Integer.class);
        if (blocking != null && blocking == 0) {
            jdbc.update("""
                    UPDATE ppl_separation_cases
                       SET lifecycle_state='READY_TO_PUBLISH',version=version+1,
                           updated_by=created_by
                     WHERE tenant_id=:tenant AND separation_case_id=:caseId
                       AND lifecycle_state='TASKS_ACTIVE'
                    """, new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("caseId", caseId));
        }
    }

    private static void requireOwnerRevision(
            OwnerTask current, long next, String originatingPayloadSha256) {
        long expected = current.ownerRevision() == null ? 1 : current.ownerRevision();
        if (next != expected || current.payloadSha256() == null
                || !current.payloadSha256().equals(originatingPayloadSha256)) {
            throw Hrm013SafeErrorV1.revisionConflict();
        }
    }

    private static MapSqlParameterSource identity(
            long tenantId, UUID casePublicId, UUID taskPublicId) {
        return new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("casePublic", casePublicId).addValue("taskPublic", taskPublicId);
    }

    private boolean sameTasks(
            long tenantId, long caseId,
            List<PlannedDispatch> plans) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT public_id,task_type,owner_workload_id,mandatory,irreversible
                  FROM ppl_separation_tasks
                 WHERE tenant_id=:tenant AND separation_case_id=:caseId
                 ORDER BY task_type FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("caseId", caseId));
        if (rows.size() != plans.size()) return false;
        java.util.Map<String, PlannedDispatch> expected = plans.stream()
                .collect(java.util.stream.Collectors.toMap(
                        plan -> plan.profile().taskType(), plan -> plan));
        return rows.stream().allMatch(row -> {
            String type = (String) row.get("task_type");
            PlannedDispatch plan = expected.get(type);
            return plan != null
                    && plan.body().taskPublicId().equals(row.get("public_id"))
                    && plan.profile().ownerWorkloadId().equals(row.get("owner_workload_id"))
                    && plan.mandatory() == (Boolean) row.get("mandatory")
                    && plan.irreversible() == (Boolean) row.get("irreversible");
        });
    }

    private static boolean sameCase(
            java.util.Map<String, Object> row,
            CaseCreation value) {
        return ((Number) row.get("worker_id")).longValue() == value.workerId()
                && ((Number) row.get("work_relationship_id")).longValue()
                == value.workRelationshipId()
                && value.separationType().equals(row.get("separation_type"))
                && value.reasonCode().equals(row.get("reason_code"))
                && value.effectiveDate().equals(row.get("effective_date"))
                && value.lastWorkingDate().equals(row.get("last_working_date"))
                && "TASKS_ACTIVE".equals(row.get("lifecycle_state"))
                && value.inputSnapshotHash().equals(row.get("input_snapshot_hash"))
                && value.taskTemplatePublicId().equals(row.get("task_template_public_id"))
                && ((Number) row.get("task_template_revision")).longValue()
                == value.taskTemplateRevision()
                && value.taskTemplateHash().equals(row.get("task_template_hash"))
                && value.makerPrincipalPublicId().equals(row.get("maker_principal_public_id"))
                && value.correlationId().equals(row.get("correlation_id"))
                && ((Number) row.get("version")).longValue() >= 1
                && value.actorId() == ((Number) row.get("created_by")).longValue();
    }

    private static MapSqlParameterSource caseParameters(
            CaseCreation value) {
        return new MapSqlParameterSource().addValue("casePublic", value.casePublicId())
                .addValue("tenant", value.tenantId()).addValue("worker", value.workerId())
                .addValue("relationship", value.workRelationshipId())
                .addValue("type", value.separationType()).addValue("reason", value.reasonCode())
                .addValue("effective", value.effectiveDate())
                .addValue("lastWorking", value.lastWorkingDate())
                .addValue("inputHash", value.inputSnapshotHash())
                .addValue("templatePublic", value.taskTemplatePublicId())
                .addValue("templateRevision", value.taskTemplateRevision())
                .addValue("templateHash", value.taskTemplateHash())
                .addValue("maker", value.makerPrincipalPublicId())
                .addValue("correlation", value.correlationId())
                .addValue("created", Timestamp.from(value.createdAt()))
                .addValue("actor", value.actorId());
    }

    private static Long nullableLong(java.sql.ResultSet row, String name)
            throws java.sql.SQLException {
        return row.getObject(name, Long.class);
    }

    public record OwnerTask(
            long tenantId, long separationCaseId, UUID casePublicId, String caseState,
            UUID caseMakerPublicId, UUID correlationId, long caseVersion,
            long separationTaskId, UUID taskPublicId, String taskType,
            String ownerWorkloadId, String taskState, Long ownerRevision,
            String payloadSha256, long taskVersion) {
        public OwnerTask {
            if (tenantId < 1 || separationCaseId < 1 || separationTaskId < 1
                    || caseVersion < 0 || taskVersion < 0) {
                throw new IllegalArgumentException("invalid owner snapshot");
            }
            java.util.Objects.requireNonNull(casePublicId);
            java.util.Objects.requireNonNull(taskPublicId);
            java.util.Objects.requireNonNull(caseMakerPublicId);
            java.util.Objects.requireNonNull(correlationId);
            java.util.Objects.requireNonNull(caseState);
            java.util.Objects.requireNonNull(taskType);
            java.util.Objects.requireNonNull(ownerWorkloadId);
            java.util.Objects.requireNonNull(taskState);
        }
    }
}
