package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ApprovalDelegationManagement {

    private final ApprovalQueryRepository queries;
    private final ApprovalCommandRepository commands;
    private final AuditOutboxRecorder audit;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalOwnerPredicateEvaluator ownerPredicates;

    ApprovalDelegationManagement(
            ApprovalQueryRepository queries,
            ApprovalCommandRepository commands,
            AuditOutboxRecorder audit,
            ApprovalIdentityDirectory identities,
            ApprovalOwnerPredicateEvaluator ownerPredicates) {
        this.queries = queries;
        this.commands = commands;
        this.audit = audit;
        this.identities = identities;
        this.ownerPredicates = ownerPredicates;
    }

    List<ApprovalDtos.DelegationSummary> list(ApprovalRequestContext.Actor actor) {
        return queries.delegations(actor);
    }

    List<ApprovalDtos.DelegationSummary> create(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateDelegationRequest request,
            String correlationId) {
        ApprovalIdentityDirectory.Subject delegate = identities.require(
                actor.tenantId(), request.delegateUserId());
        ApprovalDelegationCommandSupport.Created created =
                commands.createDelegation(actor, request, delegate);
        record(actor, "approval.delegation.created", created.delegationId(), correlationId,
                null, created.auditAfterState(request.delegateUserId(), request.endsAt()));
        return list(actor);
    }

    List<ApprovalDtos.DelegationSummary> update(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            ApprovalDelegationUpdateRequest request,
            String idempotencyKey,
            String correlationId) {
        ApprovalDelegationCommandSupport.UpdateReplay replay =
                commands.delegationUpdateReplay(actor, delegationId, request, idempotencyKey);
        if (replay.replayed()) return list(actor);
        if (ownerPredicates != null && ApprovalPilotAuthorizationContext.requiresPredicate(
                "predicate.approval.object-version.v1")) {
            ownerPredicates.lockOwnedDelegation(actor, delegationId, request.expectedVersion());
        }
        ApprovalIdentityDirectory.Subject delegate = identities.require(
                actor.tenantId(), request.delegateUserId());
        ApprovalDelegationCommandSupport.Updated updated =
                commands.updateDelegation(actor, delegationId, request, delegate, replay);
        record(actor, "approval.delegation.updated", delegationId, correlationId,
                replay.eventId(), updated.auditAfterState(request));
        return list(actor);
    }

    List<ApprovalDtos.DelegationCandidate> candidates(
            ApprovalRequestContext.Actor actor,
            String query,
            int limit) {
        return identities.search(actor.tenantId(), query, limit).stream()
                .filter(subject -> !actor.userId().equals(subject.userId()))
                .map(subject -> new ApprovalDtos.DelegationCandidate(
                        subject.userId(), subject.personPublicId(), subject.displayName(),
                        subject.email(), subject.jobTitle()))
                .toList();
    }

    List<ApprovalDtos.DelegationSummary> revoke(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            long expectedVersion,
            String correlationId) {
        if (ownerPredicates != null && ApprovalPilotAuthorizationContext.requiresPredicate(
                "predicate.approval.object-version.v1")) {
            ownerPredicates.lockOwnedDelegation(actor, delegationId, expectedVersion);
        }
        commands.revokeDelegation(actor, delegationId, expectedVersion);
        record(actor, "approval.delegation.revoked", delegationId, correlationId, null, Map.of());
        return list(actor);
    }

    private void record(
            ApprovalRequestContext.Actor actor,
            String action,
            UUID delegationId,
            String correlationId,
            UUID eventId,
            Map<String, Object> afterState) {
        audit.record(AuditEvent.builder()
                .eventId(eventId)
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("INFO")
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-approval-server")
                .sourceModule("approval-decision-hub")
                .targetType("APPROVAL_DELEGATION")
                .targetId(delegationId.toString())
                .correlationId(correlationId)
                .afterState(afterState)
                .retentionClass("EXTENDED")
                .build());
    }
}
