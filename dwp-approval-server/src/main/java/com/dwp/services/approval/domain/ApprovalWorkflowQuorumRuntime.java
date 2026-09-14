package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumRuntimeStore.*;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable tagged runtime; all changes join the owner command transaction. */
public final class ApprovalWorkflowQuorumRuntime {
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final TransactionTemplate transactions;
    private final ApprovalWorkflowQuorumAuthority authority;
    private final ApprovalWorkflowQuorumEvaluator evaluator = new ApprovalWorkflowQuorumEvaluator();
    private final ApprovalWorkflowQuorumEvidence evidence;

    public record VoteCommand(long tenantId, UUID requestId, UUID stepId, long generation, UUID taskId,
            long expectedTaskVersion, long expectedStageVersion, Pins expectedPins, long actorUserId,
            long principalUserId, Decision decision, String reason) { }
    public record Receipt(UUID requestId, UUID stepId, long stageVersion, Outcome outcome, String requestStatus,
            int approved, int threshold) { }

    public ApprovalWorkflowQuorumRuntime(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            TransactionTemplate transactions, ApprovalWorkflowQuorumAuthority authority, AuditOutboxRecorder audit) {
        this.store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper);
        this.transactions = transactions;
        this.authority = java.util.Objects.requireNonNull(authority);
        this.evidence = new ApprovalWorkflowQuorumEvidence(store, audit);
    }

    public Pins canonicalPins(long tenant, UUID request, ApprovalWorkflowQuorumDefinition definition) {
        return transactions.execute(status -> {
            store.lockRequest(tenant, request);
            return store.context(tenant, request, definition, store.policy(tenant, request)).pins();
        });
    }

    /** Called in the submit transaction only after owner form/conditional route validation and request CAS. */
    public void start(long tenant, UUID request, Pins expected, ApprovalWorkflowQuorumDefinition definition) {
        begin(tenant, request, expected, definition, 1);
    }

    void restart(long tenant, UUID request, Pins expected, ApprovalWorkflowQuorumDefinition definition, long generation) {
        if (generation <= 1) throw conflict();
        begin(tenant, request, expected, definition, generation);
    }

    private void begin(long tenant, UUID request, Pins expected, ApprovalWorkflowQuorumDefinition definition, long generation) {
        transactions.executeWithoutResult(status -> {
            store.lockRequest(tenant, request);
            var context = store.context(tenant, request, definition, store.policy(tenant, request));
            if (!context.pins().equals(expected)) throw conflict();
            var previous = store.stages(tenant, request, true);
            if (generation == 1 && !store.jdbc.queryForList("SELECT step_id FROM apr_steps WHERE tenant_id=:tenant AND request_id=:request",
                    store.scope(tenant, request)).isEmpty()) throw conflict();
            if (generation > 1 && (previous.isEmpty() || previous.getFirst().generation() != generation - 1
                    || previous.stream().anyMatch(row -> List.of("WAITING", "IN_PROGRESS").contains(row.status())))) throw conflict();
            Map<String, UUID> ids = new HashMap<>();
            int sequence = generation == 1 ? 0 : store.jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sequence_number),0) FROM apr_steps WHERE tenant_id=:tenant AND request_id=:request",
                    store.scope(tenant, request), Integer.class);
            for (var stage : definition.topologicalStages()) {
                UUID step = UUID.randomUUID();
                ids.put(stage.key(), step);
                var p = store.scope(tenant, request).addValue("step", step).addValue("key", stage.key())
                        .addValue("name", stage.name()).addValue("sequence", ++sequence)
                        .addValue("mode", stage.quorum().mode().name()).addValue("role", stage.candidateRole())
                        .addValue("context", store.json(context)).addValue("definition", definition.canonicalJson())
                        .addValue("generation", generation)
                        .addValue("hash", definition.sha256());
                store.jdbc.update("""
                        INSERT INTO apr_steps(step_id,tenant_id,request_id,step_key,step_name,sequence_number,
                            approval_mode,candidate_role,status)
                        VALUES(:step,:tenant,:request,:key,:name,:sequence,:mode,:role,'WAITING')
                        """, p);
                store.jdbc.update("""
                        INSERT INTO apr_quorum_stage_runtime(tenant_id,request_id,step_id,generation,stage_key,
                            context,definition_canonical,definition_sha256,status)
                        VALUES(:tenant,:request,:step,:generation,:key,CAST(:context AS jsonb),:definition,:hash,'WAITING')
                        """, p);
            }
            for (var stage : definition.stages()) for (String predecessor : stage.predecessors()) {
                store.jdbc.update("""
                        INSERT INTO apr_quorum_prerequisites(tenant_id,request_id,step_id,generation,predecessor_step_id)
                        VALUES(:tenant,:request,:step,:generation,:predecessor)
                        """, store.scope(tenant, request).addValue("step", ids.get(stage.key())).addValue("generation", generation)
                        .addValue("predecessor", ids.get(predecessor)));
            }
            activateReady(tenant, request);
            if (store.stages(tenant, request, false).stream().noneMatch(stage -> "IN_PROGRESS".equals(stage.status()))) {
                throw invalid("No executable approval path remains after pinned condition evaluation.");
            }
            evidence.append(tenant, request, context.requesterUserId(), generation == 1 ? "Approval.Quorum.Started" : "Approval.Quorum.GenerationStarted",
                    Map.of("contract", CONTRACT, "workflowVersionId", expected.workflowVersionId().toString(),
                            "definitionSha256", expected.workflowDefinitionSha256(), "policySha256", expected.policySha256(),
                            "stages", definition.stages().size()));
        });
    }

    public Receipt vote(VoteCommand command) {
        return transactions.execute(status -> cast(command));
    }

    private Receipt cast(VoteCommand command) {
        long tenant = command.tenantId();
        UUID request = command.requestId();
        store.lockRequest(tenant, request);
        List<StageRow> stages = store.stages(tenant, request, true);
        StageRow row = stages.stream().filter(stage -> stage.stepId().equals(command.stepId())
                && stage.generation() == command.generation()).findFirst().orElseThrow(ApprovalWorkflowQuorumRuntimeStore::conflict);
        if (!"IN_PROGRESS".equals(row.status()) || row.version() != command.expectedStageVersion()) throw conflict();
        store.verify(row.context(), tenant, request, row.definition());
        var p = store.scope(tenant, request).addValue("step", row.stepId()).addValue("generation", row.generation())
                .addValue("task", command.taskId()).addValue("principal", command.principalUserId());
        var tasks = store.jdbc.queryForList("""
                SELECT task.version, task.status, task.assignee_user_id, task.assignee_person_public_id,
                       candidate.principal_person_id
                  FROM apr_tasks task JOIN apr_quorum_candidates candidate
                    ON candidate.tenant_id=task.tenant_id AND candidate.request_id=task.request_id
                   AND candidate.step_id=task.step_id AND candidate.task_id=task.task_id
                 WHERE candidate.tenant_id=:tenant AND candidate.request_id=:request AND candidate.step_id=:step
                   AND candidate.generation=:generation AND candidate.task_id=:task AND candidate.principal_user_id=:principal
                 FOR UPDATE OF task
                """, p);
        if (tasks.size() != 1) throw conflict();
        var task = tasks.getFirst();
        if (!"CLAIMED".equals(task.get("status")) || ((Number) task.get("version")).longValue() != command.expectedTaskVersion()
                || ((Number) task.get("assignee_user_id")).longValue() != command.principalUserId()
                || !task.get("assignee_person_public_id").equals(task.get("principal_person_id"))) throw conflict();
        revalidate(tenant, request, stages, false);
        CurrentAuthority current = current(row.snapshot(), command.actorUserId(), command.principalUserId(), store.now(),
                ApprovalWorkflowRuntimeTarget.Use.CAST,command.taskId(),null);
        Instant now = store.now();
        AcceptedVote accepted = evaluator.accept(new State(row.snapshot(), row.version(), store.votes(tenant, request, row)),
                command.expectedStageVersion(), command.expectedPins(), current, command.decision(), command.reason(), now);
        Vote vote = accepted.vote();
        p.addValue("vote", UUID.randomUUID()).addValue("version", vote.stageVersion()).addValue("actor", vote.actorUserId())
                .addValue("actorPerson", vote.actorPersonPublicId()).addValue("person", vote.principalPersonPublicId())
                .addValue("decision", vote.decision().name()).addValue("evidence", store.json(vote)).addValue("now", time(now))
                .addValue("taskVersion", command.expectedTaskVersion()).addValue("payloadRevision", vote.payloadRevision())
                .addValue("payloadHash", vote.payloadSha256()).addValue("reason", vote.reason())
                .addValue("taskStatus", vote.decision() == Decision.APPROVE ? "APPROVED" : "REJECTED")
                .addValue("oldVersion", row.version()).addValue("status", accepted.evaluation().outcome().name());
        store.jdbc.update("""
                INSERT INTO apr_quorum_votes(vote_id,tenant_id,request_id,step_id,generation,stage_version,
                    actor_user_id,actor_person_id,principal_user_id,principal_person_id,task_id,decision,evidence,accepted_at)
                VALUES(:vote,:tenant,:request,:step,:generation,:version,:actor,:actorPerson,:principal,:person,
                    :task,:decision,CAST(:evidence AS jsonb),:now)
                """, p);
        changed(store.jdbc.update("""
                UPDATE apr_tasks SET status=:taskStatus,version=version+1,completed_at=:now,updated_at=:now,
                    decision_reason=:reason,decision_actor_user_id=:actor,decision_actor_person_public_id=:actorPerson,
                    decision_payload_revision=:payloadRevision,decision_payload_sha256=:payloadHash
                 WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND task_id=:task
                   AND status='CLAIMED' AND version=:taskVersion AND assignee_user_id=:principal
                """, p));
        changed(store.jdbc.update("""
                UPDATE apr_quorum_stage_runtime SET status=:status,version=version+1,
                    completed_at=CASE WHEN :status='IN_PROGRESS' THEN NULL ELSE CAST(:now AS timestamptz) END
                 WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation
                   AND status='IN_PROGRESS' AND version=:oldVersion
                """, p));
        changed(store.jdbc.update("""
                UPDATE apr_steps SET status=:status,version=version+1,updated_at=:now,
                    completed_at=CASE WHEN :status='IN_PROGRESS' THEN NULL ELSE CAST(:now AS timestamptz) END
                 WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND status='IN_PROGRESS'
                """, p));
        Outcome outcome = accepted.evaluation().outcome();
        if (outcome != Outcome.IN_PROGRESS) finishStage(tenant, request, row.stepId(), outcome, now);
        if (outcome == Outcome.APPROVED) activateReady(tenant, request);
        String requestStatus = outcome == Outcome.REJECTED ? "REJECTED" : store.stages(tenant, request, false).stream()
                .allMatch(stage -> "APPROVED".equals(stage.status()) || "SKIPPED".equals(stage.status())) ? "APPROVED" : "IN_REVIEW";
        changed(store.jdbc.update("""
                UPDATE apr_requests SET status=:status,version=version+1,updated_at=:now,updated_by=:actor,
                    completed_at=CASE WHEN :status='IN_REVIEW' THEN NULL ELSE CAST(:now AS timestamptz) END
                 WHERE tenant_id=:tenant AND request_id=:request AND status='IN_REVIEW'
                """, store.scope(tenant, request).addValue("status", requestStatus).addValue("now", time(now)).addValue("actor", vote.actorUserId())));
        evidence.append(tenant, request, vote.actorUserId(), "Approval.Quorum.VoteAccepted",
                Map.of("stepId", row.stepId().toString(), "stageVersion", vote.stageVersion(), "decision", vote.decision().name(),
                        "outcome", outcome.name(), "requestStatus", requestStatus, "payloadRevision", vote.payloadRevision(),
                        "payloadSha256", vote.payloadSha256(), "authorityRevision", vote.authorityRevision()));
        return new Receipt(request, row.stepId(), vote.stageVersion(), outcome, requestStatus,
                accepted.evaluation().approved(), accepted.evaluation().threshold());
    }

    // Information rounds additionally revalidate every frozen seat, not just previously accepted votes.
    void revalidate(long tenant, UUID request, List<StageRow> stages, boolean allSeats) {
        for (StageRow stage : stages) if (stage.snapshot() != null) {
            if (allSeats) for (Candidate candidate : stage.snapshot().candidates()) {
                CurrentAuthority current = authority instanceof ApprovalWorkflowBoundAuthority
                        ?current(stage.snapshot(),candidate.userId(),candidate.userId(),store.now(),ApprovalWorkflowRuntimeTarget.Use.SEAT_RECHECK,
                            store.taskIdForSeat(tenant,request,stage,candidate),null)
                        :current(stage.snapshot(), candidate.userId(), candidate.userId(), store.now());
                evaluator.verifyAuthority(stage.snapshot(), current, store.now());
            }
            var stored=authority instanceof ApprovalWorkflowBoundAuthority?store.storedVotes(tenant,request,stage):null;
            for (Vote vote : stored==null?store.votes(tenant,request,stage):stored.stream().map(StoredVote::vote).toList()) {
                var original=stored==null?null:stored.stream().filter(item->item.vote().equals(vote)).findFirst().orElseThrow(ApprovalWorkflowQuorumRuntimeStore::conflict);
                CurrentAuthority current = original==null?current(stage.snapshot(),vote.actorUserId(),vote.principalUserId(),store.now())
                        :current(stage.snapshot(),vote.actorUserId(),vote.principalUserId(),store.now(),ApprovalWorkflowRuntimeTarget.Use.VOTE_RECHECK,original.taskId(),original.voteId());
                UUID currentDelegation = current.delegation() == null ? null : current.delegation().delegationId();
                if (!java.util.Objects.equals(vote.delegationId(), currentDelegation)
                        || !vote.actorPersonPublicId().equals(current.actor().personPublicId())
                        || !vote.principalPersonPublicId().equals(current.principal().personPublicId())) {
                    throw new BaseException(ErrorCode.FORBIDDEN, "A replacement identity or delegation cannot heal the original vote.");
                }
                evaluator.verifyAuthority(stage.snapshot(), current, store.now());
            }
        }
    }

    CurrentAuthority current(Snapshot snapshot, long actor, long principal, Instant now) {
        CurrentAuthority current = authority.voter(snapshot, actor, principal, now);
        if (current == null || current.actor().userId() != actor || current.principal().userId() != principal) {
            throw unavailable("The owner did not resolve the requested voter identities.");
        }
        if (current.delegation() != null) {
            lockDelegation(snapshot, current);
            // A delegation lock may wait; do not finalize using Auth evidence obtained before that wait.
            CurrentAuthority refreshed = authority.voter(snapshot, actor, principal, store.now());
            if (refreshed == null || refreshed.actor().userId() != actor || refreshed.principal().userId() != principal) {
                throw unavailable("Current delegation voter authority could not be refreshed under the local grant lock.");
            }
            if (refreshed.delegation() == null || !refreshed.delegation().delegationId().equals(current.delegation().delegationId())
                    || !refreshed.actor().personPublicId().equals(current.actor().personPublicId())
                    || !refreshed.principal().personPublicId().equals(current.principal().personPublicId())) {
                throw new BaseException(ErrorCode.FORBIDDEN, "The original delegation or voter identity changed during resolution.");
            }
            current = refreshed;
        }
        return current;
    }

    CurrentAuthority current(Snapshot snapshot,long actor,long principal,Instant now,ApprovalWorkflowRuntimeTarget.Use use,UUID task,UUID original) {
        if(!(authority instanceof ApprovalWorkflowBoundAuthority bound)) return current(snapshot,actor,principal,now);
        CurrentAuthority current=bound.voter(snapshot,use,task,original,now);
        if(current==null || current.actor().userId()!=actor || current.principal().userId()!=principal)
            throw unavailable("The owner did not resolve the locked voter identities.");
        if(current.delegation()!=null) {
            lockDelegation(snapshot,current);
            CurrentAuthority refreshed=bound.voter(snapshot,use,task,original,store.now());
            if(refreshed==null || refreshed.actor().userId()!=actor || refreshed.principal().userId()!=principal)
                throw unavailable("Current locked delegation authority could not be refreshed.");
            if(refreshed.delegation()==null || !refreshed.delegation().delegationId().equals(current.delegation().delegationId())
                    || !refreshed.actor().personPublicId().equals(current.actor().personPublicId())
                    || !refreshed.principal().personPublicId().equals(current.principal().personPublicId()))
                throw new BaseException(ErrorCode.FORBIDDEN,"The original delegation or voter identity changed during resolution.");
            current=refreshed;
        }
        return current;
    }

    private void lockDelegation(Snapshot snapshot, CurrentAuthority current) {
        var delegation = current.delegation();
        var p = store.scope(snapshot.pins().tenantId(), snapshot.requestId()).addValue("delegation", delegation.delegationId())
                .addValue("actor", current.actor().userId()).addValue("principal", current.principal().userId())
                .addValue("person", current.actor().personPublicId()).addValue("role", snapshot.candidateRole());
        var rows = store.jdbc.queryForList("""
                SELECT delegation.delegation_id FROM apr_delegations delegation
                  JOIN apr_requests request ON request.tenant_id=delegation.tenant_id AND request.request_id=:request
                  JOIN apr_workflow_versions workflow ON workflow.tenant_id=request.tenant_id
                   AND workflow.workflow_version_id=request.workflow_version_id
                 WHERE delegation.tenant_id=:tenant AND delegation.delegation_id=:delegation AND delegation.lifecycle_state='ACTIVE'
                   AND delegation.delegator_user_id=:principal AND delegation.delegate_user_id=:actor
                   AND delegation.delegate_person_public_id=:person AND jsonb_exists(delegation.delegated_role_codes,:role)
                   AND (delegation.scope_type='ALL' OR (delegation.scope_type='WORKFLOW' AND delegation.workflow_id=workflow.workflow_id))
                   AND delegation.starts_at<=clock_timestamp() AND delegation.ends_at>clock_timestamp()
                 FOR SHARE OF delegation
                """, p);
        if (rows.size() != 1) throw new BaseException(ErrorCode.FORBIDDEN, "Current delegation was revoked or its scope changed.");
    }

    private void activateReady(long tenant, UUID request) {
        List<StageRow> rows = store.stages(tenant, request, true);
        var definition = ApprovalWorkflowQuorumDefinition.compile(rows.getFirst().definition());
        for (StageRow row : rows) {
            if (!"WAITING".equals(row.status())) continue;
            var stage = definition.stages().stream().filter(value -> value.key().equals(row.key())).findFirst().orElseThrow();
            var currentRows = store.stages(tenant, request, false);
            boolean closed = stage.predecessors().stream().allMatch(key -> currentRows.stream()
                    .anyMatch(predecessor -> predecessor.key().equals(key)
                            && List.of("APPROVED", "SKIPPED").contains(predecessor.status())));
            if (!closed) continue;
            boolean inactivePath = !stage.predecessors().isEmpty() && stage.predecessors().stream().allMatch(key -> currentRows.stream()
                    .anyMatch(predecessor -> predecessor.key().equals(key) && "SKIPPED".equals(predecessor.status())));
            if (inactivePath || !condition(tenant, request, stage)) skip(tenant, request, row);
            else activate(tenant, request, row, stage);
        }
    }

    private boolean condition(long tenant, UUID request, ApprovalWorkflowQuorumDefinition.Stage stage) {
        if (stage.routeCondition() == null) return true;
        var rows = store.jdbc.queryForList("""
                SELECT form.schema_payload::text AS schema,payload.payload::text AS payload
                  FROM apr_requests request JOIN apr_form_versions form ON form.tenant_id=request.tenant_id
                   AND form.form_version_id=request.form_version_id
                  JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id AND payload.request_id=request.request_id
                 WHERE request.tenant_id=:tenant AND request.request_id=:request FOR SHARE OF form,payload
                """, store.scope(tenant, request));
        if (rows.size() != 1) throw conflict();
        var row = rows.getFirst();
        return ApprovalWorkflowStageCondition.matches((String) row.get("schema"), store.object((String) row.get("payload")), stage.routeCondition());
    }

    private void skip(long tenant, UUID request, StageRow stage) {
        var p = store.scope(tenant, request).addValue("step", stage.stepId()).addValue("generation", stage.generation())
                .addValue("version", stage.version()).addValue("now", time(store.now()));
        changed(store.jdbc.update("""
                UPDATE apr_quorum_stage_runtime SET status='SKIPPED',version=version+1,completed_at=:now
                 WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation
                   AND status='WAITING' AND version=:version
                """, p));
        changed(store.jdbc.update("""
                UPDATE apr_steps SET status='SKIPPED',version=version+1,completed_at=:now,updated_at=:now
                 WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND status='WAITING'
                """, p));
        evidence.append(tenant, request, null, "Approval.Quorum.StageSkipped", Map.of("stepId", stage.stepId().toString(),
                "generation", stage.generation(), "definitionSha256", stage.context().pins().workflowDefinitionSha256(),
                "payloadRevision", stage.context().payloadRevision(), "payloadSha256", stage.context().payloadSha256()));
    }

    private void activate(long tenant, UUID request, StageRow row, ApprovalWorkflowQuorumDefinition.Stage stage) {
        Instant evaluatedAt = store.now();
        CandidatePool pool = authority instanceof ApprovalWorkflowBoundAuthority bound
                ?bound.activation(tenant,request,row.stepId(),row.generation(),evaluatedAt)
                :authority.candidates(row.context().pins(), request, stage, evaluatedAt);
        var candidates = evaluator.eligibleCandidates(row.context().pins(), stage.candidateRole(), pool,
                row.context().requesterUserId(), row.context().requesterPersonId(), store.now());
        if (row.generation() > 1) {
            var retained = store.jdbc.queryForList("""
                    SELECT retained_snapshots::text AS snapshots FROM apr_quorum_information_rounds
                     WHERE tenant_id=:tenant AND request_id=:request AND target_generation=:generation
                       AND status='RESPONDED' AND material_change=FALSE
                    """, store.scope(tenant, request).addValue("generation", row.generation()));
            if (retained.size() > 1) throw conflict();
            if (!retained.isEmpty()) {
                var previous = store.object((String) retained.getFirst().get("snapshots")).get(stage.key());
                if (previous != null) {
                    var frozen = store.json.convertValue(previous, Snapshot.class).candidates();
                    if (!candidates.containsAll(frozen)) throw new BaseException(ErrorCode.FORBIDDEN,
                            "A revoked or replacement candidate cannot change the retained denominator.");
                    candidates = frozen;
                }
            }
        }
        Instant now = store.now();
        if (pool == null || !pool.expiresAt().isAfter(now)) throw unavailable("Candidate enumeration expired before activation.");
        Snapshot snapshot = new Snapshot(row.context().pins(), request, row.stepId(), row.generation(), row.context().requesterUserId(),
                row.context().requesterPersonId(), row.context().payloadRevision(), row.context().payloadSha256(), row.context().rejectLength(),
                stage.candidateRole(), stage.quorum(), candidates, pool.authorityRevision(), now);
        Instant due = now.plusSeconds(Math.multiplyExact(stage.slaMinutes(), 60L));
        Instant requestDue = store.jdbc.queryForObject("SELECT due_at FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request",
                store.scope(tenant, request), java.sql.Timestamp.class).toInstant();
        if (requestDue.isBefore(due)) due = requestDue;
        if (!due.isAfter(now)) throw conflict();
        var p = store.scope(tenant, request).addValue("step", row.stepId()).addValue("generation", row.generation())
                .addValue("snapshot", store.json(snapshot)).addValue("poolHash", store.hash(store.json(candidates)))
                .addValue("count", candidates.size()).addValue("threshold", stage.quorum().threshold(candidates.size()))
                .addValue("now", time(now)).addValue("due", time(due)).addValue("version", row.version());
        changed(store.jdbc.update("""
                UPDATE apr_quorum_stage_runtime SET snapshot=CAST(:snapshot AS jsonb),candidate_sha256=:poolHash,
                    eligible_count=:count,threshold=:threshold,opened_at=:now,due_at=:due,status='IN_PROGRESS',version=version+1
                 WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation
                   AND status='WAITING' AND version=:version
                """, p));
        changed(store.jdbc.update("""
                UPDATE apr_steps SET status='IN_PROGRESS',started_at=:now,due_at=:due,version=version+1,updated_at=:now
                 WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND status='WAITING'
                """, p));
        for (Candidate candidate : candidates) {
            p.addValue("task", UUID.randomUUID()).addValue("principal", candidate.userId()).addValue("person", candidate.personPublicId())
                    .addValue("role", stage.candidateRole());
            store.jdbc.update("""
                    INSERT INTO apr_tasks(task_id,tenant_id,request_id,step_id,assignee_user_id,assignee_person_public_id,
                        candidate_role,status,claimed_at,due_at)
                    VALUES(:task,:tenant,:request,:step,:principal,:person,:role,'CLAIMED',:now,:due)
                    """, p);
            store.jdbc.update("""
                    INSERT INTO apr_quorum_candidates(tenant_id,request_id,step_id,generation,principal_user_id,principal_person_id,task_id)
                    VALUES(:tenant,:request,:step,:generation,:principal,:person,:task)
                    """, p);
        }
        var policy = row.context().policy();
        long duration = java.time.Duration.between(now, due).toMillis();
        for (String kind : List.of("WARNING", "BREACH")) {
            int percent = "WARNING".equals(kind) ? policy.warningPercent() : policy.breachPercent();
            store.jdbc.update("""
                    INSERT INTO apr_quorum_sla_timers(timer_id,tenant_id,request_id,step_id,generation,policy_version,kind,due_at)
                    VALUES(:timer,:tenant,:request,:step,:generation,:policy,:kind,:timerDue)
                    """, p.addValue("timer", UUID.randomUUID()).addValue("policy", policy.version()).addValue("kind", kind)
                    .addValue("timerDue", time(now.plusMillis((duration * percent + 99) / 100))));
        }
    }

    private void finishStage(long tenant, UUID request, UUID step, Outcome outcome, Instant now) {
        var p = store.scope(tenant, request).addValue("step", step).addValue("now", time(now))
                .addValue("reject", outcome == Outcome.REJECTED).addValue("taskStatus", outcome == Outcome.REJECTED ? "CANCELLED" : "SKIPPED");
        store.jdbc.update("""
                UPDATE apr_tasks SET status=:taskStatus,version=version+1,completed_at=:now,updated_at=:now
                 WHERE tenant_id=:tenant AND request_id=:request AND (:reject OR step_id=:step) AND status IN ('PENDING','CLAIMED')
                """, p);
        store.jdbc.update("""
                UPDATE apr_quorum_sla_timers SET status='CANCELLED',version=version+1,lease_owner=NULL,lease_until=NULL
                 WHERE tenant_id=:tenant AND request_id=:request AND (:reject OR step_id=:step) AND status IN ('PENDING','CLAIMED')
                """, p);
        if (outcome == Outcome.REJECTED) {
            store.jdbc.update("""
                    UPDATE apr_quorum_stage_runtime SET status='CANCELLED',version=version+1,completed_at=:now
                     WHERE tenant_id=:tenant AND request_id=:request AND status IN ('WAITING','IN_PROGRESS')
                    """, p);
            store.jdbc.update("""
                    UPDATE apr_steps SET status='CANCELLED',version=version+1,completed_at=:now,updated_at=:now
                     WHERE tenant_id=:tenant AND request_id=:request AND status IN ('WAITING','IN_PROGRESS')
                    """, p);
        }
    }
}
