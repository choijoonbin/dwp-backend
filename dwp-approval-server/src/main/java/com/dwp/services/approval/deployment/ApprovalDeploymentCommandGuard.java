package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.GovernedCommand;

@Component
final class ApprovalDeploymentCommandGuard {
    private static final String CAPABILITY = "approvals.operations.execute";
    private static final String ACTIVATION_POLICY = "STEPUP-MGMT-HIGH-V1";

    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    private final Clock clock;

    @Autowired
    ApprovalDeploymentCommandGuard(
            ApprovalStepUpVerifier verifier,
            ApprovalStepUpReplayRepository replay) {
        this(verifier, replay, Clock.systemUTC());
    }

    ApprovalDeploymentCommandGuard(
            ApprovalStepUpVerifier verifier,
            ApprovalStepUpReplayRepository replay,
            Clock clock) {
        this.verifier = verifier;
        this.replay = replay;
        this.clock = clock;
    }

    Permit begin(
            ApprovalDeploymentHttpAuthority.Current current,
            Profile profile,
            UUID targetId,
            long expectedVersion,
            Object payload,
            ApprovalStepUpHeaders headers) {
        var route = ApprovalPilotAuthorizationContext.highRisk().orElseThrow(() ->
                unavailable("Exact high-risk deployment route authority is unavailable."));
        if (!profile.routeContractKey().equals(route.routeContractKey())
                || !profile.routeContractKey().equals(current.decision().routeContractKey())
                || !CAPABILITY.equals(route.capabilityContractKey())
                || !ACTIVATION_POLICY.equals(route.activationPolicy())
                || !route.highRisk()
                || !current.management().opaqueScopeKey()
                        .equals(current.decision().contextScopeKey())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The current route does not authorize this deployment command.");
        }
        if (headers == null || headers.challenge() == null
                || headers.idempotencyKey() == null
                || !headers.idempotencyKey().matches("[A-Za-z0-9._:-]{1,120}")
                || !current.decision().revision().equals(headers.decisionRevision())
                || headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "Command-bound deployment step-up evidence is required.");
        }
        String publicPath = "/api/approvals" + profile.path(targetId);
        ApprovalStepUpVerifier.CommandBinding binding =
                new ApprovalStepUpVerifier.CommandBinding(
                        current.actor().userId(), current.actor().tenantId(),
                        profile.routeContractKey(), current.decision().contextKey(),
                        ACTIVATION_POLICY, CAPABILITY,
                        current.decision().contextScopeKey(),
                        profile.targetType(), targetId.toString(),
                        expectedVersion, "POST", publicPath,
                        headers.idempotencyKey(), verifier.payloadSha256(payload),
                        current.decision().revision());
        Map<String, Object> requestMaterial = new LinkedHashMap<>();
        requestMaterial.put("routeContractKey", binding.commandContractKey());
        requestMaterial.put("actorUserId", binding.actorUserId());
        requestMaterial.put("tenantId", binding.tenantId());
        requestMaterial.put("contextKey", binding.contextKey());
        requestMaterial.put("contextScopeKey", binding.scopeRef());
        requestMaterial.put("targetType", binding.targetType());
        requestMaterial.put("targetId", binding.targetId());
        requestMaterial.put("targetVersion", binding.targetVersion());
        requestMaterial.put("commandMethod", binding.commandMethod());
        requestMaterial.put("commandPath", binding.commandPath());
        requestMaterial.put("idempotencyKey", binding.idempotencyKey());
        requestMaterial.put("payloadSha256", binding.payloadSha256());
        requestMaterial.put("decisionRevision", binding.decisionRevision());
        ApprovalStepUpReplayRepository.Reservation reservation = replay.reserve(
                binding, profile.routeContractKey(),
                verifier.payloadSha256(requestMaterial));
        if (reservation.committed()) {
            return Permit.prior(reservation);
        }
        ApprovalStepUpVerifier.VerifiedChallenge challenge =
                verifier.verify(headers.challenge(), binding);
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        String evidenceReference = "verified:" + ApprovalDeploymentCanonical.sha256Text(
                challenge.issuer() + ":" + challenge.challengeId() + ":" + challenge.nonce());
        GovernedCommand governed = new GovernedCommand(
                current.actor().userId(), headers.idempotencyKey(),
                current.decision().contextKey(), current.decision().revision(),
                evidenceReference, clock.instant(), challenge.expiresAt());
        return Permit.verified(governed, challenge, reservation);
    }

    void complete(Permit permit) {
        if (permit.challenge() != null) {
            replay.consume(permit.challenge());
            replay.commit(permit.reservation().id(), permit.challenge());
        }
    }

    enum Profile {
        PACKAGE_CREATE(
                "route.approvals.admin.deployment-package-create.action",
                "APPROVAL_DEPLOYMENT_PACKAGE",
                "/v1/admin/operations/deployments/packages"),
        PROMOTION_CREATE(
                "route.approvals.admin.deployment-promotion-create.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions"),
        APPROVE(
                "route.approvals.admin.deployment-promotion-review.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/approval"),
        SCHEDULE(
                "route.approvals.admin.deployment-promotion-schedule.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/schedule"),
        ACTIVATE(
                "route.approvals.admin.deployment-activation.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/activation"),
        ACTIVATION_EVIDENCE(
                "route.approvals.admin.deployment-activation-evidence.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/activation-evidence"),
        CANARY_CONTROL(
                "route.approvals.admin.deployment-canary-control.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/canary/control"),
        CANARY_TELEMETRY(
                "route.approvals.admin.deployment-canary-telemetry.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/canary/telemetry"),
        ROLLBACK(
                "route.approvals.admin.deployment-rollback.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/rollback"),
        ROLLBACK_EVIDENCE(
                "route.approvals.admin.deployment-rollback-evidence.action",
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/v1/admin/operations/deployments/promotions/%s/rollback-evidence");

        private final String routeContractKey;
        private final String targetType;
        private final String pathTemplate;

        Profile(String routeContractKey, String targetType, String pathTemplate) {
            this.routeContractKey = routeContractKey;
            this.targetType = targetType;
            this.pathTemplate = pathTemplate;
        }

        String routeContractKey() {
            return routeContractKey;
        }

        String targetType() {
            return targetType;
        }

        String path(UUID targetId) {
            return pathTemplate.contains("%s")
                    ? pathTemplate.formatted(targetId)
                    : pathTemplate;
        }
    }

    record Permit(
            GovernedCommand governed,
            ApprovalStepUpVerifier.VerifiedChallenge challenge,
            ApprovalStepUpReplayRepository.Reservation reservation) {
        static Permit verified(
                GovernedCommand governed,
                ApprovalStepUpVerifier.VerifiedChallenge challenge,
                ApprovalStepUpReplayRepository.Reservation reservation) {
            return new Permit(governed, challenge, reservation);
        }

        static Permit prior(ApprovalStepUpReplayRepository.Reservation reservation) {
            return new Permit(null, null, reservation);
        }

        boolean priorResult() {
            return reservation != null && reservation.committed();
        }
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
