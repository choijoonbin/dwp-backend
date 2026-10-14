package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class PayrollHighRiskCommandGuard {

    private static final String ACTIVATION_POLICY = "STEPUP-MGMT-HIGH-V1";
    private final PayrollStepUpVerifier verifier;
    private final PayrollV41PepRegistry registry;
    private final Clock clock;

    PayrollHighRiskCommandGuard(
            PayrollStepUpVerifier verifier,
            PayrollV41PepRegistry registry) {
        this(verifier, registry, Clock.systemUTC());
    }

    PayrollHighRiskCommandGuard(
            PayrollStepUpVerifier verifier,
            PayrollV41PepRegistry registry,
            Clock clock) {
        this.verifier = verifier;
        this.registry = registry;
        this.clock = clock;
    }

    public PayrollStepUpEvidence require(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            long currentVersion,
            String publicPath,
            Object canonicalPayload,
            PayrollStepUpHeaders headers) {
        PayrollCalculationAuthority.Subject subject = actor.subject();
        PayrollV41PepRegistry.Binding routeBinding = registry.resolve(
                        "POST", publicPath.replaceFirst("^/api/payroll", ""))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Generated Payroll step-up route is unavailable."));
        if (routeBinding.route().action() != subject.routeAction()
                || !routeBinding.routeContractKey().equals(subject.routeContractKey())
                || !routeBinding.capabilityCodesByContract()
                .containsKey(subject.capabilityContractKey())
                || !routeBinding.highRisk()
                || !"PAYROLL_PROCESSING".equals(subject.purposeCode())
                || !routeBinding.activeAccessModes().contains(subject.accessMode())
                || !"POST".equals(routeBinding.method())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The exact generated Payroll route does not authorize this command.");
        }
        if (headers == null || blank(headers.challenge())
                || blank(headers.idempotencyKey())
                || blank(headers.expectedDecisionRevision())
                || headers.expectedObjectVersion() != currentVersion) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Command-bound Payroll step-up headers are required.");
        }
        if (!headers.expectedDecisionRevision().equals(subject.decisionRevision())
                || !subject.revalidateAt().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "Payroll authority changed after challenge issuance.");
        }
        String payloadSha256 = verifier.payloadSha256(canonicalPayload);
        String targetId = targetId(publicPath, runId);
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        actor.actorId(), actor.tenantId(), subject.routeContractKey(),
                        subject.contextKey(), ACTIVATION_POLICY,
                        subject.capabilityContractKey(), subject.contextScopeKey(),
                        "PAYROLL_RUN", targetId, currentVersion,
                        "POST", publicPath, headers.idempotencyKey(), payloadSha256,
                        subject.decisionRevision());
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verified =
                verifier.verify(headers.challenge(), binding);
        return new PayrollStepUpEvidence(
                verified.challengeId(), verified.nonce(), verified.issuer(),
                verified.expiresAt(), payloadSha256, subject.decisionRevision());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String targetId(String publicPath, UUID runId) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^/api/payroll/v1/runs/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                        + "[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
                        + "[0-9a-fA-F]{12})/(approvals|finalize)$")
                .matcher(publicPath);
        if (!matcher.matches()
                || !UUID.fromString(matcher.group(1)).equals(runId)) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "The Payroll step-up target is invalid.");
        }
        return matcher.group(1);
    }
}
