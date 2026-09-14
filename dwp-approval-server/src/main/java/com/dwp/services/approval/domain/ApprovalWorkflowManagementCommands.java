package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ApprovalWorkflowManagementCommands {
    private final ApprovalCommandRepository commands;
    private final ApprovalQueryRepository queries;
    private final AuditOutboxRecorder audit;

    ApprovalWorkflowManagementCommands(ApprovalCommandRepository commands, ApprovalQueryRepository queries,
            AuditOutboxRecorder audit) {
        this.commands = commands;
        this.queries = queries;
        this.audit = audit;
    }

    ApprovalDtos.WorkflowDetail create(ApprovalRequestContext.Actor actor, ApprovalDtos.CreateWorkflowDraftRequest request,
            String correlation, Map<String, Object> definition) {
        UUID id = definition == null ? commands.createWorkflowDraft(actor, request)
                : commands.createWorkflowDraft(actor, request, definition);
        record(actor, id, correlation, "approval.workflow.draft.created", Map.of("workflowKey", request.workflowKey()));
        return queries.workflow(actor.tenantId(), id);
    }

    ApprovalDtos.WorkflowDetail update(ApprovalRequestContext.Actor actor, UUID id, ApprovalDtos.UpdateWorkflowDraftRequest request,
            String correlation, Map<String, Object> definition) {
        if (definition == null) commands.updateWorkflowDraft(actor, id, request);
        else commands.updateWorkflowDraft(actor, id, request, definition);
        record(actor, id, correlation, "approval.workflow.draft.updated", Map.of("stepCount", definition == null
                ? request.steps().size() : ApprovalWorkflowQuorumDefinition.compile(definition).stages().size()));
        return queries.workflow(actor.tenantId(), id);
    }

    private void record(ApprovalRequestContext.Actor actor, UUID id, String correlation, String action, Map<String, Object> state) {
        audit.record(AuditEvent.builder().tenantId(actor.tenantId()).category("ADMIN_CHANGE").action(action)
                .outcome("SUCCESS").severity("INFO").actorType("USER").actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles())).sourceService("dwp-approval-server").sourceModule("approval-decision-hub")
                .targetType("APPROVAL_WORKFLOW").targetId(id.toString()).correlationId(correlation).afterState(state)
                .retentionClass("EXTENDED").build());
    }
}
