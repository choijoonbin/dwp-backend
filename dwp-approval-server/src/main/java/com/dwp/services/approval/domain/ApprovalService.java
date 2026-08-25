package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalService {

    private final ApprovalQueryRepository queries;
    private final ApprovalCommandRepository commands;
    private final AuditOutboxRecorder audit;
    private final ApprovalIdentityDirectory identities;

    public ApprovalService(
            ApprovalQueryRepository queries,
            ApprovalCommandRepository commands,
            AuditOutboxRecorder audit,
            ApprovalIdentityDirectory identities) {
        this.queries = queries;
        this.commands = commands;
        this.audit = audit;
        this.identities = identities;
    }

    @Transactional
    public ApprovalDtos.HomeResponse home() {
        ApprovalRequestContext.Actor actor = prepare();
        boolean canUseTasks = actor.hasPermission("ACTION.APPROVAL_TASK", "VIEW", "MANAGE");
        boolean canUseRequests = actor.hasPermission("ACTION.APPROVAL_REQUEST", "VIEW", "MANAGE");
        ApprovalDtos.ApprovalMetrics rawMetrics = canUseTasks || canUseRequests
                ? queries.metrics(actor)
                : emptyMetrics();
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
        boolean canViewOperations = actor.hasPermission(
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
                insights(metrics, pulse),
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
                && queries.isBlockingPolicyActive(actor.tenantId(), "BLOCK_SELF_APPROVAL");
        boolean candidate = access.assigneeUserId() == null
                && access.candidateRole() != null
                && actor.roles().contains(access.candidateRole());
        boolean assigned = actor.userId().equals(access.assigneeUserId());
        boolean delegated = access.delegatedAccess();
        boolean pending = "PENDING".equals(access.summary().status());
        boolean decisionOpen = pending || "CLAIMED".equals(access.summary().status());
        boolean canClaimTask = actor.hasPermission(
                "ACTION.APPROVAL_TASK", "UPDATE", "MANAGE");
        boolean canDecideTask = actor.hasPermission(
                "ACTION.APPROVAL_TASK", "APPROVE", "MANAGE");
        return new ApprovalDtos.TaskDetail(
                access.summary(),
                queries.requestPayload(actor.tenantId(), access.summary().requestId()),
                queries.requestFormSchema(actor.tenantId(), access.summary().requestId()),
                queries.timeline(actor.tenantId(), access.summary().requestId()),
                canClaimTask && pending && access.assigneeUserId() == null && (candidate || delegated),
                canDecideTask && decisionOpen && !selfApprovalBlocked
                        && (assigned || candidate || delegated),
                selfApprovalBlocked);
    }

    @Transactional
    public ApprovalDtos.TaskDetail claim(
            UUID taskId,
            long expectedVersion,
            String correlationId) {
        ApprovalRequestContext.Actor actor = prepare();
        ApprovalQueryRepository.TaskAccess task = queries.taskDetail(actor, taskId);
        validateDelegatedAuthority(actor, task);
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
        validateDelegatedAuthority(actor, task);
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
        commands.respondToInformationRequest(actor, requestId, request, correlationId);
        record(actor, "approval.request.information.responded", "APPROVAL_REQUEST",
                requestId.toString(), correlationId, Map.of());
        return queries.request(actor, requestId);
    }

    @Transactional
    public List<ApprovalDtos.WorkflowSummary> publishedWorkflows() {
        ApprovalRequestContext.Actor actor = prepare();
        return queries.workflows(actor.tenantId(), true);
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
        UUID delegationId = commands.createDelegation(actor, request, delegate);
        record(actor, "approval.delegation.created", "APPROVAL_DELEGATION",
                delegationId.toString(), correlationId,
                Map.of(
                        "delegateUserId", request.delegateUserId(),
                        "scopeType", request.scopeType().trim().toUpperCase(java.util.Locale.ROOT),
                        "endsAt", request.endsAt().toString()));
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
        ApprovalRequestContext.Actor actor = prepare();
        commands.publishWorkflow(actor, workflowId, expectedVersion, correlationId);
        record(actor, "approval.workflow.published", "APPROVAL_WORKFLOW",
                workflowId.toString(), correlationId, Map.of());
        return queries.workflows(actor.tenantId(), false);
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
        ApprovalRequestContext.Actor actor = prepare();
        commands.publishForm(actor, formId, expectedVersion);
        record(actor, "approval.form.published", "APPROVAL_FORM",
                formId.toString(), correlationId, Map.of());
        return queries.form(actor.tenantId(), formId);
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
        ApprovalRequestContext.Actor actor = prepare();
        commands.publishPolicy(actor, policyId, request);
        record(actor, "approval.policy.published", "APPROVAL_POLICY",
                policyId.toString(), correlationId,
                Map.of("reviewComment", request.reviewComment().trim()));
        return queries.policies(actor.tenantId());
    }

    @Transactional
    public ApprovalDtos.OperationsResponse operations() {
        ApprovalRequestContext.Actor actor = prepare();
        return operations(actor);
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
        return operations(actor);
    }

    private ApprovalDtos.OperationsResponse operations(ApprovalRequestContext.Actor actor) {
        int failed = queries.failedIntegrationCount(actor.tenantId());
        int pending = queries.pendingIntegrationCount(actor.tenantId());
        int delegations = queries.activeDelegationCount(actor.tenantId());
        int overdue = queries.adminPulse(actor.tenantId()).overdueTasks();
        return new ApprovalDtos.OperationsResponse(
                Instant.now(),
                List.of(
                        new ApprovalDtos.OperationSignal(
                                "sla", overdue == 0 ? "HEALTHY" : "ATTENTION",
                                "SLA 준수", "SLA assurance",
                                "기한을 넘긴 결재를 추적합니다.",
                                "Track approval tasks beyond their due time.", overdue),
                        new ApprovalDtos.OperationSignal(
                                "integration", failed == 0 ? "HEALTHY" : "DEGRADED",
                                "통합 전달", "Integration delivery",
                                "실패·격리된 아웃박스 이벤트를 감시합니다.",
                                "Monitor failed and isolated outbox events.", failed),
                        new ApprovalDtos.OperationSignal(
                                "outbox", pending < 25 ? "HEALTHY" : "ATTENTION",
                                "이벤트 백로그", "Event backlog",
                                "외부 전달을 기다리는 이벤트입니다.",
                                "Events waiting for external delivery.", pending),
                        new ApprovalDtos.OperationSignal(
                                "delegation", "INFORMATIONAL",
                                "활성 위임", "Active delegations",
                                "현재 효력이 있는 사용자 위임입니다.",
                                "User delegations currently in effect.", delegations)),
                queries.breachedTasks(actor.tenantId(), 20),
                queries.integrationDeliveries(actor.tenantId(), 50));
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

    private void validateDelegatedAuthority(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task) {
        if (!task.delegatedAccess() || task.delegatedFromUserId() == null) return;
        ApprovalIdentityDirectory.Subject delegator = identities.require(
                actor.tenantId(), task.delegatedFromUserId());
        boolean roleBasedAuthority = task.assigneeUserId() == null;
        if (!delegator.active()
                || (roleBasedAuthority
                    && task.candidateRole() != null
                    && !delegator.hasRole(task.candidateRole()))) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.FORBIDDEN,
                    "The original approver no longer holds the delegated authority.");
        }
    }

    private List<ApprovalDtos.DecisionInsight> insights(
            ApprovalDtos.ApprovalMetrics metrics,
            ApprovalDtos.AdminPulse pulse) {
        List<ApprovalDtos.DecisionInsight> values = new ArrayList<>();
        if (metrics.overdue() > 0) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "overdue", "critical",
                    "기한을 넘긴 결정이 있습니다", "Decisions have passed their due time",
                    "리스크가 높은 항목부터 검토해 업무 지연을 줄이세요.",
                    "Review high-risk items first to reduce downstream delay.",
                    "/approvals/inbox"));
        }
        if (metrics.needsInformation() > 0) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "needs-info", "warning",
                    "추가 정보 응답이 필요합니다", "More information is required",
                    "요청자 응답을 완료하면 결재 흐름이 다시 시작됩니다.",
                    "Respond to restart the approval flow.",
                    "/approvals/requests/needs-info"));
        }
        if (pulse != null && pulse.draftWorkflows() > 0) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "draft-workflow", "info",
                    "게시 대기 중인 프로세스가 있습니다", "A process is waiting to be published",
                    "설계자와 게시 책임자의 직무 분리 검토가 필요합니다.",
                    "Designer and publisher separation requires review.",
                    "/approvals/admin/workflows"));
        }
        if (values.isEmpty()) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "healthy", "success",
                    "결재 흐름이 안정적입니다", "Approval flow is healthy",
                    "현재 즉시 조치가 필요한 SLA 또는 통합 문제가 없습니다.",
                    "No SLA or integration issue requires immediate action.",
                    "/approvals/inbox"));
        }
        return List.copyOf(values);
    }

    private ApprovalDtos.ApprovalMetrics emptyMetrics() {
        return new ApprovalDtos.ApprovalMetrics(0, 0, 0, 0, 0, 0, 100);
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
