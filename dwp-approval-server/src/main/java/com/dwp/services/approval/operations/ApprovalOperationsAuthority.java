package com.dwp.services.approval.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.integration.ApprovalRecoveryAuditorResolver;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class ApprovalOperationsAuthority {
    private final ApprovalWorkAuthority workAuthority;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalRecoveryAuditorResolver recoveryAuditors;
    private final ApprovalStepUpVerifier stepUpVerifier;
    private final ApprovalStepUpReplayRepository replay;
    private final Clock clock;

    @Autowired
    public ApprovalOperationsAuthority(
            ApprovalWorkAuthority workAuthority,
            ApprovalIdentityDirectory identities,
            ApprovalRecoveryAuditorResolver recoveryAuditors,
            ApprovalStepUpVerifier stepUpVerifier,
            ApprovalStepUpReplayRepository replay) {
        this(workAuthority, identities, recoveryAuditors, stepUpVerifier, replay, Clock.systemUTC());
    }

    ApprovalOperationsAuthority(
            ApprovalWorkAuthority workAuthority,
            ApprovalIdentityDirectory identities,
            ApprovalRecoveryAuditorResolver recoveryAuditors,
            ApprovalStepUpVerifier stepUpVerifier,
            ApprovalStepUpReplayRepository replay,
            Clock clock) {
        this.workAuthority = workAuthority;
        this.identities = identities;
        this.recoveryAuditors = recoveryAuditors;
        this.stepUpVerifier = stepUpVerifier;
        this.replay = replay;
        this.clock = clock;
    }

    Current requireCurrent(
            ApprovalOperationsProtocol.Route route,
            ApprovalStepUpHeaders headers,
            long commandVersion) {
        ApprovalRequestContext.Actor actor = currentActor();
        var decision = ApprovalDecisionRevisionContext.current().orElseThrow(() ->
                ApprovalOperationsProtocol.unavailable("Current Approval decision evidence is unavailable."));
        var scope = ApprovalManagementScopeContext.current().orElseThrow(() ->
                ApprovalOperationsProtocol.unavailable("Current Approval management scope is unavailable."));
        var high = ApprovalPilotAuthorizationContext.highRisk().orElseThrow(() ->
                ApprovalOperationsProtocol.forbidden("High-risk Approval operation authority is required."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!route.contractKey().equals(decision.routeContractKey())
                || !route.contractKey().equals(high.routeContractKey())
                || !ApprovalOperationsProtocol.CAPABILITY.equals(high.capabilityContractKey())
                || !"STEPUP-MGMT-HIGH-V1".equals(high.activationPolicy())
                || !("110".equals(decision.rolloutState()) || "111".equals(decision.rolloutState()))
                || decision.validUntil() == null || !decision.validUntil().isAfter(now)
                || decision.contextKey() == null || decision.contextKey().isBlank()
                || !decision.contextScopeKey().equals(scope.opaqueScopeKey())) {
            throw ApprovalOperationsProtocol.forbidden(
                    "The current route authority does not authorize this Approval operation.");
        }
        if (headers == null || headers.idempotencyKey() == null
                || !headers.idempotencyKey().matches("[A-Za-z0-9._:-]{1,120}")
                || headers.decisionRevision() == null
                || !headers.decisionRevision().equals(decision.revision())
                || headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion().longValue() != commandVersion) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "Approval operation command headers do not match current authority.");
        }
        return new Current(actor, scope, decision, high.activationPolicy());
    }

    ApprovalStepUpVerifier.VerifiedChallenge verifyStepUp(
            Current current,
            ApprovalOperationsProtocol.Route route,
            String targetType,
            UUID targetId,
            long targetVersion,
            String nativePath,
            Object payload,
            ApprovalStepUpHeaders headers) {
        if (headers.challenge() == null || headers.challenge().isBlank()) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "An Auth-signed Approval operation challenge is required.");
        }
        var binding = new ApprovalStepUpVerifier.CommandBinding(
                current.actor().userId(), current.actor().tenantId(), route.contractKey(),
                current.decision().contextKey(), current.activationPolicy(),
                ApprovalOperationsProtocol.CAPABILITY, current.scope().opaqueScopeKey(),
                targetType, targetId.toString(), targetVersion, "POST",
                "/api/approvals" + nativePath, headers.idempotencyKey(),
                stepUpVerifier.payloadSha256(payload), current.decision().revision());
        var challenge = stepUpVerifier.verify(headers.challenge(), binding);
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        return challenge;
    }

    DeliveryObservation observeDelivery(
            Current current,
            DeliverySnapshot delivery) {
        requireRecoveryEvidence(current, delivery);
        ApprovalRecoveryAuditorResolver.Assignment assignment;
        try {
            assignment = recoveryAuditors.resolve(
                    current.actor().tenantId(), delivery.targetId(),
                    delivery.originatorUserId(), current.scope().resourceSetKey());
        } catch (BaseException exception) {
            throw unavailable(exception, "Current recovery-auditor authority is unavailable.");
        }
        if (assignment.selectedUserId() != delivery.auditorUserId()
                || !current.scope().resourceSetKey().equals(assignment.resourceSetKey())
                || !delivery.assignmentRevision().equals(assignment.assignmentRevision())) {
            throw ApprovalOperationsProtocol.unavailable(
                    "Recovery-auditor authority changed after the delivery snapshot.");
        }
        ApprovalIdentityDirectory.Subject auditor = currentSubject(
                current.actor().tenantId(), delivery.auditorUserId(), true);
        if (!auditor.active()
                || !auditor.hasPermission("ADMIN.APPROVAL_OPERATIONS:VIEW")
                || auditor.personPublicId() == null) {
            throw ApprovalOperationsProtocol.unavailable(
                    "The assigned recovery auditor no longer has current authority.");
        }
        return new DeliveryObservation(
                auditor.userId(), auditor.personPublicId(), assignment.assignmentRevision(), clock.instant());
    }

    TaskObservation observeTask(
            Current current,
            TaskSnapshot task,
            long assigneeUserId,
            UUID assigneePersonPublicId) {
        if (current.actor().userId() == task.requesterUserId()
                || current.actor().userId() == assigneeUserId
                || Long.valueOf(assigneeUserId).equals(task.assigneeUserId())) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The requester, operator, and target assignee must remain distinct.");
        }
        ApprovalIdentityDirectory.RoleEligibility role;
        ApprovalIdentityDirectory.Subject candidate;
        try {
            role = identities.requireRole(current.actor().tenantId(), task.stepCandidateRole());
            candidate = identities.require(current.actor().tenantId(), assigneeUserId);
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.EXTERNAL_SERVICE_ERROR
                    || exception.getErrorCode() == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) {
                throw ApprovalOperationsProtocol.unavailable(
                        "Current task candidate authority is unavailable.");
            }
            throw ApprovalOperationsProtocol.rejected(
                    "The selected task assignee is not currently eligible.");
        }
        if (role == null || !role.activeAndStaffed()
                || candidate == null || !candidate.active()
                || !Long.valueOf(assigneeUserId).equals(candidate.userId())
                || !current.actor().tenantId().equals(candidate.tenantId())
                || !assigneePersonPublicId.equals(candidate.personPublicId())
                || !candidate.hasRole(task.stepCandidateRole())
                || !candidate.hasPermission("ACTION.APPROVAL_TASK:VIEW")) {
            throw ApprovalOperationsProtocol.rejected(
                    "The selected task assignee is not currently eligible.");
        }
        String revision = "identity-role-v1:" + role.roleCode() + ":"
                + role.lifecycleState() + ":" + role.eligibleUserCount();
        return new TaskObservation(
                candidate.userId(), candidate.personPublicId(), role.roleCode(), revision, clock.instant());
    }

    void consume(ApprovalStepUpVerifier.VerifiedChallenge challenge) {
        replay.consume(challenge);
    }

    private ApprovalRequestContext.Actor currentActor() {
        try {
            ApprovalRequestContext.Actor actor = workAuthority.requireCurrent(
                    ApprovalOperationsProtocol.PERMISSION);
            if (actor.personPublicId() == null) {
                throw ApprovalOperationsProtocol.unavailable(
                        "The current operation actor has no canonical person identity.");
            }
            return actor;
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.EXTERNAL_SERVICE_ERROR
                    || exception.getErrorCode() == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) {
                throw ApprovalOperationsProtocol.unavailable(
                        "Current Approval operation authority is unavailable.");
            }
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw ApprovalOperationsProtocol.forbidden(
                        "Current Approval operation authority was revoked.");
            }
            throw exception;
        }
    }

    private ApprovalIdentityDirectory.Subject currentSubject(
            long tenantId,
            long userId,
            boolean unavailableWhenMissing) {
        try {
            return identities.require(tenantId, userId);
        } catch (BaseException exception) {
            if (unavailableWhenMissing
                    || exception.getErrorCode() == ErrorCode.EXTERNAL_SERVICE_ERROR) {
                throw ApprovalOperationsProtocol.unavailable(
                        "Current Approval identity evidence is unavailable.");
            }
            throw exception;
        }
    }

    private void requireRecoveryEvidence(
            Current current,
            DeliverySnapshot delivery) {
        if (delivery.requestId() == null
                || delivery.originatorUserId() == null
                || delivery.auditorUserId() == null
                || !"ASSIGNED".equals(delivery.assignmentState())
                || !current.scope().resourceSetKey().equals(delivery.managementScope())
                || !current.scope().resourceSetKey().equals(delivery.recoveryScope())
                || delivery.assignmentRevision() == null
                || delivery.assignmentRevision().isBlank()
                || delivery.assignedAt() == null
                || delivery.originatorUserId().equals(delivery.auditorUserId())) {
            throw ApprovalOperationsProtocol.unavailable(
                    "Delivery recovery authority evidence is incomplete.");
        }
        if (current.actor().userId().equals(delivery.originatorUserId())
                || current.actor().userId().equals(delivery.auditorUserId())) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "Delivery originators and assigned auditors cannot execute recovery.");
        }
    }

    private BaseException unavailable(BaseException cause, String message) {
        if (cause.getErrorCode() == ErrorCode.FORBIDDEN
                || cause.getErrorCode() == ErrorCode.SOD_CONFLICT) return cause;
        return ApprovalOperationsProtocol.unavailable(message);
    }

    record Current(
            ApprovalRequestContext.Actor actor,
            ApprovalManagementScopeContext.Evidence scope,
            ApprovalDecisionRevisionContext.Evidence decision,
            String activationPolicy) {
    }

    interface DeliverySnapshot {
        UUID targetId();

        UUID requestId();

        Long originatorUserId();

        Long auditorUserId();

        String managementScope();

        String assignmentState();

        String recoveryScope();

        String assignmentRevision();

        Instant assignedAt();
    }

    interface TaskSnapshot {
        Long assigneeUserId();

        long requesterUserId();

        String stepCandidateRole();
    }

    record DeliveryObservation(
            long authorityUserId,
            UUID authorityPersonPublicId,
            String brokerRevision,
            Instant observedAt) {
    }

    record TaskObservation(
            long authorityUserId,
            UUID authorityPersonPublicId,
            String authorityRole,
            String brokerRevision,
            Instant observedAt) {
    }
}
