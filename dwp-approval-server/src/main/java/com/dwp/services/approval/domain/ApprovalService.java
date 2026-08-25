package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
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

    public ApprovalService(
            ApprovalQueryRepository queries,
            ApprovalCommandRepository commands,
            AuditOutboxRecorder audit,
            ApprovalIdentityDirectory identities) {
        this(queries, commands, audit, identities, null, null);
    }

    @Autowired
    public ApprovalService(
            ApprovalQueryRepository queries,
            ApprovalCommandRepository commands,
            AuditOutboxRecorder audit,
            ApprovalIdentityDirectory identities,
            ApprovalHighRiskCommandGuard highRiskGuard,
            ApprovalOwnerPredicateEvaluator ownerPredicates) {
        this.queries = queries;
        this.commands = commands;
        this.audit = audit;
        this.identities = identities;
        this.highRiskGuard = highRiskGuard;
        this.ownerPredicates = ownerPredicates;
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
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalQueryRepository.TaskAccess access = queries.taskDetail(actor, taskId);
        boolean selfApprovalBlocked = access.requesterUserId() == actor.userId()
                && queries.isBlockingPolicyActive(
                        actor.tenantId(), "BLOCK_SELF_APPROVAL",
                        access.managementResourceSetKey());
        boolean candidate = access.assigneeUserId() == null
                && access.candidateRole() != null
                && actor.roles().contains(access.candidateRole());
        boolean assigned = actor.userId().equals(access.assigneeUserId());
        boolean delegated = access.delegatedAccess();
        boolean pending = "PENDING".equals(access.summary().status());
        boolean decisionOpen = pending || "CLAIMED".equals(access.summary().status());
        boolean governed = ApprovalPilotAuthorizationContext.current().isPresent();
        boolean canClaimTask = governed
                ? actor.hasPermission("ACTION.APPROVAL_TASK", "UPDATE")
                : actor.hasPermission("ACTION.APPROVAL_TASK", "UPDATE", "MANAGE");
        boolean canDecideTask = governed
                ? actor.hasPermission("ACTION.APPROVAL_TASK", "APPROVE")
                : actor.hasPermission("ACTION.APPROVAL_TASK", "APPROVE", "MANAGE");
        boolean exactDecision = governed || ApprovalPilotAuthorizationContext.requiresPredicate(
                "predicate.approval-task-decision.v1");
        return new ApprovalDtos.TaskDetail(
                access.summary(),
                queries.requestPayload(actor.tenantId(), access.summary().requestId()),
                queries.requestFormSchema(actor.tenantId(), access.summary().requestId()),
                queries.timeline(actor.tenantId(), access.summary().requestId()),
                canClaimTask && pending && access.assigneeUserId() == null && (candidate || delegated),
                canDecideTask && decisionOpen && !selfApprovalBlocked
                        && (assigned || delegated || (!exactDecision && candidate)),
                selfApprovalBlocked);
    }

    @Transactional
    public ApprovalDtos.TaskDetail claim(
            UUID taskId,
            long expectedVersion,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
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

    @Transactional
    public ApprovalDtos.TaskDetail decide(
            UUID taskId,
            ApprovalDtos.DecisionRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalQueryRepository.TaskAccess task = queries.taskDetail(actor, taskId);
        if (ApprovalPilotAuthorizationContext.requiresPredicate(
                "predicate.approval-task-decision.v1")) {
            requireOwnerPredicates();
            ownerPredicates.lockDecidableTask(actor, task, request.expectedVersion());
        } else {
            ApprovalLegacyDelegationGuard.verify(actor, task, identities);
        }
        ApprovalCommandRepository.DecisionResult result = commands.decide(
                actor, task, request, correlationId);
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
        return queries.requestDetail(actor, requestId);
    }

    @Transactional
    public ApprovalDtos.RequestSummary create(
            ApprovalDtos.CreateRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        if (ownerPredicates != null && ApprovalPilotAuthorizationContext.current().isPresent()) {
            ownerPredicates.requirePublishedForm(actor, request.formId(), request.workflowId());
        }
        UUID requestId = commands.createDraft(actor, request, correlationId);
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
        commands.submit(actor, requestId, expectedVersion, correlationId);
        record(actor, "approval.request.submitted", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of());
        return queries.request(actor, requestId);
    }

    @Transactional
    public ApprovalDtos.RequestSummary withdraw(
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        lockOwnedRequest(actor, requestId, expectedVersion);
        commands.withdraw(actor, requestId, expectedVersion, correlationId);
        record(actor, "approval.request.withdrawn", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of());
        return queries.request(actor, requestId);
    }

    @Transactional
    public ApprovalDtos.RequestSummary respondToInformationRequest(
            UUID requestId,
            ApprovalDtos.InformationResponseRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        lockOwnedRequest(actor, requestId, request.expectedVersion());
        commands.respondToInformationRequest(actor, requestId, request, correlationId);
        record(actor, "approval.request.information.responded", "APPROVAL_REQUEST",
                requestId.toString(), correlationId, Map.of());
        return queries.request(actor, requestId);
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
        return queries.delegations(prepare());
    }

    @Transactional
    public List<ApprovalDtos.DelegationSummary> createDelegation(
            ApprovalDtos.CreateDelegationRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalIdentityDirectory.Subject delegate = identities.require(
                actor.tenantId(), request.delegateUserId());
        ApprovalDelegationCommandSupport.Created created =
                commands.createDelegation(actor, request, delegate);
        record(actor, "approval.delegation.created", "APPROVAL_DELEGATION",
                created.delegationId().toString(), correlationId,
                created.auditAfterState(request.delegateUserId(), request.endsAt()));
        return queries.delegations(actor);
    }

    @Transactional
    public List<ApprovalDtos.DelegationCandidate> delegationCandidates(String query, int limit) {
        ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
        return identities.search(actor.tenantId(), query, limit).stream()
                .filter(subject -> !actor.userId().equals(subject.userId()))
                .map(subject -> new ApprovalDtos.DelegationCandidate(
                        subject.userId(), subject.personPublicId(), subject.displayName(),
                        subject.email(), subject.jobTitle()))
                .toList();
    }

    @Transactional
    public List<ApprovalDtos.DelegationSummary> revokeDelegation(
            UUID delegationId,
            long expectedVersion,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        if (ownerPredicates != null && ApprovalPilotAuthorizationContext.requiresPredicate(
                "predicate.approval.object-version.v1")) {
            ownerPredicates.lockOwnedDelegation(actor, delegationId, expectedVersion);
        }
        commands.revokeDelegation(actor, delegationId, expectedVersion);
        record(actor, "approval.delegation.revoked", "APPROVAL_DELEGATION",
                delegationId.toString(), correlationId, Map.of());
        return queries.delegations(actor);
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
    public ApprovalDtos.WorkflowDetail createWorkflowDraft(
            ApprovalDtos.CreateWorkflowDraftRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        UUID workflowId = commands.createWorkflowDraft(actor, request);
        record(actor, "approval.workflow.draft.created", "APPROVAL_WORKFLOW",
                workflowId.toString(), correlationId,
                Map.of("workflowKey", request.workflowKey()));
        return queries.workflow(actor.tenantId(), workflowId);
    }

    @Transactional
    public ApprovalDtos.WorkflowDetail updateWorkflowDraft(
            UUID workflowId,
            ApprovalDtos.UpdateWorkflowDraftRequest request,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        commands.updateWorkflowDraft(actor, workflowId, request);
        record(actor, "approval.workflow.draft.updated", "APPROVAL_WORKFLOW",
                workflowId.toString(), correlationId,
                Map.of("stepCount", request.steps().size()));
        return queries.workflow(actor.tenantId(), workflowId);
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
        commands.publishWorkflow(actor, workflowId, expectedVersion, correlationId);
        record(actor, "approval.workflow.published", "APPROVAL_WORKFLOW",
                workflowId.toString(), correlationId, Map.of());
        List<ApprovalDtos.WorkflowSummary> result = queries.workflows(actor.tenantId(), false);
        completeHighRisk(permit);
        return result;
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
        record(actor, "approval.form.draft.updated", "APPROVAL_FORM",
                formId.toString(), correlationId,
                Map.of("fieldCount", request.fields().size()));
        return queries.form(actor.tenantId(), formId);
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
