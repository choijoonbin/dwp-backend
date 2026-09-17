package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffIdentity;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffOutboxRepository;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalHighRiskCommandGuard;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalService {

    private final ApprovalQueryRepository queries;
    private final ApprovalCommandRepository commands;
    private final AuditOutboxRecorder audit;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalHighRiskCommandGuard highRiskGuard;
    private final ApprovalOwnerPredicateEvaluator ownerPredicates;
    private final ApprovalTaskGovernance taskGovernance;
    private final ApprovalWorkflowQuorumFacade quorum;
    private final ApprovalWorkflowManagementCommands workflowCommands;
    private final ApprovalWorkflowDecisionCommands workflowDecisions;
    private final ApprovalDelegationManagement delegationManagement;
    private final DwaionProposalHandoffOutboxRepository dwaionHandoffs;

    public ApprovalService(ApprovalQueryRepository queries, ApprovalCommandRepository commands,
            AuditOutboxRecorder audit, ApprovalIdentityDirectory identities) {
        this(queries, commands, audit, identities, null, null, null, null);
    }

    public ApprovalService(
            ApprovalQueryRepository queries,
            ApprovalCommandRepository commands,
            AuditOutboxRecorder audit,
            ApprovalIdentityDirectory identities,
            ApprovalHighRiskCommandGuard highRiskGuard,
            ApprovalOwnerPredicateEvaluator ownerPredicates) {
        this(queries, commands, audit, identities, highRiskGuard, ownerPredicates, null, null);
    }

    public ApprovalService(ApprovalQueryRepository queries, ApprovalCommandRepository commands,
            AuditOutboxRecorder audit, ApprovalIdentityDirectory identities,
            ApprovalHighRiskCommandGuard highRiskGuard, ApprovalOwnerPredicateEvaluator ownerPredicates,
            ApprovalWorkflowQuorumFacade quorum) {
        this(queries, commands, audit, identities, highRiskGuard, ownerPredicates, quorum, null);
    }

    @Autowired
    public ApprovalService(ApprovalQueryRepository queries, ApprovalCommandRepository commands,
            AuditOutboxRecorder audit, ApprovalIdentityDirectory identities,
            ApprovalHighRiskCommandGuard highRiskGuard, ApprovalOwnerPredicateEvaluator ownerPredicates,
            ApprovalWorkflowQuorumFacade quorum,
            DwaionProposalHandoffOutboxRepository dwaionHandoffs) {
        this.queries = queries;
        this.commands = commands;
        this.audit = audit;
        this.identities = identities;
        this.highRiskGuard = highRiskGuard;
        this.ownerPredicates = ownerPredicates;
        this.quorum = quorum;
        this.dwaionHandoffs = dwaionHandoffs;
        workflowCommands = new ApprovalWorkflowManagementCommands(commands, queries, audit);
        delegationManagement = new ApprovalDelegationManagement(
                queries, commands, audit, identities, ownerPredicates);
        this.taskGovernance = new ApprovalTaskGovernance(
                queries, identities, ownerPredicates);
        workflowDecisions = new ApprovalWorkflowDecisionCommands(queries, commands, identities, ownerPredicates, taskGovernance, quorum);
    }

    @Transactional
    public ApprovalDtos.HomeResponse home() {
        ApprovalRequestContext.Actor actor = prepare();
        boolean governedWorkSurface = ApprovalPilotAuthorizationContext.current().isPresent();
        boolean canUseTasks = governedWorkSurface
                ? actor.hasPermission("ACTION.APPROVAL_TASK", "VIEW")
                : actor.hasPermission("ACTION.APPROVAL_TASK", "VIEW", "MANAGE");
        boolean canUseRequests = governedWorkSurface
                ? actor.hasPermission("ACTION.APPROVAL_REQUEST", "VIEW")
                : actor.hasPermission("ACTION.APPROVAL_REQUEST", "VIEW", "MANAGE");
        ApprovalDtos.ApprovalMetrics rawMetrics = canUseTasks || canUseRequests
                ? queries.metrics(actor)
                : ApprovalResponseAssembler.emptyMetrics();
        ApprovalDtos.ApprovalMetrics metrics = new ApprovalDtos.ApprovalMetrics(
                canUseTasks ? rawMetrics.pending() : 0,
                canUseTasks ? rawMetrics.dueToday() : 0,
                canUseTasks ? rawMetrics.overdue() : 0,
                canUseTasks ? rawMetrics.needsInformation() : 0,
                canUseRequests ? rawMetrics.myRequestsInFlight() : 0,
                canUseRequests ? rawMetrics.averageCycleHours() : 0,
                canUseRequests ? rawMetrics.slaCompliancePercent() : 100);
        List<ApprovalDtos.TaskSummary> tasks = canUseTasks
                ? queries.tasks(actor, "INBOX", 6)
                : List.of();
        List<ApprovalDtos.RequestSummary> requests = canUseRequests
                ? queries.requests(actor, "SUBMITTED", 5)
                : List.of();
        boolean canViewOperations = !governedWorkSurface && actor.hasPermission(
                "ADMIN.APPROVAL_OPERATIONS", "VIEW", "MANAGE");
        ApprovalDtos.AdminPulse pulse = canViewOperations
                ? queries.adminPulse(actor.tenantId())
                : null;
        return new ApprovalDtos.HomeResponse(
                Instant.now(),
                metrics,
                tasks,
                requests,
                canUseTasks || canUseRequests ? queries.flow(actor) : List.of(),
                ApprovalResponseAssembler.insights(metrics, pulse),
                canViewOperations,
                pulse);
    }

    @Transactional
    public List<ApprovalDtos.TaskSummary> tasks(String view, int limit) {
        return queries.tasks(prepare(), view, limit);
    }

    @Transactional
    public ApprovalDtos.TaskDetail task(UUID taskId) {
        var actor = prepare();
        var detail = taskGovernance.detail(actor, taskId);
        return quorum == null ? detail : quorum.detail(actor, detail);
    }
    @Transactional
    public ApprovalDtos.TaskDetail claim(
            UUID taskId,
            long expectedVersion,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalWorkflowCommandLiveFence.task(commands.jdbc, actor.tenantId(), taskId);
        ApprovalQueryRepository.TaskAccess task = queries.taskDetail(actor, taskId);
        if (ApprovalPilotAuthorizationContext.requiresPredicate(
                "predicate.approval-task-claimable.v1")) {
            requireOwnerPredicates();
            ownerPredicates.lockClaimableTask(actor, task, expectedVersion);
        } else {
            ApprovalLegacyDelegationGuard.verify(actor, task, identities);
        }
        commands.claim(actor, task, expectedVersion, correlationId);
        record(actor, "approval.task.claimed", "APPROVAL_TASK", taskId.toString(),
                correlationId, Map.of("requestId", task.summary().requestId().toString()));
        return task(taskId);
    }

    @Transactional(noRollbackFor = ApprovalWorkflowQuorumInformationPending.class)
    public ApprovalDtos.TaskDetail decide(UUID taskId, ApprovalDtos.DecisionRequest request, String correlationId) {
        return decide(taskId, request, correlationId, ApprovalWorkflowQuorumBindings.expected(request.quorum()));
    }

    @Transactional(noRollbackFor = ApprovalWorkflowQuorumInformationPending.class)
    public ApprovalDtos.TaskDetail decide(UUID taskId, ApprovalDtos.DecisionRequest request, String correlationId,
            ApprovalWorkflowQuorumFacade.ExpectedVote expectedQuorum) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalWorkflowCommandLiveFence.task(commands.jdbc, actor.tenantId(), taskId);
        ApprovalQueryRepository.TaskAccess task = queries.taskDetail(actor, taskId);
        var result = workflowDecisions.decide(actor, task, request, correlationId, expectedQuorum);
        record(actor, "approval.task.decided", "APPROVAL_TASK", taskId.toString(),
                correlationId,
                Map.of("requestId", task.summary().requestId().toString(),
                        "decision", result.decision(),
                        "requestStatus", result.requestStatus()));
        return task(taskId);
    }

    @Transactional
    public List<ApprovalDtos.RequestSummary> requests(String view, int limit) {
        return queries.requests(prepare(), view, limit);
    }

    @Transactional
    public ApprovalDtos.RequestSummary request(UUID requestId) {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.request(actor, requestId);
    }

    @Transactional
    public ApprovalDtos.RequestDetail requestDetail(UUID requestId) {
        ApprovalRequestContext.Actor actor = prepare();
        var detail = queries.requestDetail(actor, requestId);
        return commands.quorumWorkflow(actor.tenantId(), requestId) == null ? detail : requireQuorum().informationDetail(actor, detail);
    }

    @Transactional
    public ApprovalDtos.RequestSummary create(
            ApprovalDtos.CreateRequest request,
            String correlationId) {
        return create(request, correlationId, null);
    }

    @Transactional
    public ApprovalDtos.RequestSummary create(
            ApprovalDtos.CreateRequest request,
            String correlationId,
            DwaionProposalHandoffIdentity handoffIdentity) {
        ApprovalRequestContext.Actor actor = prepare();
        if (ownerPredicates != null && ApprovalPilotAuthorizationContext.current().isPresent()) {
            ownerPredicates.requirePublishedForm(actor, request.formId(), request.workflowId());
        }
        UUID requestId = commands.createDraft(actor, request, correlationId);
        if (request.dwaionProposalHandoff() != null) {
            if (dwaionHandoffs == null || handoffIdentity == null) {
                throw new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The DWAI-ON Approval completion bridge is unavailable.");
            }
            dwaionHandoffs.bindDraft(actor, requestId, request.dwaionProposalHandoff(),
                    handoffIdentity, correlationId);
        }
        record(actor, "approval.request.drafted", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of("workflowId", request.workflowId().toString()));
        return queries.request(actor, requestId);
    }

    @Transactional
    public ApprovalDtos.RequestDetail updateDraft(
            UUID requestId,
            ApprovalDtos.UpdateDraftRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        lockOwnedRequest(actor, requestId, request.expectedVersion());
        commands.updateDraft(actor, requestId, request, correlationId);
        record(actor, "approval.request.draft.updated", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of("workflowId", request.workflowId().toString()));
        return queries.requestDetail(actor, requestId);
    }

    @Transactional
    public ApprovalDtos.RequestSummary submit(
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        lockOwnedRequest(actor, requestId, expectedVersion);
        if (commands.quorumWorkflow(actor.tenantId(), requestId) != null) {
            requireQuorum().submit(actor, requestId, expectedVersion, correlationId);
        } else {
            taskGovernance.requireEligibleCandidateRoles(actor, queries.requestCandidateRoles(actor, requestId));
            commands.submit(actor, requestId, expectedVersion, correlationId);
        }
        record(actor, "approval.request.submitted", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of());
        ApprovalDtos.RequestSummary result = queries.request(actor, requestId);
        if (dwaionHandoffs != null) {
            dwaionHandoffs.markDomainCommitted(actor, requestId, result, correlationId);
        }
        return result;
    }

    @Transactional
    public ApprovalDtos.RequestPreflight preflight(UUID requestId, long expectedVersion) {
        ApprovalRequestContext.Actor actor = prepare();
        List<ApprovalDtos.RequestPreflightCheck> checks = new java.util.ArrayList<>();
        if (commands.quorumWorkflow(actor.tenantId(), requestId) == null) {
            commands.preflightLegacySubmit(actor, requestId, expectedVersion);
            checks.add(new ApprovalDtos.RequestPreflightCheck(
                    "OWNED_DRAFT", "PASS", "The current actor owns the exact draft version."));
            checks.add(new ApprovalDtos.RequestPreflightCheck(
                    "CONCURRENCY", "PASS", "The stored draft version matches the requested version."));
            checks.add(new ApprovalDtos.RequestPreflightCheck(
                    "FORM_SCHEMA_AND_ROUTE", "PASS", "The stored payload matches its published form and route."));
            checks.add(new ApprovalDtos.RequestPreflightCheck(
                    "ATTACHMENT_EVIDENCE", "PASS", "All submission attachments are sealed for this version."));
            taskGovernance.requireEligibleCandidateRoles(actor, queries.requestCandidateRoles(actor, requestId));
            checks.add(new ApprovalDtos.RequestPreflightCheck(
                    "CANDIDATE_AUTHORITY", "PASS", "Every configured approver role is active and staffed."));
            return new ApprovalDtos.RequestPreflight(
                    requestId, expectedVersion, true, "LEGACY_SEQUENTIAL", checks, Instant.now(), null);
        }
        ApprovalWorkflowQuorumSimulation.Result result = requireQuorum().preflight(actor, requestId);
        commands.prepareQuorumSubmit(actor, requestId, expectedVersion);
        checks.add(new ApprovalDtos.RequestPreflightCheck(
                "OWNED_DRAFT", "PASS", "The current actor owns the exact draft version."));
        checks.add(new ApprovalDtos.RequestPreflightCheck(
                "CONCURRENCY", "PASS", "The stored draft version matches the requested version."));
        checks.add(new ApprovalDtos.RequestPreflightCheck(
                "FORM_SCHEMA_AND_ROUTE", "PASS", "The stored payload matches its pinned form and conditional route."));
        checks.add(new ApprovalDtos.RequestPreflightCheck(
                "ATTACHMENT_EVIDENCE", "PASS", "All submission attachments are sealed for this version."));
        boolean ready = result.status() != ApprovalWorkflowQuorumSimulation.Status.BLOCKED
                && result.status() != ApprovalWorkflowQuorumSimulation.Status.UNKNOWN;
        if (result.issues().isEmpty()) {
            checks.add(new ApprovalDtos.RequestPreflightCheck(
                    "CANDIDATE_AUTHORITY", "PASS", "Current candidate authority satisfies every executable stage."));
            checks.add(new ApprovalDtos.RequestPreflightCheck(
                    "SEGREGATION_OF_DUTIES", "PASS", "Requester exclusion and quorum rules were evaluated from pinned policy."));
        } else {
            for (ApprovalWorkflowQuorumSimulation.Issue issue : result.issues()) {
                checks.add(new ApprovalDtos.RequestPreflightCheck(
                        issue.code(), "BLOCKED", issue.stageKey() == null ? "WORKFLOW" : issue.stageKey()));
            }
        }
        return new ApprovalDtos.RequestPreflight(
                requestId, expectedVersion, ready, result.schemaContract(), checks,
                result.evaluatedAt(), result.validUntil());
    }

    @Transactional
    public ApprovalDtos.RequestSummary withdraw(
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        lockOwnedRequest(actor, requestId, expectedVersion);
        if (commands.quorumWorkflow(actor.tenantId(), requestId) != null) requireQuorum().cancel(actor, requestId);
        commands.withdraw(actor, requestId, expectedVersion, correlationId);
        record(actor, "approval.request.withdrawn", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of());
        return queries.request(actor, requestId);
    }

    @Transactional(noRollbackFor = ApprovalWorkflowQuorumInformationPending.class)
    public ApprovalDtos.RequestSummary respondToInformationRequest(
            UUID requestId,
            ApprovalDtos.InformationResponseRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        if (commands.quorumWorkflow(actor.tenantId(), requestId) != null) return requireQuorum().reply(actor, requestId, request);
        if (request.sourceGeneration() != null) throw new com.dwp.core.exception.BaseException(com.dwp.core.common.ErrorCode.FORBIDDEN);
        return workflowDecisions.respondLegacy(actor, requestId, request, correlationId,
                () -> lockOwnedRequest(actor, requestId, request.expectedVersion()),
                () -> record(actor, "approval.request.information.responded", "APPROVAL_REQUEST", requestId.toString(), correlationId, Map.of()));
    }

    @Transactional
    public List<ApprovalDtos.WorkflowSummary> publishedWorkflows() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.publishedWorkflowsForWork(actor.tenantId());
    }

    @Transactional
    public ApprovalDtos.RequestTemplate publishedTemplate(UUID workflowId) {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.publishedTemplate(actor.tenantId(), workflowId);
    }

    @Transactional
    public List<ApprovalDtos.FormSummary> publishedForms() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.publishedForms(actor.tenantId());
    }

    @Transactional
    public ApprovalDtos.RequestTemplate publishedTemplateByForm(UUID formId) {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.publishedTemplateByForm(actor.tenantId(), formId);
    }

    @Transactional
    public List<ApprovalDtos.DelegationSummary> delegations() {
        return delegationManagement.list(prepare());
    }

    @Transactional
    public List<ApprovalDtos.DelegationSummary> createDelegation(
            ApprovalDtos.CreateDelegationRequest request,
            String correlationId) {
        return delegationManagement.create(prepare(), request, correlationId);
    }

    @Transactional
    public List<ApprovalDtos.DelegationSummary> updateDelegation(
            UUID delegationId,
            ApprovalDelegationUpdateRequest request,
            String idempotencyKey,
            String correlationId) {
        return delegationManagement.update(
                prepare(), delegationId, request, idempotencyKey, correlationId);
    }

    @Transactional
    public List<ApprovalDtos.DelegationCandidate> delegationCandidates(String query, int limit) {
        return delegationManagement.candidates(ApprovalRequestContext.require(), query, limit);
    }

    @Transactional
    public List<ApprovalDtos.DelegationSummary> revokeDelegation(
            UUID delegationId,
            long expectedVersion,
            String correlationId) {
        return delegationManagement.revoke(
                prepare(), delegationId, expectedVersion, correlationId);
    }

    @Transactional
    public ApprovalDtos.AdminPulse adminOverview() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.adminPulse(actor.tenantId());
    }

    @Transactional
    public List<ApprovalDtos.WorkflowSummary> workflows() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.workflows(actor.tenantId(), false);
    }

    @Transactional
    public ApprovalDtos.WorkflowDetail workflow(UUID workflowId) {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.workflow(actor.tenantId(), workflowId);
    }

    @Transactional
    public ApprovalDtos.WorkflowDetail createWorkflowDraft(ApprovalDtos.CreateWorkflowDraftRequest request, String correlationId) {
        return createWorkflowDraft(request, correlationId, request.typedDefinition());
    }

    @Transactional
    public ApprovalDtos.WorkflowDetail createWorkflowDraft(ApprovalDtos.CreateWorkflowDraftRequest request,
            String correlationId, Map<String, Object> typedDefinition) {
        return workflowCommands.create(prepare(), request, correlationId, typedDefinition);
    }

    @Transactional
    public ApprovalDtos.WorkflowDetail updateWorkflowDraft(UUID workflowId, ApprovalDtos.UpdateWorkflowDraftRequest request,
            String correlationId) {
        return updateWorkflowDraft(workflowId, request, correlationId, request.typedDefinition());
    }

    @Transactional
    public ApprovalDtos.WorkflowDetail updateWorkflowDraft(UUID workflowId, ApprovalDtos.UpdateWorkflowDraftRequest request,
            String correlationId, Map<String, Object> typedDefinition) {
        return workflowCommands.update(prepare(), workflowId, request, correlationId, typedDefinition);
    }

    @Transactional
    public List<ApprovalDtos.WorkflowSummary> publishWorkflow(
            UUID workflowId,
            long expectedVersion,
            String correlationId) {
        return publishWorkflow(workflowId, expectedVersion, correlationId, null);
    }

    @Transactional
    public List<ApprovalDtos.WorkflowSummary> publishWorkflow(
            UUID workflowId,
            long expectedVersion,
            String correlationId,
            ApprovalStepUpHeaders stepUpHeaders) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalHighRiskCommandGuard.Permit permit = beginHighRisk(
                actor, "approvals.design.publish", "WORKFLOW", workflowId, expectedVersion,
                "/api/approvals/v1/admin/workflows/" + workflowId + "/publish",
                Map.of("expectedVersion", expectedVersion), stepUpHeaders);
        if (prior(permit)) {
            return queries.workflows(actor.tenantId(), false);
        }
        taskGovernance.requireEligibleCandidateRoles(
                actor, queries.workflowCandidateRoles(actor.tenantId(), workflowId));
        commands.publishWorkflow(actor, workflowId, expectedVersion, correlationId);
        record(actor, "approval.workflow.published", "APPROVAL_WORKFLOW",
                workflowId.toString(), correlationId, Map.of());
        List<ApprovalDtos.WorkflowSummary> result = queries.workflows(actor.tenantId(), false);
        completeHighRisk(permit);
        return result;
    }

    private ApprovalWorkflowQuorumFacade requireQuorum() {
        if (quorum == null) throw ApprovalWorkflowQuorum.unavailable("The durable workflow runtime is unavailable.");
        return quorum;
    }

    @Transactional(readOnly = true)
    public ApprovalWorkflowQuorumSimulation.Result simulateWorkflow(UUID requestId,
            ApprovalWorkflowQuorumSimulation.Input input) {
        return requireQuorum().simulate(ApprovalRequestContext.require(), requestId, input);
    }

    @Transactional
    public List<ApprovalDtos.FormSummary> forms() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.forms(actor.tenantId());
    }

    @Transactional
    public List<ApprovalDtos.FormCategorySummary> formCategories() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.formCategories(actor.tenantId());
    }

    @Transactional
    public List<ApprovalDtos.FormCategorySummary> createFormCategory(
            ApprovalDtos.CreateFormCategoryRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        UUID categoryId = commands.createFormCategory(actor, request);
        record(actor, "approval.form.category.created", "APPROVAL_FORM_CATEGORY",
                categoryId.toString(), correlationId, Map.of("categoryKey", request.categoryKey()));
        return queries.formCategories(actor.tenantId());
    }

    @Transactional
    public List<ApprovalDtos.FormCategorySummary> updateFormCategory(
            UUID categoryId,
            ApprovalDtos.UpdateFormCategoryRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        commands.updateFormCategory(actor, categoryId, request);
        record(actor, "approval.form.category.updated", "APPROVAL_FORM_CATEGORY",
                categoryId.toString(), correlationId,
                Map.of("lifecycleState", request.lifecycleState()));
        return queries.formCategories(actor.tenantId());
    }

    @Transactional
    public ApprovalDtos.FormDetail form(UUID formId) {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.form(actor.tenantId(), formId);
    }

    @Transactional
    public ApprovalDtos.FormDetail createFormDraft(
            ApprovalDtos.CreateFormDraftRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        UUID formId = commands.createFormDraft(actor, request);
        record(actor, "approval.form.draft.created", "APPROVAL_FORM",
                formId.toString(), correlationId,
                Map.of("formKey", request.formKey(),
                        "workflowId", request.defaultWorkflowId().toString()));
        return queries.form(actor.tenantId(), formId);
    }

    @Transactional
    public ApprovalDtos.FormDetail updateFormDraft(
            UUID formId,
            ApprovalDtos.UpdateFormDraftRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        commands.updateFormDraft(actor, formId, request);
        ApprovalDtos.FormDetail updated = queries.form(actor.tenantId(), formId);
        record(actor, "approval.form.draft.updated", "APPROVAL_FORM",
                formId.toString(), correlationId,
                Map.of("fieldCount", updated.form().fieldCount()));
        return updated;
    }

    @Transactional
    public ApprovalDtos.FormDetail publishForm(
            UUID formId,
            long expectedVersion,
            String correlationId) {
        return publishForm(formId, expectedVersion, correlationId, null);
    }

    @Transactional
    public ApprovalDtos.FormDetail publishForm(
            UUID formId,
            long expectedVersion,
            String correlationId,
            ApprovalStepUpHeaders stepUpHeaders) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalHighRiskCommandGuard.Permit permit = beginHighRisk(
                actor, "approvals.design.publish", "FORM", formId, expectedVersion,
                "/api/approvals/v1/admin/forms/" + formId + "/publish",
                Map.of("expectedVersion", expectedVersion), stepUpHeaders);
        if (prior(permit)) {
            return queries.form(actor.tenantId(), formId);
        }
        commands.publishForm(actor, formId, expectedVersion);
        record(actor, "approval.form.published", "APPROVAL_FORM",
                formId.toString(), correlationId, Map.of());
        ApprovalDtos.FormDetail result = queries.form(actor.tenantId(), formId);
        completeHighRisk(permit);
        return result;
    }

    @Transactional
    public List<ApprovalDtos.PolicySummary> policies() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.policies(actor.tenantId());
    }

    @Transactional
    public List<ApprovalDtos.PolicyVersionSummary> policyVersions(UUID policyId) {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.policyVersions(actor.tenantId(), policyId);
    }

    @Transactional
    public List<ApprovalDtos.PolicySummary> updatePolicy(
            UUID policyId,
            ApprovalDtos.UpdatePolicyRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        commands.updatePolicy(actor, policyId, request);
        record(actor, "approval.policy.change.submitted", "APPROVAL_POLICY",
                policyId.toString(), correlationId,
                Map.of("enforcementMode", request.enforcementMode(),
                        "lifecycleState", request.lifecycleState()));
        return queries.policies(actor.tenantId());
    }

    @Transactional
    public List<ApprovalDtos.PolicySummary> publishPolicy(
            UUID policyId,
            ApprovalDtos.PublishPolicyRequest request,
            String correlationId) {
        return publishPolicy(policyId, request, correlationId, null);
    }

    @Transactional
    public List<ApprovalDtos.PolicySummary> publishPolicy(
            UUID policyId,
            ApprovalDtos.PublishPolicyRequest request,
            String correlationId,
            ApprovalStepUpHeaders stepUpHeaders) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalHighRiskCommandGuard.Permit permit = beginHighRisk(
                actor, "approvals.policy.publish", "POLICY", policyId,
                request.expectedVersion(),
                "/api/approvals/v1/admin/policies/" + policyId + "/publish",
                Map.of(
                        "expectedVersion", request.expectedVersion(),
                        "reviewComment", request.reviewComment().trim()),
                stepUpHeaders);
        if (prior(permit)) {
            return queries.policies(actor.tenantId());
        }
        commands.publishPolicy(actor, policyId, request);
        record(actor, "approval.policy.published", "APPROVAL_POLICY",
                policyId.toString(), correlationId,
                Map.of("reviewComment", request.reviewComment().trim()));
        List<ApprovalDtos.PolicySummary> result = queries.policies(actor.tenantId());
        completeHighRisk(permit);
        return result;
    }

    @Transactional
    public ApprovalDtos.OperationsResponse operations() {
        ApprovalRequestContext.Actor actor = prepare();
        return ApprovalResponseAssembler.operations(queries, actor);
    }

    @Transactional
    public ApprovalDtos.OperationsResponse retryIntegrationDelivery(
            UUID outboxId,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        commands.retryIntegrationDelivery(actor, outboxId);
        record(actor, "approval.integration.delivery.retried", "APPROVAL_INTEGRATION_EVENT",
                outboxId.toString(), correlationId,
                Map.of("outboxId", outboxId.toString()));
        return ApprovalResponseAssembler.operations(queries, actor);
    }

    @Transactional
    public ApprovalDtos.OperationsResponse retryIntegrationDelivery(
            UUID outboxId,
            Long expectedVersion,
            String correlationId,
            ApprovalStepUpHeaders stepUpHeaders) {
        if (expectedVersion == null && ApprovalPilotAuthorizationContext.highRisk().isEmpty()
                && com.dwp.services.approval.security.ApprovalDecisionRevisionContext.current().isEmpty()) {
            return retryIntegrationDelivery(outboxId, correlationId);
        }
        ApprovalRequestContext.Actor actor = prepare();
        if (expectedVersion == null || expectedVersion < 0) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Expected delivery version is required.");
        }
        ApprovalHighRiskCommandGuard.Permit permit = beginHighRisk(
                actor, "approvals.operations.execute", "OUTBOX_EVENT", outboxId,
                expectedVersion,
                "/api/approvals/v1/admin/operations/events/" + outboxId + "/retry",
                Map.of(), stepUpHeaders);
        if (prior(permit)) {
            return ApprovalResponseAssembler.operations(queries, actor);
        }
        commands.retryIntegrationDelivery(actor, outboxId, expectedVersion);
        record(actor, "approval.integration.delivery.retried", "APPROVAL_INTEGRATION_EVENT",
                outboxId.toString(), correlationId, Map.of("outboxId", outboxId.toString()));
        ApprovalDtos.OperationsResponse result = ApprovalResponseAssembler.operations(queries, actor);
        completeHighRisk(permit);
        return result;
    }

    @Transactional
    public List<ApprovalDtos.SignatureProviderSummary> signatures() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.signatureProviders(actor.tenantId());
    }

    private ApprovalRequestContext.Actor prepare() {
        ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
        queries.ensureTenant(actor.tenantId());
        return actor;
    }

    private ApprovalHighRiskCommandGuard.Permit beginHighRisk(
            ApprovalRequestContext.Actor actor,
            String capability,
            String targetType,
            UUID targetId,
            long expectedVersion,
            String publicPath,
            Object payload,
            ApprovalStepUpHeaders headers) {
        return highRiskGuard == null ? null : highRiskGuard.begin(
                actor, capability, targetType, targetId, expectedVersion,
                publicPath, payload, headers);
    }

    private boolean prior(ApprovalHighRiskCommandGuard.Permit permit) {
        return permit != null && permit.priorResult();
    }
    private void completeHighRisk(ApprovalHighRiskCommandGuard.Permit permit) {
        if (highRiskGuard != null) highRiskGuard.complete(permit);
    }

    private void lockOwnedRequest(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion) {
        if (ownerPredicates != null && ApprovalPilotAuthorizationContext.requiresPredicate(
                "predicate.approval.own-request.v1")) {
            ownerPredicates.lockOwnedRequest(actor, requestId, expectedVersion);
        }
    }
    private void requireOwnerPredicates() {
        if (ownerPredicates == null) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The Approval owner predicate evaluator is unavailable.");
        }
    }

    private void record(
            ApprovalRequestContext.Actor actor,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            Map<String, Object> afterState) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category(auditCategory(targetType))
                .action(action)
                .outcome("SUCCESS")
                .severity("INFO")
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-approval-server")
                .sourceModule("approval-decision-hub")
                .targetType(targetType)
                .targetId(targetId)
                .correlationId(correlationId)
                .approvalId(targetType.equals("APPROVAL_REQUEST") ? targetId : null)
                .afterState(afterState)
                .retentionClass("EXTENDED")
                .build());
    }

    private String auditCategory(String targetType) {
        return switch (targetType) {
            case "APPROVAL_REQUEST", "APPROVAL_TASK" -> "SYSTEM_EVENT";
            default -> "ADMIN_CHANGE";
        };
    }
}
