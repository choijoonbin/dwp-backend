package com.dwp.services.approval.routingdirectory;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

@Component
final class RoutingDirectoryEndpointAuthority {
    static final String RESOURCE = "ADMIN.APPROVAL_DESIGN";
    static final String UPDATE_CAPABILITY = "approvals.design.update";
    static final String PUBLISH_CAPABILITY = "approvals.design.publish";

    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    private final ApprovalWorkAuthority workAuthority;

    RoutingDirectoryEndpointAuthority(
            ApprovalStepUpVerifier verifier,
            ApprovalStepUpReplayRepository replay,
            ApprovalWorkAuthority workAuthority) {
        this.verifier = verifier;
        this.replay = replay;
        this.workAuthority = workAuthority;
    }

    void read() {
        current(Set.of("VIEW", "MANAGE"));
    }

    Permit begin(
            String capability,
            String targetType,
            UUID targetId,
            long expectedVersion,
            String method,
            String path,
            Object payload,
            ApprovalStepUpHeaders headers) {
        boolean publish = PUBLISH_CAPABILITY.equals(capability);
        if (!publish && !UPDATE_CAPABILITY.equals(capability)) {
            throw forbidden("The routing command capability is not supported.");
        }
        Current current = current(Set.of(
                publish ? "PUBLISH" : "UPDATE", "MANAGE"));
        var matches = ApprovalPilotAuthorizationContext.current().orElseThrow(() -> unavailable(
                        "Current Approval route authority is unavailable.")).stream()
                .filter(value -> !value.readOnly()
                        && current.decision().routeContractKey().equals(value.routeContractKey())
                        && capability.equals(value.capabilityContractKey()))
                .toList();
        if (matches.size() != 1) {
            throw forbidden("The current route does not authorize this routing command.");
        }
        var route = matches.getFirst();
        if (publish != route.highRisk()
                || publish && !"STEPUP-MGMT-HIGH-V1".equals(route.activationPolicy())) {
            throw forbidden("The current route does not authorize this routing command.");
        }
        requireHeaders(headers, current, expectedVersion, publish);
        if (!publish) return new Permit(null, null);
        ApprovalStepUpVerifier.CommandBinding binding = new ApprovalStepUpVerifier.CommandBinding(
                current.actor().userId(), current.actor().tenantId(),
                current.decision().routeContractKey(), current.decision().contextKey(),
                route.activationPolicy(), capability, current.scope().opaqueScopeKey(),
                targetType, targetId.toString(), expectedVersion, method,
                "/api/approvals" + path, headers.idempotencyKey(),
                verifier.payloadSha256(payload), current.decision().revision());
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("binding", binding);
        material.put("resourceSetKey", current.scope().resourceSetKey());
        var reservation = replay.reserve(binding, binding.commandContractKey(),
                verifier.payloadSha256(material));
        if (reservation.committed()) return new Permit(null, reservation);
        var challenge = verifier.verify(headers.challenge(), binding);
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        return new Permit(challenge, reservation);
    }

    void complete(Permit permit) {
        if (permit.challenge() == null) return;
        replay.consume(permit.challenge());
        replay.commit(permit.reservation().id(), permit.challenge());
    }

    private Current current(Set<String> actions) {
        var scope = ApprovalManagementScopeContext.current().orElseThrow(() -> unavailable(
                "Current Approval management scope is unavailable."));
        var decision = ApprovalDecisionRevisionContext.current().orElseThrow(() -> unavailable(
                "Current Approval decision evidence is unavailable."));
        if (decision.routeContractKey() == null) {
            throw unavailable("Current Approval route contract is unavailable.");
        }
        boolean routePresent = ApprovalPilotAuthorizationContext.current().orElseThrow(() -> unavailable(
                        "Current Approval route authority is unavailable.")).stream()
                .anyMatch(value -> decision.routeContractKey().equals(value.routeContractKey()));
        if (!routePresent || decision.validUntil() == null
                || !decision.validUntil().isAfter(OffsetDateTime.now())
                || !("110".equals(decision.rolloutState()) || "111".equals(decision.rolloutState()))
                || decision.contextKey() == null || decision.contextKey().isBlank()
                || !scope.opaqueScopeKey().equals(decision.contextScopeKey())) {
            throw forbidden("Routing-directory scope or decision evidence does not match.");
        }
        var permissions = actions.stream()
                .map(action -> RESOURCE + ":" + action)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var actor = workAuthority.requireAnyCurrent(permissions);
        if (actor.userId() == null || actor.tenantId() == null || actor.personPublicId() == null) {
            throw unavailable("Current Approval actor identity is incomplete.");
        }
        return new Current(actor, scope, decision);
    }

    private void requireHeaders(
            ApprovalStepUpHeaders headers,
            Current current,
            long expectedVersion,
            boolean challengeRequired) {
        if (headers == null
                || (challengeRequired
                && (headers.challenge() == null || headers.challenge().isBlank()))
                || headers.idempotencyKey() == null
                || !headers.idempotencyKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
                || headers.decisionRevision() == null
                || !headers.decisionRevision().equals(current.decision().revision())
                || headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion() != expectedVersion) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "Routing command evidence does not match the current object and decision.");
        }
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    record Permit(
            ApprovalStepUpVerifier.VerifiedChallenge challenge,
            ApprovalStepUpReplayRepository.Reservation reservation) {
    }

    private record Current(
            ApprovalRequestContext.Actor actor,
            ApprovalManagementScopeContext.Evidence scope,
            ApprovalDecisionRevisionContext.Evidence decision) {
    }
}
