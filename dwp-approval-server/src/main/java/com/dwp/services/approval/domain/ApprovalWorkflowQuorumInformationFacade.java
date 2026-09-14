package com.dwp.services.approval.domain;

import static com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof.Purpose.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApprovalWorkflowQuorumInformationFacade {
    private final ApprovalCommandRepository commands;
    private final ApprovalQueryRepository queries;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final ObjectProvider<ApprovalWorkflowQuorumAuthority> authorities;
    private final ApprovalFormPayloadNormalization normalizer;
    private final ApprovalWorkflowQuorumCommandMetadata metadata;
    private final ApprovalWorkAuthority work;
    private final ObjectProvider<ApprovalOwnerPredicateEvaluator> owners;
    private final AuditOutboxRecorder audit;
    private final ApprovalWorkflowInformationCompletionAdmissions admissions;
    private final ApprovalWorkflowInformationAttachments attachments;

    public ApprovalWorkflowQuorumInformationFacade(ApprovalCommandRepository commands, ApprovalQueryRepository queries,
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
            ObjectProvider<ApprovalWorkflowQuorumAuthority> authorities, ApprovalFormPayloadNormalization normalizer,
            ApprovalWorkflowQuorumCommandMetadata metadata, ApprovalWorkAuthority work,
            ObjectProvider<ApprovalOwnerPredicateEvaluator> owners, AuditOutboxRecorder audit) {
        this(commands, queries, jdbc, mapper, manager, authorities, normalizer, metadata, work, owners, audit, null);
    }

    public ApprovalWorkflowQuorumInformationFacade(ApprovalCommandRepository commands, ApprovalQueryRepository queries,
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
            ObjectProvider<ApprovalWorkflowQuorumAuthority> authorities, ApprovalFormPayloadNormalization normalizer,
            ApprovalWorkflowQuorumCommandMetadata metadata, ApprovalWorkAuthority work,
            ObjectProvider<ApprovalOwnerPredicateEvaluator> owners, AuditOutboxRecorder audit, ApprovalWorkflowInformationCompletionAdmissions admissions) {
        this(commands, queries, jdbc, mapper, manager, authorities, normalizer, metadata, work, owners, audit, admissions, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalWorkflowQuorumInformationFacade(ApprovalCommandRepository commands, ApprovalQueryRepository queries,
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
            ObjectProvider<ApprovalWorkflowQuorumAuthority> authorities, ApprovalFormPayloadNormalization normalizer,
            ApprovalWorkflowQuorumCommandMetadata metadata, ApprovalWorkAuthority work,
            ObjectProvider<ApprovalOwnerPredicateEvaluator> owners, AuditOutboxRecorder audit, ApprovalWorkflowInformationCompletionAdmissions admissions,
            ApprovalWorkflowInformationAttachments attachments) {
        this.commands = commands; this.queries = queries; this.jdbc = jdbc; this.mapper = mapper;
        transactions = new TransactionTemplate(manager); this.authorities = authorities; this.normalizer = normalizer;
        this.metadata = metadata; this.work = work; this.owners = owners; this.audit = audit; this.admissions = admissions;
        this.attachments = attachments;
    }

    public ApprovalCommandRepository.DecisionResult request(Actor actor, ApprovalQueryRepository.TaskAccess task,
            ApprovalDtos.DecisionRequest request, ApprovalWorkflowQuorumExpectedVoteView expected, Runnable ownerGuard) {
        if (expected == null || expected.expectedRequestVersion() == null || expected.expectedRequestVersion() < 0) throw conflict();
        var identity = metadata.current(actor, TASK_INFORMATION, task.summary().taskId());
        var command = new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(
                task.summary().requestId(), task.summary().taskId(), request.expectedVersion(), expected.expectedRequestVersion(),
                expected, identity.idempotencyKey(), request.comment(), identity.rawBodySha256());
        var engine = engine();
        var result = engine.request(actor, command,
                () -> { metadata.current(actor, TASK_INFORMATION, task.summary().taskId()); ownerGuard.run(); },
                admissions == null ? receipt -> { } : admissions.request(actor, command));
        completed(result); return new ApprovalCommandRepository.DecisionResult("REQUEST_INFO", "NEEDS_INFO");
    }

    public ApprovalDtos.RequestSummary reply(Actor actor, UUID requestId, ApprovalDtos.InformationResponseRequest request) {
        if (request.sourceGeneration() == null || request.sourceGeneration() < 1) throw conflict();
        var identity = metadata.current(actor, REQUEST_REPLY, requestId);
        var command = new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(requestId, request.expectedVersion(),
                request.sourceGeneration(), identity.idempotencyKey(), request.message(), request.payload(), identity.rawBodySha256());
        var engine = engine();
        var result = engine.reply(actor, command,
                normalizer, () -> { metadata.current(actor, REQUEST_REPLY, requestId); requireRequester(actor, requestId, request.expectedVersion()); },
                admissions == null ? receipt -> { } : admissions.reply(actor, command),
                attachments == null ? null : bound -> attachments.prepare(requestId, bound));
        completed(result); return queries.request(actor, requestId);
    }

    public ApprovalDtos.RequestDetail detail(Actor actor, ApprovalDtos.RequestDetail detail) {
        if (!"NEEDS_INFO".equals(detail.request().status())) return detail;
        if (actor.personPublicId() == null || detail.workflowId() == null || detail.formId() == null || detail.formVersionId() == null
                || !ApprovalWorkflowQuorum.sha256(detail.formSchemaSha256()) || detail.request().version() < 0
                || detail.payload() == null || detail.formSchema() == null) throw conflict();
        if (detail.formSchema().containsKey("schemaContract") && !new ApprovalFormSchemaV2Compiler().compile(detail.formSchema())
                .sha256().equals(detail.formSchemaSha256())) throw conflict();
        var store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper);
        var bound = ApprovalWorkflowQuorumInformationContext.require(store, actor, detail.request().requestId(), "NEEDS_INFO");
        if (!detail.workflowId().equals(bound.workflowId()) || !detail.formId().equals(bound.formId())
                || !detail.formVersionId().equals(bound.context().formVersionId())
                || !detail.formSchemaSha256().equals(bound.context().pins().formSchemaSha256())
                || detail.request().version() != bound.requestVersion()
                || !ApprovalFormSchemaV2Canonical.freeze(detail.formSchema()).equals(ApprovalFormSchemaV2Canonical.freeze(store.object(bound.schema())))
                || !ApprovalFormSchemaV2Canonical.freeze(detail.payload()).equals(bound.payload())) throw conflict();
        if (bound.context().requesterUserId() != actor.userId() || !actor.personPublicId().equals(bound.context().requesterPersonId())) return detail;
        var rows = jdbc.queryForList("SELECT round_id,source_generation,target_generation,context::text FROM apr_quorum_information_rounds "
                + "WHERE tenant_id=:tenant AND request_id=:request AND status='OPEN'", store.scope(actor.tenantId(), detail.request().requestId()));
        if (rows.isEmpty()) return detail;
        if (rows.size() != 1) throw conflict(); var row = rows.getFirst();
        var frozen = store.read((String) row.get("context"), ApprovalWorkflowQuorumRuntimeStore.Context.class);
        if (!bound.context().pins().equals(frozen.pins()) || !bound.context().formVersionId().equals(frozen.formVersionId())
                || bound.context().requesterUserId() != frozen.requesterUserId()
                || !bound.context().requesterPersonId().equals(frozen.requesterPersonId())
                || bound.context().payloadRevision() != frozen.payloadRevision()
                || !bound.context().payloadSha256().equals(frozen.payloadSha256())) throw conflict();
        long generation = ((Number) row.get("source_generation")).longValue();
        var snapshot = new ApprovalDtos.QuorumInformationSnapshot((UUID) row.get("round_id"), generation,
                ((Number) row.get("target_generation")).longValue(), new ApprovalDtos.WorkflowRuntimePins(frozen.pins().workflowVersionId(),
                    frozen.pins().workflowVersion(), frozen.pins().workflowDefinitionSha256(), frozen.pins().formSchemaSha256(),
                    frozen.pins().policyVersion(), frozen.pins().policySha256()),
                frozen.payloadRevision(), frozen.payloadSha256());
        return new ApprovalDtos.RequestDetail(detail.request(), detail.workflowId(), detail.formId(), detail.payload(), detail.formSchema(),
                detail.timeline(), detail.formVersionId(), detail.formSchemaSha256(), generation, snapshot);
    }

    private ApprovalWorkflowQuorumInformationRuntime engine() {
        var source = authorities.getIfAvailable();
        if (source == null) throw ApprovalWorkflowQuorum.unavailable("The dedicated current quorum authority source is unavailable.");
        return new ApprovalWorkflowQuorumInformationRuntime(jdbc, mapper, transactions, source, audit, commands::matchesRouteCondition);
    }
    private void requireRequester(Actor actor, UUID request, long version) {
        if (!actor.equals(work.require("ACTION.APPROVAL_REQUEST:UPDATE", true))
                || !actor.equals(work.requireCurrent("ACTION.APPROVAL_FORM:VIEW"))) throw new BaseException(ErrorCode.FORBIDDEN);
        if (ApprovalPilotAuthorizationContext.requiresPredicate("predicate.approval.own-request.v1")) {
            var owner = owners.getIfAvailable();
            if (owner == null) throw ApprovalWorkflowQuorum.unavailable("Current owner authority is unavailable.");
            owner.lockOwnedRequest(actor, request, version);
        }
    }
    private void completed(ApprovalWorkflowQuorumInformationRuntime.Receipt receipt) {
        if ("UNKNOWN".equals(receipt.status())) throw new ApprovalWorkflowQuorumInformationPending(receipt);
        if (!"COMPLETED".equals(receipt.status())) throw conflict();
    }
    private BaseException conflict() { return ApprovalWorkflowQuorumRuntimeStore.conflict(); }
}
