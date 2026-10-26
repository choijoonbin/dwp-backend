package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalTerminalDecisionContext;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Transaction-local verifier for the dedicated HRM-013 approval command.
 *
 * <p>This deliberately does not reuse the management-object high-risk guard.  The route is a
 * work-item command and its scope is the sealed DECIDABLE_TASK_SELF_APPROVAL_SOD authority.
 * Reservation, challenge consumption and the native terminal vote therefore share one owner
 * transaction.</p>
 */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
public final class HrisSeparationStepUpEvidenceVerifier {
    public static final String ROUTE =
            "route.approvals.work.hris-separation-waiver-task-approve.action";
    public static final String CAPABILITY =
            "approvals.work.hris-separation-waiver-task.approve";
    public static final String ACTIVATION = "STEPUP-MGMT-HIGH-V1";
    public static final String TARGET_TYPE = "HRIS_SEPARATION_WAIVER_TASK";
    public static final String SERVICE_PATH_PREFIX =
            "/v1/hris/separation-waiver-tasks/";
    public static final String GATEWAY_PATH_PREFIX =
            "/api/approvals/v1/hris/separation-waiver-tasks/";

    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    private final Clock clock;

    public HrisSeparationStepUpEvidenceVerifier(ApprovalStepUpVerifier verifier,
            ApprovalStepUpReplayRepository replay) {
        this(verifier, replay, Clock.systemUTC());
    }

