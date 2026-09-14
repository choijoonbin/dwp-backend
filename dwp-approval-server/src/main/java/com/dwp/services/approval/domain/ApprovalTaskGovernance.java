package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ApprovalTaskGovernance {

    private final ApprovalQueryRepository queries;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalOwnerPredicateEvaluator ownerPredicates;

    ApprovalTaskGovernance(
            ApprovalQueryRepository queries,
            ApprovalIdentityDirectory identities,
            ApprovalOwnerPredicateEvaluator ownerPredicates) {
        this.queries = queries;
        this.identities = identities;
        this.ownerPredicates = ownerPredicates;
    }

    ApprovalDtos.TaskDetail detail(
            ApprovalRequestContext.Actor actor,
            UUID taskId) {
        ApprovalQueryRepository.TaskAccess access = queries.taskDetail(actor, taskId);
        boolean decisionBearing = isDecisionBearing(access.summary().status());
        ApprovalOwnerPredicateEvaluator.ContentReadDecision contentDecision =
                contentAccess(actor, access, decisionBearing);
        boolean readable = contentDecision.readable();
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
                readable ? access.summary() : redacted(access.summary()),
                readable
                        ? decisionBearing
                                ? queries.decisionPayload(actor.tenantId(), taskId)
                                : queries.requestPayload(
                                        actor.tenantId(), access.summary().requestId())
                        : Map.of(),
                readable
                        ? queries.requestFormSchema(actor.tenantId(), access.summary().requestId())
                        : Map.of(),
                readable
                        ? queries.timeline(actor.tenantId(), access.summary().requestId())
                        : List.of(),
                readable && canClaimTask && pending && access.assigneeUserId() == null
                        && (candidate || delegated),
                readable && canDecideTask && decisionOpen && !selfApprovalBlocked
                        && (assigned || delegated || (!exactDecision && candidate)),
                selfApprovalBlocked,
                new ApprovalDtos.ContentAccess(
                        readable ? "FULL" : "REDACTED",
                        contentDecision.reason(),
                        Instant.now()));
    }

    void requireEligibleCandidateRoles(
            ApprovalRequestContext.Actor actor,
            List<String> candidateRoles) {
        if (candidateRoles == null || candidateRoles.isEmpty()) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Approval candidate-role evidence is unavailable.");
        }
        for (String roleCode : candidateRoles) {
            ApprovalIdentityDirectory.RoleEligibility eligibility =
                    identities.requireRole(actor.tenantId(), roleCode);
            if (eligibility == null || !eligibility.activeAndStaffed()) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Approval candidate role is inactive or has no eligible approver: "
                                + roleCode);
            }
        }
    }

    private ApprovalOwnerPredicateEvaluator.ContentReadDecision contentAccess(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess access,
            boolean decisionBearing) {
        if (ownerPredicates == null) {
            ApprovalLegacyDelegationGuard.verify(actor, access, identities);
            return new ApprovalOwnerPredicateEvaluator.ContentReadDecision(
                    true, "LEGACY_CURRENT_AUTHORITY_VERIFIED");
        }
        if (decisionBearing) {
            return ownerPredicates.completedContentAccess(actor, access);
        }
        ownerPredicates.requireReadableTask(actor, access);
        return ApprovalOwnerPredicateEvaluator.ContentReadDecision.full();
    }

    private ApprovalDtos.TaskSummary redacted(ApprovalDtos.TaskSummary value) {
        return new ApprovalDtos.TaskSummary(
                value.taskId(), value.requestId(), value.requestNumber(),
                "Restricted approval", "", value.workflowNameKo(), value.workflowNameEn(),
                value.stepKey(), value.stepName(), value.stepSequence(),
                "", "", value.status(), value.priority(), value.dataClassification(),
                value.riskScore(), value.submittedAt(), value.dueAt(), value.version());
    }

    private boolean isDecisionBearing(String status) {
        return "APPROVED".equals(status)
                || "REJECTED".equals(status)
                || "INFO_REQUESTED".equals(status)
                || "SUPERSEDED".equals(status);
    }
}
