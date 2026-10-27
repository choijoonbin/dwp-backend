package com.dwp.services.approval.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffIdentity;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffOutboxRepository;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request lifecycle orchestration inside the service's existing transaction boundary. */
final class ApprovalRequestLifecycleCommands {

    private final ApprovalQueryRepository queries;
    private final ApprovalCommandRepository commands;
    private final AuditOutboxRecorder audit;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalOwnerPredicateEvaluator ownerPredicates;
    private final ApprovalTaskGovernance taskGovernance;
    private final ApprovalWorkflowQuorumFacade quorum;
    private final ApprovalWorkflowDecisionCommands workflowDecisions;
    private final DwaionProposalHandoffOutboxRepository dwaionHandoffs;

    ApprovalRequestLifecycleCommands(
            ApprovalQueryRepository queries, ApprovalCommandRepository commands,
            AuditOutboxRecorder audit, ApprovalIdentityDirectory identities,
            ApprovalOwnerPredicateEvaluator ownerPredicates, ApprovalTaskGovernance taskGovernance,
            ApprovalWorkflowQuorumFacade quorum, ApprovalWorkflowDecisionCommands workflowDecisions,
            DwaionProposalHandoffOutboxRepository dwaionHandoffs) {
        this.queries = queries;
        this.commands = commands;
        this.audit = audit;
        this.identities = identities;
        this.ownerPredicates = ownerPredicates;
        this.taskGovernance = taskGovernance;
        this.quorum = quorum;
        this.workflowDecisions = workflowDecisions;
        this.dwaionHandoffs = dwaionHandoffs;
    }

    ApprovalDtos.RequestSummary create(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateRequest request,
            String correlationId,
            DwaionProposalHandoffIdentity handoffIdentity) {
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
        ApprovalServiceAudit.record(audit, actor, "approval.request.drafted", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of("workflowId", request.workflowId().toString()));
        return queries.request(actor, requestId);
    }

    ApprovalDtos.RequestDetail updateDraft(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            ApprovalDtos.UpdateDraftRequest request,
            String correlationId) {
        lockOwnedRequest(actor, requestId, request.expectedVersion());
        commands.updateDraft(actor, requestId, request, correlationId);
        ApprovalServiceAudit.record(audit, actor, "approval.request.draft.updated", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of("workflowId", request.workflowId().toString()));
        return queries.requestDetail(actor, requestId);
    }

    ApprovalDtos.RequestSummary submit(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        lockOwnedRequest(actor, requestId, expectedVersion);
        if (commands.quorumWorkflow(actor.tenantId(), requestId) != null) {
            requireQuorum().submit(actor, requestId, expectedVersion, correlationId);
        } else {
            taskGovernance.requireEligibleCandidateRoles(actor, queries.requestCandidateRoles(actor, requestId));
            commands.submit(actor, requestId, expectedVersion, correlationId);
        }
        ApprovalServiceAudit.record(audit, actor, "approval.request.submitted", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of());
        ApprovalDtos.RequestSummary result = queries.request(actor, requestId);
        if (dwaionHandoffs != null) {
            dwaionHandoffs.markDomainCommitted(actor, requestId, result, correlationId);
        }
        return result;
    }

    ApprovalDtos.RequestPreflight preflight(
            ApprovalRequestContext.Actor actor,
            UUID requestId, long expectedVersion) {
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

    ApprovalDtos.RequestSummary withdraw(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        lockOwnedRequest(actor, requestId, expectedVersion);
        if (commands.quorumWorkflow(actor.tenantId(), requestId) != null) requireQuorum().cancel(actor, requestId);
        commands.withdraw(actor, requestId, expectedVersion, correlationId);
        ApprovalServiceAudit.record(audit, actor, "approval.request.withdrawn", "APPROVAL_REQUEST", requestId.toString(),
                correlationId, Map.of());
        return queries.request(actor, requestId);
    }

    ApprovalDtos.RequestSummary respondToInformationRequest(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            ApprovalDtos.InformationResponseRequest request,
            String correlationId) {
        if (commands.quorumWorkflow(actor.tenantId(), requestId) != null) return requireQuorum().reply(actor, requestId, request);
        if (request.sourceGeneration() != null) throw new com.dwp.core.exception.BaseException(com.dwp.core.common.ErrorCode.FORBIDDEN);
        return workflowDecisions.respondLegacy(actor, requestId, request, correlationId,
                () -> lockOwnedRequest(actor, requestId, request.expectedVersion()),
                () -> ApprovalServiceAudit.record(audit, actor, "approval.request.information.responded", "APPROVAL_REQUEST", requestId.toString(), correlationId, Map.of()));
    }

    ApprovalDtos.RequestDetail requestDetail(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        var detail = queries.requestDetail(actor, requestId);
        return commands.quorumWorkflow(actor.tenantId(), requestId) == null ? detail : requireQuorum().informationDetail(actor, detail);
    }

    void claim(
            ApprovalRequestContext.Actor actor,
            UUID taskId,
            long expectedVersion,
            String correlationId) {
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
        ApprovalServiceAudit.record(audit, actor, "approval.task.claimed", "APPROVAL_TASK", taskId.toString(),
                correlationId, Map.of("requestId", task.summary().requestId().toString()));
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

    private ApprovalWorkflowQuorumFacade requireQuorum() {
        if (quorum == null) throw ApprovalWorkflowQuorum.unavailable("The durable workflow runtime is unavailable.");
        return quorum;
    }

}
