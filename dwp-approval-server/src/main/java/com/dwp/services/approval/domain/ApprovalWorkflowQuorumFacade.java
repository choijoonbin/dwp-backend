package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApprovalWorkflowQuorumFacade {
    private final ApprovalCommandRepository commands;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final ObjectProvider<ApprovalWorkflowQuorumAuthority> authorities;
    private final AuditOutboxRecorder audit;
    private final ApprovalWorkflowQuorumInformationFacade information;
    private java.util.function.Consumer<ApprovalWorkflowQuorumSlaRuntime> slaInitializer;
    void bindSlaInitializer(java.util.function.Consumer<ApprovalWorkflowQuorumSlaRuntime> initializer) { slaInitializer = java.util.Objects.requireNonNull(initializer); }

    public record ExpectedVote(long generation, long stageVersion, Pins pins, int payloadRevision, String payloadSha256,
            Long expectedRequestVersion) implements ApprovalWorkflowQuorumExpectedVoteView {
        public ExpectedVote(long generation, long stageVersion, Pins pins, int payloadRevision, String payloadSha256) {
            this(generation, stageVersion, pins, payloadRevision, payloadSha256, null);
        }
        public ExpectedVote {
            if (generation < 1 || stageVersion < 1 || pins == null || payloadRevision < 1 || !sha256(payloadSha256)
                    || (expectedRequestVersion != null && (expectedRequestVersion < 0 || expectedRequestVersion > 9_007_199_254_740_991L))) {
                throw invalid("Exact quorum vote and payload evidence is required.");
            }
        }
    }

    public ApprovalWorkflowQuorumFacade(ApprovalCommandRepository commands, NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper, PlatformTransactionManager manager,
            ObjectProvider<ApprovalWorkflowQuorumAuthority> authorities, AuditOutboxRecorder audit) {
        this(commands, jdbc, mapper, manager, authorities, audit, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalWorkflowQuorumFacade(ApprovalCommandRepository commands, NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper, PlatformTransactionManager manager,
            ObjectProvider<ApprovalWorkflowQuorumAuthority> authorities, AuditOutboxRecorder audit,
            ApprovalWorkflowQuorumInformationFacade information) {
        this.commands = commands;
        this.jdbc = jdbc;
        this.mapper = mapper;
        transactions = new TransactionTemplate(manager);
        this.authorities = authorities;
        this.audit = audit;
        this.information = information;
    }

    void prepareDecision(ApprovalRequestContext.Actor actor, ApprovalQueryRepository.TaskAccess task, ExpectedVote expected) {
        if (expected == null) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper);
        store.lockRequest(actor.tenantId(), task.summary().requestId());
        if (expected.expectedRequestVersion() != null && !expected.expectedRequestVersion().equals(jdbc.queryForObject(
                "SELECT version FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request",
                store.scope(actor.tenantId(), task.summary().requestId()), Long.class))) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var stages = store.stages(actor.tenantId(), task.summary().requestId(), true);
        var seats = jdbc.queryForList("SELECT step_id FROM apr_quorum_candidates WHERE tenant_id=:tenant AND request_id=:request "
                + "AND task_id=:task AND generation=:generation", store.scope(actor.tenantId(), task.summary().requestId())
                .addValue("task", task.summary().taskId()).addValue("generation", expected.generation()));
        if (seats.size() != 1 || stages.stream().noneMatch(stage -> stage.stepId().equals(seats.getFirst().get("step_id"))
                && "IN_PROGRESS".equals(stage.status()) && stage.version() == expected.stageVersion()
                && stage.context().pins().equals(expected.pins()))) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
    }

    ApprovalCommandRepository.DecisionResult requestInformation(ApprovalRequestContext.Actor actor, ApprovalQueryRepository.TaskAccess task,
            ApprovalDtos.DecisionRequest request, ExpectedVote expected, Runnable ownerGuard) {
        if (information == null) throw unavailable("The information runtime is unavailable.");
        return information.request(actor, task, request, expected, ownerGuard);
    }
    ApprovalDtos.RequestSummary reply(ApprovalRequestContext.Actor actor, UUID requestId, ApprovalDtos.InformationResponseRequest request) {
        if (information == null) throw unavailable("The information runtime is unavailable.");
        return information.reply(actor, requestId, request);
    }
    ApprovalDtos.RequestDetail informationDetail(ApprovalRequestContext.Actor actor, ApprovalDtos.RequestDetail detail) {
        return information == null ? detail : information.detail(actor, detail);
    }

    private ApprovalWorkflowQuorumAuthority authority() {
        var source = authorities.getIfAvailable();
        if (source == null) throw unavailable("The dedicated current workflow role authority source is unavailable.");
        return source;
    }

    private ApprovalWorkflowQuorumRuntime runtime() {
        return new ApprovalWorkflowQuorumRuntime(jdbc, mapper, transactions, authority(), audit);
    }

    public void submit(ApprovalRequestContext.Actor actor, UUID request, long version, String correlation) {
        transactions.executeWithoutResult(status -> commands.submitQuorum(actor, request, version, correlation, runtime()));
    }

    public void cancel(ApprovalRequestContext.Actor actor, UUID request) {
        transactions.executeWithoutResult(status -> new ApprovalWorkflowQuorumCancellation(
                new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper), audit).cancel(
                actor.tenantId(), request, actor.userId(), actor.personPublicId()));
    }

    public ApprovalCommandRepository.DecisionResult decide(ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task, ApprovalDtos.DecisionRequest decision, ExpectedVote expected) {
        if (expected == null) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        return transactions.execute(status -> {
            new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper).lockRequest(actor.tenantId(), task.summary().requestId());
            // Principal identity is derived from the immutable seat, never from caller role/source IDs.
            var p = new MapSqlParameterSource().addValue("tenant", actor.tenantId())
                    .addValue("request", task.summary().requestId()).addValue("task", task.summary().taskId())
                    .addValue("generation", expected.generation());
            var seats = jdbc.queryForList("""
                    SELECT candidate.principal_user_id,candidate.step_id
                      FROM apr_quorum_candidates candidate
                     WHERE tenant_id=:tenant AND request_id=:request AND task_id=:task AND generation=:generation
                    """, p);
            if (seats.size() != 1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            var payloads = jdbc.queryForList("""
                    SELECT schema_version,payload_sha256 FROM apr_request_payloads
                     WHERE tenant_id=:tenant AND request_id=:request FOR SHARE
                    """, p);
            if (payloads.size() != 1 || ((Number) payloads.getFirst().get("schema_version")).intValue() != expected.payloadRevision()
                    || !expected.payloadSha256().equals(((String) payloads.getFirst().get("payload_sha256")).strip())) {
                throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            }
            Decision choice;
            try { choice = Decision.valueOf(decision.decision().strip().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException exception) { throw invalid("Only an explicit APPROVE or REJECT quorum vote is supported."); }
            var seat = seats.getFirst();
            var receipt = runtime().vote(new ApprovalWorkflowQuorumRuntime.VoteCommand(actor.tenantId(),
                    task.summary().requestId(), (UUID) seat.get("step_id"), expected.generation(), task.summary().taskId(),
                    decision.expectedVersion(), expected.stageVersion(), expected.pins(), actor.userId(),
                    ((Number) seat.get("principal_user_id")).longValue(), choice, decision.comment()));
            return new ApprovalCommandRepository.DecisionResult(choice.name(), receipt.requestStatus());
        });
    }

    public ApprovalDtos.TaskDetail detail(ApprovalRequestContext.Actor actor, ApprovalDtos.TaskDetail detail) {
        if (detail.contentAccess() == null || !"FULL".equals(detail.contentAccess().state())) return detail;
        var rows = jdbc.queryForList("""
                SELECT stage.generation,stage.version,stage.context::text,candidate.principal_person_id,request.version AS request_version
                  FROM apr_quorum_candidates candidate JOIN apr_quorum_stage_runtime stage
                    ON stage.tenant_id=candidate.tenant_id AND stage.request_id=candidate.request_id
                   AND stage.step_id=candidate.step_id AND stage.generation=candidate.generation
                  JOIN apr_requests request ON request.tenant_id=candidate.tenant_id AND request.request_id=candidate.request_id
                 WHERE candidate.tenant_id=:tenant AND candidate.request_id=:request AND candidate.task_id=:task
                """, new MapSqlParameterSource().addValue("tenant", actor.tenantId())
                .addValue("request", detail.task().requestId()).addValue("task", detail.task().taskId()));
        if (rows.isEmpty()) return detail;
        if (rows.size() != 1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var row = rows.getFirst();
        var context = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper).read((String) row.get("context"),
                ApprovalWorkflowQuorumRuntimeStore.Context.class);
        var snapshot = new ApprovalDtos.QuorumTaskSnapshot(((Number) row.get("generation")).longValue(),
                ((Number) row.get("version")).longValue(), new ApprovalDtos.WorkflowRuntimePins(context.pins().workflowVersionId(),
                        context.pins().workflowVersion(), context.pins().workflowDefinitionSha256(), context.pins().formSchemaSha256(),
                        context.pins().policyVersion(), context.pins().policySha256()),
                context.payloadRevision(), context.payloadSha256(), (UUID) row.get("principal_person_id"), ((Number) row.get("request_version")).longValue());
        return new ApprovalDtos.TaskDetail(detail.task(), detail.payload(), detail.formSchema(), detail.timeline(), detail.canClaim(),
                detail.canDecide() && authorities.getIfAvailable() != null, detail.selfApprovalBlocked(), detail.contentAccess(), snapshot);
    }

    public ApprovalWorkflowQuorumSimulation.Result simulate(ApprovalRequestContext.Actor actor, UUID request,
            ApprovalWorkflowQuorumSimulation.Input input) {
        return new ApprovalWorkflowQuorumReadOnlySimulation(jdbc, mapper, transactions, authority()).simulate(
                actor.tenantId(), request, input, () -> validateReadonlyDraft(actor, request));
    }

    private void validateReadonlyDraft(ApprovalRequestContext.Actor actor, UUID request) {
        var rows = jdbc.queryForList("""
                SELECT request.title,form_version.schema_payload::text AS schema,payload.payload::text AS payload,
                       binding.binding_type,binding.condition_payload::text AS condition
                  FROM apr_requests request
                  JOIN apr_workflow_versions workflow_version ON workflow_version.tenant_id=request.tenant_id
                   AND workflow_version.workflow_version_id=request.workflow_version_id AND workflow_version.lifecycle_state='PUBLISHED'
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=workflow_version.tenant_id
                   AND workflow.workflow_id=workflow_version.workflow_id AND workflow.lifecycle_state='PUBLISHED'
                  JOIN apr_form_versions form_version ON form_version.tenant_id=request.tenant_id
                   AND form_version.form_version_id=request.form_version_id AND form_version.lifecycle_state='PUBLISHED'
                  JOIN apr_forms form ON form.tenant_id=form_version.tenant_id AND form.form_id=form_version.form_id
                   AND form.lifecycle_state='PUBLISHED' AND form.management_resource_set_key=request.management_resource_set_key
                  JOIN apr_form_categories category ON category.tenant_id=form.tenant_id AND category.category_id=form.category_id
                   AND category.lifecycle_state='ACTIVE' AND category.management_resource_set_key=request.management_resource_set_key
                  JOIN apr_form_workflow_bindings binding ON binding.tenant_id=request.tenant_id AND binding.form_id=form.form_id
                   AND binding.workflow_id=workflow.workflow_id AND binding.lifecycle_state='ACTIVE'
                  JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id AND payload.request_id=request.request_id
                 WHERE request.tenant_id=:tenant AND request.request_id=:request AND request.requester_user_id=:actor
                   AND request.requester_person_public_id=:person AND request.status='DRAFT' AND request.deleted_at IS NULL
                   AND workflow.management_resource_set_key=request.management_resource_set_key
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=CURRENT_TIMESTAMP)
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>CURRENT_TIMESTAMP)
                   AND (binding.effective_from IS NULL OR binding.effective_from<=CURRENT_TIMESTAMP)
                   AND (binding.effective_to IS NULL OR binding.effective_to>CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource().addValue("tenant", actor.tenantId()).addValue("request", request)
                .addValue("actor", actor.userId()).addValue("person", actor.personPublicId()));
        if (rows.size() != 1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var row = rows.getFirst();
        if (!(row.get("title") instanceof String title) || title.isBlank()) throw invalid("Approval title is required.");
        String schema = (String) row.get("schema");
        var support = new ApprovalCommandPayloadSupport(mapper);
        var payload = support.object((String) row.get("payload"), "Stored request payload is invalid.");
        var normalized = commands.normalizeRequestPayload(schema, payload, true);
        if (support.isTypedFormSchema(schema) && !ApprovalFormSchemaV2Canonical.freeze(payload).equals(normalized)) {
            throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        }
        if ("CONDITIONAL".equals(row.get("binding_type"))
                && !commands.matchesRouteCondition((String) row.get("condition"), payload, schema)) {
            throw invalid("The selected workflow route does not match the draft.");
        }
    }

    public void pollSla(String owner, int leaseSeconds, int limit) {
        var runtime = new ApprovalWorkflowQuorumSlaRuntime(jdbc, mapper, transactions, audit);
        if (slaInitializer == null) throw unavailable("The dedicated SYSTEM_SLA producer is unavailable.");
        slaInitializer.accept(runtime);
        runtime.finishClaimed(runtime.claim(owner, leaseSeconds, limit));
    }
}