    HrisSeparationStepUpEvidenceVerifier(ApprovalStepUpVerifier verifier,
            ApprovalStepUpReplayRepository replay, Clock clock) {
        this.verifier = java.util.Objects.requireNonNull(verifier);
        this.replay = java.util.Objects.requireNonNull(replay);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public Permit begin(ApprovalRequestContext.Actor actor, UUID taskId,
            ApprovalDtos.DecisionRequest command, UUID correlationId,
            ApprovalStepUpHeaders headers, String rawBodySha256) {
        requireCommand(actor, taskId, command, correlationId, headers,
                rawBodySha256);
        var route = ApprovalPilotAuthorizationContext.highRisk().orElseThrow(
                HrisSeparationStepUpEvidenceVerifier::unavailable);
        var current = ApprovalDecisionRevisionContext.current().orElseThrow(
                HrisSeparationStepUpEvidenceVerifier::unavailable);
        Instant now = clock.instant();
        if (!ROUTE.equals(route.routeContractKey()) || !"ACTION".equals(route.routeKind())
                || !"full-work".equals(route.profileKey()) || route.readOnly()
                || !route.highRisk() || !CAPABILITY.equals(route.capabilityContractKey())
                || !ACTIVATION.equals(route.activationPolicy())
                || !"ACTION.APPROVAL_TASK:APPROVE".equals(route.resolvedCapabilityCode())
                || !route.predicatePolicyKeys().containsAll(Set.of(
                        "predicate.approval-task-decision.v1",
                        "predicate.approval.object-version.v1"))
                || !actor.permissions().contains(route.resolvedCapabilityCode())
                || !ROUTE.equals(current.routeContractKey())
                || !headers.decisionRevision().equals(current.revision())
                || current.validUntil() == null
                || !current.validUntil().toInstant().isAfter(now)
                || !Set.of("110", "111").contains(current.rolloutState())
                || blank(current.contextKey()) || blank(current.contextScopeKey())) {
            throw forbidden();
        }
        String gatewayPath = GATEWAY_PATH_PREFIX + taskId + "/approve";
        var binding = new ApprovalStepUpVerifier.CommandBinding(
                actor.userId(), actor.tenantId(), ROUTE, current.contextKey(),
                ACTIVATION, CAPABILITY, current.contextScopeKey(), TARGET_TYPE,
                taskId.toString(), command.expectedVersion(), "POST", gatewayPath,
                headers.idempotencyKey(), rawBodySha256,
                current.revision());
        LinkedHashMap<String, Object> requestIdentity = new LinkedHashMap<>();
        requestIdentity.put("binding", binding);
        requestIdentity.put("correlationId", correlationId.toString());
        var reservation = replay.reserve(binding, ROUTE,
                verifier.payloadSha256(requestIdentity));
        if (reservation.committed()) return Permit.prior(reservation);
        var challenge = verifier.verify(headers.challenge(), binding);
        canonicalUuid(challenge.challengeId());
        canonicalUuid(challenge.nonce());
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        return Permit.verified(challenge, reservation, taskId, correlationId);
    }

    /** Consumes the browser challenge and creates only the downstream typed attestation. */
    public ApprovalTerminalDecisionContext consume(Permit permit) {
        if (permit == null || permit.priorResult() || permit.challenge() == null) {
            throw unavailable();
        }
        var current = ApprovalDecisionRevisionContext.current().orElseThrow(
                HrisSeparationStepUpEvidenceVerifier::unavailable);
        Instant consumedAt = clock.instant();
        var challenge = permit.challenge();
        if (!ROUTE.equals(current.routeContractKey())
                || !challenge.binding().decisionRevision().equals(current.revision())
                || current.validUntil() == null
                || !current.validUntil().toInstant().isAfter(consumedAt)
                || !challenge.expiresAt().isAfter(consumedAt)
                || challenge.authenticatedAt().isAfter(consumedAt.plusSeconds(30))
                || consumedAt.isAfter(challenge.authenticatedAt().plusSeconds(600))
                || challenge.verifiedAt().isAfter(consumedAt)) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "HRM-013 approval authority changed before challenge consumption.");
        }
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        replay.consume(challenge);
        UUID evidenceId = canonicalUuid(challenge.challengeId());
        UUID browserNonce = canonicalUuid(challenge.nonce());
        UUID downstreamNonce;
        do downstreamNonce = UUID.randomUUID();
        while (downstreamNonce.equals(evidenceId) || downstreamNonce.equals(browserNonce));
        return ApprovalTerminalDecisionContext.hrisApprove(
                permit.taskId(), permit.correlationId(),
                new ApprovalTerminalDecisionContext.StepUpEvidence(
                        evidenceId, downstreamNonce, challenge.verifiedAt(), consumedAt));
    }

    public void complete(Permit permit) {
        if (permit != null && permit.challenge() != null) {
            replay.commit(permit.reservation().id(), permit.challenge());
        }
    }

    private static void requireCommand(ApprovalRequestContext.Actor actor, UUID taskId,
            ApprovalDtos.DecisionRequest command, UUID correlationId,
            ApprovalStepUpHeaders headers, String rawBodySha256) {
        if (actor == null || actor.personPublicId() == null || taskId == null
                || command == null || !"APPROVE".equals(command.decision())
                || command.comment() != null && command.comment().length() > 2_000
                || command.expectedVersion() == null || command.expectedVersion() < 0
                || command.quorum() == null || correlationId == null
                || headers == null || blank(headers.challenge())
                || blank(headers.idempotencyKey())
                || !headers.idempotencyKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
                || blank(headers.decisionRevision())
                || rawBodySha256 == null
                || !rawBodySha256.matches("[0-9a-f]{64}")
                || headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion().longValue()
                        != command.expectedVersion().longValue()) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "The HRM-013 approval command evidence is incomplete.");
        }
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "HRM-013 step-up identifiers must be canonical UUIDs.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN,
                "The resolved route does not authorize this HRM-013 approval command.");
    }

    private static BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current HRM-013 approval authority is unavailable.");
    }

    public record Permit(ApprovalStepUpVerifier.VerifiedChallenge challenge,
            ApprovalStepUpReplayRepository.Reservation reservation,
            UUID taskId, UUID correlationId) {
        static Permit verified(ApprovalStepUpVerifier.VerifiedChallenge challenge,
                ApprovalStepUpReplayRepository.Reservation reservation,
                UUID taskId, UUID correlationId) {
            return new Permit(challenge, reservation, taskId, correlationId);
        }

        static Permit prior(ApprovalStepUpReplayRepository.Reservation reservation) {
            return new Permit(null, reservation, null, null);
        }

        public boolean priorResult() {
            return reservation != null && reservation.committed();
        }
    }
}
