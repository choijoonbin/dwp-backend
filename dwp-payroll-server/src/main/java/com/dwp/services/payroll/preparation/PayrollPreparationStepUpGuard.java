package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.dwp.services.payroll.security.PayrollStepUpVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollPreparationStepUpGuard {

    private static final String PUBLIC_PATH = "/api/payroll/v1/runs";
    private static final String ACTIVATION_POLICY = "STEPUP-MGMT-HIGH-V1";
    private final PayrollStepUpVerifier verifier;
    private final Clock clock;

    @Autowired
    PayrollPreparationStepUpGuard(PayrollStepUpVerifier verifier) {
        this(verifier, Clock.systemUTC());
    }

    PayrollPreparationStepUpGuard(
            PayrollStepUpVerifier verifier, Clock clock) {
        this.verifier = verifier;
        this.clock = clock;
    }

    PayrollStepUpEvidence require(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command,
            Headers headers) {
        PayrollPreparationAuthority.Subject subject = actor.subject();
        actor.requirePrepare();
        if (headers == null || blank(headers.challenge())
                || blank(headers.idempotencyKey())
                || blank(headers.expectedDecisionRevision())
                || headers.expectedObjectVersion() != 0) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Command-bound Payroll preparation step-up is required.");
        }
        if (!headers.expectedDecisionRevision().equals(subject.decisionRevision())
                || !subject.revalidateAt().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "Payroll preparation authority changed after challenge issuance.");
        }
        if (subject.route() != PayrollPreparationRoute.PREPARE
                || !"PAYROLL_PROCESSING".equals(subject.purposeCode())
                || !"ELEVATED".equals(subject.accessMode())
                || !PayrollPreparationRoute.PREPARE.capabilityContractKey()
                .equals(subject.capabilityContractKey())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The exact generated Payroll prepare route is required.");
        }
        String payloadSha256 = verifier.payloadSha256(command);
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        subject.actorId(), subject.tenantId(),
                        subject.route().routeContractKey(), subject.contextKey(),
                        ACTIVATION_POLICY, subject.capabilityContractKey(),
                        subject.contextScopeKey(), "PAYROLL_RUN_PREPARATION",
                        command.preparationId().toString(), 0,
                        "POST", PUBLIC_PATH, headers.idempotencyKey(),
                        payloadSha256, subject.decisionRevision());
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verified =
                verifier.verify(headers.challenge(), binding);
        return new PayrollStepUpEvidence(
                verified.challengeId(), verified.nonce(), verified.issuer(),
                verified.expiresAt(), payloadSha256,
                subject.decisionRevision());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record Headers(
            String challenge,
            String idempotencyKey,
            String expectedDecisionRevision,
            long expectedObjectVersion) {
    }
}
