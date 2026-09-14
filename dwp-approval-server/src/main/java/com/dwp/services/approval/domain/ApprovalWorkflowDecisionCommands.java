package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;

final class ApprovalWorkflowDecisionCommands {
    private final ApprovalQueryRepository queries;
    private final ApprovalCommandRepository commands;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalOwnerPredicateEvaluator owners;
    private final ApprovalTaskGovernance governance;
    private final ApprovalWorkflowQuorumFacade quorum;

    ApprovalWorkflowDecisionCommands(ApprovalQueryRepository queries, ApprovalCommandRepository commands,
            ApprovalIdentityDirectory identities, ApprovalOwnerPredicateEvaluator owners,
            ApprovalTaskGovernance governance, ApprovalWorkflowQuorumFacade quorum) {
        this.queries = queries;
        this.commands = commands;
        this.identities = identities;
        this.owners = owners;
        this.governance = governance;
        this.quorum = quorum;
    }

    ApprovalCommandRepository.DecisionResult decide(ApprovalRequestContext.Actor actor, ApprovalQueryRepository.TaskAccess task,
            ApprovalDtos.DecisionRequest request, String correlation, ApprovalWorkflowQuorumFacade.ExpectedVote expected) {
        boolean tagged = commands.quorumWorkflow(actor.tenantId(), task.summary().requestId()) != null;
        if (tagged && "REQUEST_INFO".equalsIgnoreCase(request.decision())) {
            if (quorum == null) throw ApprovalWorkflowQuorum.unavailable("The durable information runtime is unavailable.");
            return quorum.requestInformation(actor, task, request, expected, () -> lockDecisionOwner(actor, task, request));
        }
        if (tagged) {
            if (quorum == null) throw ApprovalWorkflowQuorum.unavailable("The durable workflow runtime is unavailable.");
            quorum.prepareDecision(actor, task, expected);
        }
        lockDecisionOwner(actor, task, request);
        if (!tagged && expected != null) throw new BaseException(ErrorCode.FORBIDDEN, "A quorum vote cannot target a legacy task.");
        if (!tagged && "APPROVE".equalsIgnoreCase(request.decision())) {
            governance.requireEligibleCandidateRoles(actor, queries.requestCandidateRoles(actor.tenantId(),
                    task.summary().requestId(), task.managementResourceSetKey()));
        }
        if (!tagged) return commands.decide(actor, task, request, correlation);
        return quorum.decide(actor, task, request, expected);
    }

    private void lockDecisionOwner(ApprovalRequestContext.Actor actor, ApprovalQueryRepository.TaskAccess task, ApprovalDtos.DecisionRequest request) {
        if (ApprovalPilotAuthorizationContext.requiresPredicate("predicate.approval-task-decision.v1")) {
            if (owners == null) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Approval owner predicate evaluation is unavailable.");
            owners.lockDecidableTask(actor, task, request.expectedVersion());
        } else {
            ApprovalLegacyDelegationGuard.verify(actor, task, identities);
        }
    }

    ApprovalDtos.RequestSummary respondLegacy(ApprovalRequestContext.Actor actor, java.util.UUID requestId,
            ApprovalDtos.InformationResponseRequest request, String correlation, Runnable ownerGuard, Runnable record) {
        ownerGuard.run(); governance.requireEligibleCandidateRoles(actor, queries.requestCandidateRoles(actor, requestId));
        commands.respondToInformationRequest(actor, requestId, request, correlation); record.run();
        return queries.request(actor, requestId);
    }
}
