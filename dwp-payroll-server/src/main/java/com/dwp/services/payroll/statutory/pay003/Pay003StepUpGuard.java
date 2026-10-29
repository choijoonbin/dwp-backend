package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.payroll.security.PayrollStepUpVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class Pay003StepUpGuard implements Pay003Ports.StepUpPort {

    private static final String POLICY = "STEPUP-MGMT-HIGH-V1";
    private final PayrollStepUpVerifier verifier;
    private final Clock clock;

    Pay003StepUpGuard(PayrollStepUpVerifier verifier) {
        this(verifier, Clock.systemUTC());
    }

    Pay003StepUpGuard(PayrollStepUpVerifier verifier, Clock clock) {
        this.verifier = verifier;
        this.clock = clock;
    }

    @Override
    public Pay003StepUpEvidence require(
            Pay003Authority.Actor actor,
            Pay003Models.PackSnapshot snapshot,
            Pay003Models.ActivationCommand command,
            Pay003Models.ActivationHeaders headers) {
        actor.require(Pay003Route.ACTIVATE);
        Pay003Authority.Subject subject = actor.subject();
        if (!headers.expectedDecisionRevision().equals(subject.decisionRevision())
                || !subject.revalidateAt().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "PAY-003 authority changed after challenge issuance.");
        }
        if (!Pay003Contract.ACTIVATION_PURPOSE.equals(subject.purposeCode())
                || !"ELEVATED".equals(subject.accessMode())
                || !Pay003Route.ACTIVATE.routeContractKey().equals(
                subject.route().routeContractKey())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The exact HIGH PAY-003 activation route is required.");
        }
        String publicPath = "/api/payroll/v1/country-packs/"
                + snapshot.packId() + "/activate";
        String payloadSha256 = verifier.payloadSha256(command);
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        subject.actorId(), subject.tenantId(),
                        subject.route().routeContractKey(), subject.contextKey(),
                        POLICY, "hcm.operations.statutory.execute",
                        subject.contextScopeKey(), "COUNTRY_PACK",
                        snapshot.packId().toString(), headers.expectedRevision(),
                        "POST", publicPath, headers.idempotencyKey(),
                        payloadSha256, subject.decisionRevision());
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verified =
                verifier.verify(headers.challenge(), binding);
        return new Pay003StepUpEvidence(
                verified.challengeId(), verified.nonce(), verified.issuer(),
                verified.expiresAt(), payloadSha256, subject.decisionRevision());
    }
}
