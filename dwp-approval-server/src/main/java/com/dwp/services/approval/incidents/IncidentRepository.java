package com.dwp.services.approval.incidents;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;
import static com.dwp.services.approval.incidents.IncidentValidation.*;

@Repository
public class IncidentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;

    public IncidentRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    <T> T prior(Context context, String operation, UUID target, Object input, Class<T> type) {
        requireActiveTenant(context);
        String digest = canonical.fingerprint(input);
        MapSqlParameterSource parameters = base(context).addValue("operation", operation)
                .addValue("target", target).addValue("digest", digest);
        advisoryLock(context, operation);
        int inserted = jdbc.update("""
                INSERT INTO apr_incident_commands(
                    tenant_id,resource_set_key,actor_user_id,operation,
                    idempotency_key,target_id,command_sha256)
                VALUES(:tenant,:scope,:actor,:operation,:key,:target,:digest)
                ON CONFLICT DO NOTHING
                """, parameters);
        Command command = jdbc.query("""
                SELECT target_id,command_sha256,status,result_type,result_payload::text
                  FROM apr_incident_commands
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key FOR UPDATE
                """, parameters, result -> {
            if (!result.next()) throw IncidentRejected.unavailable(
                    "Incident command receipt is unavailable.");
            return new Command(result.getObject(1, UUID.class), result.getString(2),
                    result.getString(3), result.getString(4), result.getString(5));
        });
        if (!java.util.Objects.equals(target, command.target())
                || !digest.equals(command.digest())) {
            throw IncidentRejected.conflict(
                    "The idempotency key is bound to another incident command.");
        }
        if (inserted == 1) return null;
        if (!"SUCCEEDED".equals(command.status()) || command.payload() == null
                || !type.getName().equals(command.resultType())) {
            throw IncidentRejected.unavailable("The incident command outcome is unknown.");
        }
        return canonical.read(command.payload(), type);
    }

    void complete(Context context, String operation, Object input, Object result) {
        int updated = jdbc.update("""
                UPDATE apr_incident_commands
                   SET status='SUCCEEDED',result_type=:type,
                       result_payload=CAST(:result AS jsonb),completed_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key AND command_sha256=:digest
                   AND status='UNKNOWN'
                """, base(context).addValue("operation", operation)
                .addValue("digest", canonical.fingerprint(input))
                .addValue("type", result.getClass().getName())
                .addValue("result", canonical.json(result)));
        if (updated != 1) throw IncidentRejected.conflict(
                "Incident command completion lost its exact fence.");
    }

    IncidentView open(Context context, OpenIncident input, Instant now) {
        validateOpen(input);
        MapSqlParameterSource parameters = base(context)
                .addValue("id", input.incidentId())
                .addValue("incidentKey", input.incidentKey().trim().toUpperCase())
                .addValue("title", input.title().trim())
                .addValue("severity", input.severity().name())
                .addValue("sourceKind", input.sourceKind().name())
                .addValue("sourceReference", input.sourceReference().trim())
                .addValue("now", Timestamp.from(now));
        try {
            jdbc.update("""
                    INSERT INTO apr_incidents(
                        tenant_id,resource_set_key,incident_id,incident_key,title,severity,
                        status,source_kind,source_reference,version,opened_by,opened_at,
                        updated_by,updated_at)
                    VALUES(:tenant,:scope,:id,:incidentKey,:title,:severity,'OPEN',
                        :sourceKind,:sourceReference,1,:actor,:now,:actor,:now)
                    """, parameters);
            appendTimeline(context, input.incidentId(), "OPENED", null, "OPEN",
                    "Incident opened", canonical.fingerprint(input), now);
        } catch (DuplicateKeyException exception) {
            throw IncidentRejected.conflict(
                    "Incident identifier or key already exists in this management scope.");
        }
        return requireIncident(context, input.incidentId(), true);
    }

    IncidentView changeStatus(
            Context context, UUID incidentId, StatusCommand input, Instant now) {
        if (input == null || input.status() == null || input.expectedVersion() < 1
                || blank(input.summary()) || input.summary().length() > 1000
                || !digest(input.evidenceSha256())) {
            throw IncidentRejected.invalid("Incident status command is invalid.");
        }
        IncidentView current = requireIncident(context, incidentId, true);
        if (current.version() != input.expectedVersion()
                || !transitionAllowed(current.status(), input.status())) {
            throw IncidentRejected.conflict("Incident status or version changed.");
        }
        boolean resolved = List.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED)
                .contains(input.status());
        int updated = jdbc.update("""
                UPDATE apr_incidents
                   SET status=:status,version=version+1,updated_by=:actor,updated_at=:now,
                       resolved_at=:resolvedAt
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND version=:expected AND status=:before
                """, base(context).addValue("id", incidentId)
                .addValue("status", input.status().name())
                .addValue("expected", input.expectedVersion())
                .addValue("before", current.status().name())
                .addValue("now", Timestamp.from(now))
                .addValue("resolvedAt", resolved ? Timestamp.from(now) : null));
        if (updated != 1) throw IncidentRejected.conflict(
                "Incident status changed during the command.");
        appendTimeline(context, incidentId, "STATUS_CHANGED", current.status().name(),
                input.status().name(), input.summary(), input.evidenceSha256(), now);
        return requireIncident(context, incidentId, true);
    }

    DiagnosticView addDiagnostic(
            Context context, UUID incidentId, DiagnosticCommand input, Instant now) {
        validateDiagnostic(input, now);
        IncidentView incident = requireIncident(context, incidentId, true);
        if (incident.version() != input.expectedIncidentVersion()) {
            throw IncidentRejected.conflict(
                    "Incident changed before diagnostic evidence was recorded.");
        }
        Map<String, Object> redacted = IncidentRedactor.redact(input.payload());
        String sha = canonical.fingerprint(redacted);
        MapSqlParameterSource parameters = base(context).addValue("id", incidentId)
                .addValue("diagnostic", input.diagnosticId())
                .addValue("kind", input.diagnosticKind())
                .addValue("payload", canonical.json(redacted)).addValue("sha", sha)
                .addValue("revision", input.sourceRevision().trim())
                .addValue("observed", Timestamp.from(input.observedAt()))
                .addValue("expected", input.expectedIncidentVersion())
                .addValue("now", Timestamp.from(now));
        jdbc.update("""
                INSERT INTO apr_incident_diagnostics(
                    tenant_id,resource_set_key,incident_id,diagnostic_id,diagnostic_kind,
                    redacted_payload,payload_sha256,source_revision,observed_at,recorded_by)
                VALUES(:tenant,:scope,:id,:diagnostic,:kind,CAST(:payload AS jsonb),
                    :sha,:revision,:observed,:actor)
                """, parameters);
        touchIncident(context, incidentId, input.expectedIncidentVersion(), now);
        appendTimeline(context, incidentId, "DIAGNOSTIC_ADDED", null, null,
                "Redacted diagnostic evidence added", sha, now);
        return new DiagnosticView(input.diagnosticId(), input.diagnosticKind(),
                redacted, sha, input.sourceRevision().trim(), input.observedAt());
    }

    PlanView createPlan(Context context, UUID incidentId, CreatePlan input, Instant now) {
        validatePlan(input);
        IncidentView incident = requireIncident(context, incidentId, true);
        if (incident.version() != input.expectedIncidentVersion()
                || List.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED)
                .contains(incident.status())) {
            throw IncidentRejected.conflict("Incident cannot accept this recovery plan.");
        }
        Map<String, Object> target = IncidentRedactor.redact(input.targetSnapshot());
        String sha = canonical.fingerprint(target);
        MapSqlParameterSource parameters = base(context).addValue("id", incidentId)
                .addValue("plan", input.planId()).addValue("kind", input.planKind().name())
                .addValue("target", canonical.json(target)).addValue("sha", sha)
                .addValue("expected", input.expectedIncidentVersion())
                .addValue("now", Timestamp.from(now));
        jdbc.update("""
                INSERT INTO apr_incident_recovery_plans(
                    tenant_id,resource_set_key,incident_id,plan_id,plan_kind,state,
                    target_snapshot,target_sha256,version,created_by,created_at,
                    updated_by,updated_at)
                VALUES(:tenant,:scope,:id,:plan,:kind,'DRAFT',CAST(:target AS jsonb),
                    :sha,1,:actor,:now,:actor,:now)
                """, parameters);
        for (StageDraft stage : input.stages()) {
            jdbc.update("""
                    INSERT INTO apr_incident_recovery_stages(
                        tenant_id,resource_set_key,incident_id,plan_id,stage_number,
                        action_kind,target_type,target_id,expected_target_version,state,version)
                    VALUES(:tenant,:scope,:id,:plan,:number,:action,:targetType,
                        :targetId,:targetVersion,'PENDING',1)
                    """, new MapSqlParameterSource(parameters.getValues())
                    .addValue("number", stage.stageNumber())
                    .addValue("action", stage.actionKind().name())
                    .addValue("targetType", stage.targetType().name())
                    .addValue("targetId", stage.targetId())
                    .addValue("targetVersion", stage.expectedTargetVersion()));
        }
        touchIncident(context, incidentId, input.expectedIncidentVersion(), now);
        appendTimeline(context, incidentId, "PLAN_CREATED", null, null,
                "Dry-run recovery plan created", sha, now);
        return requirePlan(context, incidentId, input.planId(), true);
    }

    PlanView recordDryRun(
            Context context,
            UUID incidentId,
            UUID planId,
            DryRunObservation input,
            Instant now) {
        if (input == null || input.expectedPlanVersion() < 1
                || input.result() == null || input.result().isEmpty()
                || !digest(input.evidenceSha256())) {
            throw IncidentRejected.invalid("Recovery dry-run observation is invalid.");
        }
        Map<String, Object> result = IncidentRedactor.redact(input.result());
        PlanState state = input.executable() ? PlanState.VALIDATED : PlanState.BLOCKED;
        int updated = jdbc.update("""
                UPDATE apr_incident_recovery_plans
                   SET state=:state,dry_run_result=CAST(:result AS jsonb),
                       dry_run_evidence_sha256=:evidence,version=version+1,
                       updated_by=:actor,updated_at=:now
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND plan_id=:plan AND version=:expected
                   AND state='DRAFT'
                """, base(context).addValue("id", incidentId).addValue("plan", planId)
                .addValue("state", state.name()).addValue("result", canonical.json(result))
                .addValue("evidence", input.evidenceSha256())
                .addValue("expected", input.expectedPlanVersion())
                .addValue("now", Timestamp.from(now)));
        if (updated != 1) throw IncidentRejected.conflict(
                "Recovery plan changed or already has a dry-run result.");
        lockIncident(context, incidentId);
        appendTimeline(context, incidentId, "PLAN_VALIDATED", null, state.name(),
                input.executable() ? "Recovery dry-run validated" : "Recovery dry-run blocked",
                input.evidenceSha256(), now);
        return requirePlan(context, incidentId, planId, true);
    }

    PlanView startStage(
            Context context,
            UUID incidentId,
            UUID planId,
            StageStart input,
            Instant now) {
        if (input == null || input.stageNumber() < 1 || input.stageNumber() > 100
                || input.expectedPlanVersion() < 1 || input.expectedStageVersion() < 1
                || blank(input.executionKey())
                || !input.executionKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
            throw IncidentRejected.invalid("Recovery stage start command is invalid.");
        }
        PlanView plan = requirePlan(context, incidentId, planId, true);
        if (plan.version() != input.expectedPlanVersion()
                || !List.of(PlanState.VALIDATED, PlanState.EXECUTING, PlanState.PARTIAL)
                .contains(plan.state())) {
            throw IncidentRejected.conflict("Recovery plan is not executable at this version.");
        }
        StageView stage = stage(plan, input.stageNumber());
        if (stage.version() != input.expectedStageVersion()
                || stage.state() != StageState.PENDING
                || plan.stages().stream().anyMatch(previous -> previous.stageNumber() < stage.stageNumber()
                && previous.state() != StageState.SUCCEEDED)) {
            throw IncidentRejected.conflict(
                    "Recovery stages must execute in order after prior verified success.");
        }
        int stageUpdated = jdbc.update("""
                UPDATE apr_incident_recovery_stages
                   SET state='RUNNING',execution_key=:execution,attempt=attempt+1,
                       version=version+1,started_at=:now
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND plan_id=:plan AND stage_number=:stage
                   AND version=:stageVersion AND state='PENDING'
                """, base(context).addValue("id", incidentId).addValue("plan", planId)
                .addValue("stage", input.stageNumber()).addValue("execution", input.executionKey())
                .addValue("stageVersion", input.expectedStageVersion())
                .addValue("now", Timestamp.from(now)));
        if (stageUpdated != 1) throw IncidentRejected.conflict(
                "Recovery stage changed before execution started.");
        updatePlanState(context, incidentId, planId, input.expectedPlanVersion(),
                PlanState.EXECUTING, now, null);
        lockIncident(context, incidentId);
        appendTimeline(context, incidentId, "STAGE_STARTED", null, "RUNNING",
                "Recovery stage " + input.stageNumber() + " started",
                canonical.fingerprint(input), now);
        return requirePlan(context, incidentId, planId, true);
    }

    PlanView completeStage(
            Context context,
            UUID incidentId,
            UUID planId,
            VerifiedStageCompletion input,
            Instant now) {
        validateStageCompletion(input, now);
        PlanView plan = requirePlan(context, incidentId, planId, true);
        if (plan.version() != input.expectedPlanVersion()
                || plan.state() != PlanState.EXECUTING) {
            throw IncidentRejected.conflict("Recovery plan changed during stage execution.");
        }
        StageView stage = stage(plan, input.stageNumber());
        if (stage.version() != input.expectedStageVersion()
                || stage.state() != StageState.RUNNING) {
            throw IncidentRejected.conflict("Recovery stage is not running at this version.");
        }
        Map<String, Object> result = IncidentRedactor.redact(input.result());
        int updated = jdbc.update("""
                UPDATE apr_incident_recovery_stages
                   SET state=:state,result=CAST(:result AS jsonb),evidence_sha256=:evidence,
                       receipt_issuer=:receiptIssuer,receipt_key_id=:receiptKeyId,
                       receipt_verification_reference=:receiptVerification,
                       version=version+1,completed_at=:completed
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND plan_id=:plan AND stage_number=:stage
                   AND version=:stageVersion AND state='RUNNING'
                """, base(context).addValue("id", incidentId).addValue("plan", planId)
                .addValue("stage", input.stageNumber()).addValue("state", input.state().name())
                .addValue("result", canonical.json(result))
                .addValue("evidence", input.evidenceSha256())
                .addValue("receiptIssuer", input.receiptIssuer())
                .addValue("receiptKeyId", input.receiptKeyId())
                .addValue("receiptVerification", input.receiptVerificationReference())
                .addValue("completed", Timestamp.from(input.completedAt()))
                .addValue("stageVersion", input.expectedStageVersion()));
        if (updated != 1) throw IncidentRejected.conflict(
                "Recovery stage changed before completion evidence was stored.");
        PlanState provisional = switch (input.state()) {
            case SUCCEEDED -> PlanState.PARTIAL;
            case FAILED -> PlanState.FAILED;
            case UNKNOWN_REMOTE_OUTCOME -> PlanState.UNKNOWN_REMOTE_OUTCOME;
            default -> throw IncidentRejected.invalid("Recovery stage result is not terminal.");
        };
        updatePlanState(context, incidentId, planId, input.expectedPlanVersion(),
                provisional, now, null);
        lockIncident(context, incidentId);
        appendTimeline(context, incidentId, "STAGE_COMPLETED", "RUNNING",
                input.state().name(), "Recovery stage " + input.stageNumber() + " completed",
                input.evidenceSha256(), now);
        return requirePlan(context, incidentId, planId, true);
    }

    PlanView reconcilePlan(
            Context context, UUID incidentId, UUID planId, long expectedVersion, Instant now) {
        PlanView plan = requirePlan(context, incidentId, planId, true);
        if (plan.version() != expectedVersion || expectedVersion < 1) {
            throw IncidentRejected.conflict("Recovery plan version changed before reconciliation.");
        }
        PlanState state = reconciledState(plan.stages());
        String evidence = canonical.fingerprint(Map.of(
                "planId", planId, "version", expectedVersion,
                "stages", plan.stages().stream().map(stage -> Map.of(
                        "number", stage.stageNumber(), "state", stage.state().name(),
                        "version", stage.version(), "evidence",
                        stage.evidenceSha256() == null ? "" : stage.evidenceSha256(),
                        "receipt", stage.receiptVerificationReference() == null
                                ? "" : stage.receiptVerificationReference())).toList()));
        updatePlanState(context, incidentId, planId, expectedVersion, state, now,
                state == PlanState.COMPLETED ? now : null);
        lockIncident(context, incidentId);
        appendTimeline(context, incidentId, "RECONCILED", null, state.name(),
                "Recovery plan reconciled from stored stage evidence", evidence, now);
        return requirePlan(context, incidentId, planId, true);
    }

    PostmortemView recordPostmortem(
            Context context, UUID incidentId, PostmortemCommand input, Instant now) {
        validatePostmortem(input);
        IncidentView incident = requireIncident(context, incidentId, true);
        if (incident.version() != input.expectedIncidentVersion()
                || !List.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED)
                .contains(incident.status())) {
            throw IncidentRejected.conflict(
                    "Postmortem requires the exact version of a resolved incident.");
        }
        MapSqlParameterSource parameters = base(context).addValue("id", incidentId)
                .addValue("postmortem", input.postmortemId())
                .addValue("summary", input.summary().trim())
                .addValue("factors", canonical.json(input.contributingFactors()))
                .addValue("actions", canonical.json(input.correctiveActions()))
                .addValue("evidence", input.evidenceSha256())
                .addValue("expected", input.expectedIncidentVersion())
                .addValue("now", Timestamp.from(now));
        jdbc.update("""
                INSERT INTO apr_incident_postmortems(
                    tenant_id,resource_set_key,incident_id,postmortem_id,summary,
                    contributing_factors,corrective_actions,evidence_sha256,
                    version,recorded_by,recorded_at)
                VALUES(:tenant,:scope,:id,:postmortem,:summary,CAST(:factors AS jsonb),
                    CAST(:actions AS jsonb),:evidence,1,:actor,:now)
                """, parameters);
        touchIncident(context, incidentId, input.expectedIncidentVersion(), now);
        appendTimeline(context, incidentId, "POSTMORTEM_RECORDED", null, null,
                "Incident postmortem recorded", input.evidenceSha256(), now);
        return new PostmortemView(input.postmortemId(), input.summary().trim(),
                List.copyOf(input.contributingFactors()), List.copyOf(input.correctiveActions()),
                input.evidenceSha256(), 1, now);
    }

    IncidentView requireIncident(Context context, UUID incidentId, boolean lock) {
        List<IncidentView> rows = jdbc.query("""
                SELECT incident_id,incident_key,title,severity,status,source_kind,
                       source_reference,version,opened_at,updated_at,resolved_at
                  FROM apr_incidents
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND incident_id=:id
                """ + (lock ? " FOR UPDATE" : ""), base(context).addValue("id", incidentId),
                (row, number) -> new IncidentView(row.getObject(1, UUID.class), row.getString(2),
                        row.getString(3), Severity.valueOf(row.getString(4)),
                        IncidentStatus.valueOf(row.getString(5)), SourceKind.valueOf(row.getString(6)),
                        row.getString(7), row.getLong(8), instant(row.getTimestamp(9)),
                        instant(row.getTimestamp(10)), instant(row.getTimestamp(11))));
        if (rows.size() != 1) throw IncidentRejected.unavailable(
                "Incident is unavailable in this management scope.");
        return rows.getFirst();
    }

    PlanView requirePlan(
            Context context, UUID incidentId, UUID planId, boolean lock) {
        List<PlanView> rows = jdbc.query("""
                SELECT incident_id,plan_id,plan_kind,state,target_snapshot::text,target_sha256,
                       dry_run_result::text,dry_run_evidence_sha256,version,completed_at
                  FROM apr_incident_recovery_plans
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND plan_id=:plan
                """ + (lock ? " FOR UPDATE" : ""), base(context).addValue("id", incidentId)
                .addValue("plan", planId), (row, number) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> target = canonical.read(row.getString(5), Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dryRun = row.getString(7) == null ? null
                            : canonical.read(row.getString(7), Map.class);
                    return new PlanView(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            PlanKind.valueOf(row.getString(3)), PlanState.valueOf(row.getString(4)),
                            Map.copyOf(target), row.getString(6),
                            dryRun == null ? null : Map.copyOf(dryRun), row.getString(8),
                            row.getLong(9), stages(context, incidentId, planId),
                            instant(row.getTimestamp(10)));
                });
        if (rows.size() != 1) throw IncidentRejected.unavailable(
                "Recovery plan is unavailable in this management scope.");
        return rows.getFirst();
    }

    private List<StageView> stages(Context context, UUID incidentId, UUID planId) {
        return jdbc.query("""
                SELECT stage_number,action_kind,target_type,target_id,expected_target_version,
                       state,execution_key,attempt,result::text,evidence_sha256,version,
                       started_at,completed_at,receipt_issuer,receipt_key_id,
                       receipt_verification_reference
                  FROM apr_incident_recovery_stages
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND plan_id=:plan ORDER BY stage_number
                """, base(context).addValue("id", incidentId).addValue("plan", planId),
                (row, number) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = row.getString(9) == null ? null
                            : canonical.read(row.getString(9), Map.class);
                    return new StageView(row.getInt(1), ActionKind.valueOf(row.getString(2)),
                            TargetType.valueOf(row.getString(3)), row.getObject(4, UUID.class),
                            row.getLong(5), StageState.valueOf(row.getString(6)), row.getString(7),
                            row.getInt(8), result == null ? null : Map.copyOf(result), row.getString(10),
                            row.getLong(11), instant(row.getTimestamp(12)), instant(row.getTimestamp(13)),
                            row.getString(14), row.getString(15), row.getString(16));
                });
    }

    private void appendTimeline(
            Context context,
            UUID incidentId,
            String type,
            String before,
            String after,
            String summary,
            String evidence,
            Instant now) {
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(max(sequence),0) + 1 FROM apr_incident_timeline
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND incident_id=:id
                """, base(context).addValue("id", incidentId), Long.class);
        jdbc.update("""
                INSERT INTO apr_incident_timeline(
                    tenant_id,resource_set_key,incident_id,sequence,event_type,
                    status_before,status_after,summary,evidence_sha256,actor_user_id,occurred_at)
                VALUES(:tenant,:scope,:id,:sequence,:type,:before,:after,:summary,
                    :evidence,:actor,:now)
                """, base(context).addValue("id", incidentId).addValue("sequence", sequence)
                .addValue("type", type).addValue("before", before).addValue("after", after)
                .addValue("summary", summary).addValue("evidence", evidence)
                .addValue("now", Timestamp.from(now)));
    }

    private void touchIncident(Context context, UUID incidentId, long expectedVersion, Instant now) {
        int updated = jdbc.update("""
                UPDATE apr_incidents
                   SET version=version+1,updated_by=:actor,updated_at=:now
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND version=:expected
                """, base(context).addValue("id", incidentId)
                .addValue("expected", expectedVersion).addValue("now", Timestamp.from(now)));
        if (updated != 1) throw IncidentRejected.conflict(
                "Incident version changed during the command.");
    }

    private void lockIncident(Context context, UUID incidentId) {
        requireIncident(context, incidentId, true);
    }

    private void updatePlanState(
            Context context,
            UUID incidentId,
            UUID planId,
            long expectedVersion,
            PlanState state,
            Instant now,
            Instant completedAt) {
        int updated = jdbc.update("""
                UPDATE apr_incident_recovery_plans
                   SET state=:state,version=version+1,updated_by=:actor,updated_at=:now,
                       completed_at=:completed
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:id AND plan_id=:plan AND version=:expected
                """, base(context).addValue("id", incidentId).addValue("plan", planId)
                .addValue("expected", expectedVersion).addValue("state", state.name())
                .addValue("now", Timestamp.from(now))
                .addValue("completed", completedAt == null ? null : Timestamp.from(completedAt)));
        if (updated != 1) throw IncidentRejected.conflict(
                "Recovery plan version changed during the command.");
    }

    private StageView stage(PlanView plan, int number) {
        return plan.stages().stream().filter(value -> value.stageNumber() == number)
                .findFirst().orElseThrow(() -> IncidentRejected.invalid(
                        "Recovery stage does not exist in this plan."));
    }

    private PlanState reconciledState(List<StageView> stages) {
        if (stages.stream().allMatch(stage -> stage.state() == StageState.SUCCEEDED
                && stage.receiptVerificationReference() != null)) {
            return PlanState.COMPLETED;
        }
        if (stages.stream().anyMatch(stage -> stage.state() == StageState.SUCCEEDED
                && stage.receiptVerificationReference() == null)) {
            return PlanState.UNKNOWN_REMOTE_OUTCOME;
        }
        if (stages.stream().anyMatch(stage -> stage.state() == StageState.UNKNOWN_REMOTE_OUTCOME)) {
            return PlanState.UNKNOWN_REMOTE_OUTCOME;
        }
        if (stages.stream().anyMatch(stage -> stage.state() == StageState.FAILED)) {
            return PlanState.FAILED;
        }
        if (stages.stream().anyMatch(stage -> stage.state() == StageState.RUNNING)) {
            return PlanState.EXECUTING;
        }
        if (stages.stream().anyMatch(stage -> stage.state() == StageState.SUCCEEDED)) {
            return PlanState.PARTIAL;
        }
        return PlanState.VALIDATED;
    }

    private void requireActiveTenant(Context context) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, base(context), Integer.class);
        if (count == null || count != 1) throw IncidentRejected.forbidden(
                "Approval tenant is not active.");
    }

    private void advisoryLock(Context context, String operation) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))",
                Map.of("value", context.tenantId() + "|" + context.resourceSetKey()
                        + "|" + context.actorUserId() + "|" + operation + "|"
                        + context.idempotencyKey()), Object.class);
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource().addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey())
                .addValue("actor", context.actorUserId())
                .addValue("key", context.idempotencyKey());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Command(
            UUID target, String digest, String status, String resultType, String payload) {
    }
}
