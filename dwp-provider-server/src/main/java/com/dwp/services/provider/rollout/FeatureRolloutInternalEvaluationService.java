package com.dwp.services.provider.rollout;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;

@Service
public class FeatureRolloutInternalEvaluationService {

    private final FeatureRolloutService rolloutService;
    private final FeatureRolloutDecisionOutboxRepository decisionOutbox;

    public FeatureRolloutInternalEvaluationService(
            FeatureRolloutService rolloutService,
            FeatureRolloutDecisionOutboxRepository decisionOutbox) {
        this.rolloutService = rolloutService;
        this.decisionOutbox = decisionOutbox;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FeatureRolloutDtos.InternalEvaluation evaluate(
            FeatureRolloutDtos.InternalEvaluationRequest request) {
        FeatureRolloutDtos.Evaluation decision = rolloutService.evaluateProductSurfaceFlag(
                request.flagKey(), request.authTenantId());
        long revision = decisionOutbox.revision(request.flagKey());
        boolean enabled = decision.value().isBoolean() && decision.value().booleanValue();
        return new FeatureRolloutDtos.InternalEvaluation(
                request.flagKey(),
                enabled,
                decision.reasonCode(),
                opaque(revision),
                cohort(decision, enabled),
                decision.evaluatedAt());
    }

    static String opaque(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("Decision revision cannot be negative");
        }
        return "rev-" + String.format(Locale.ROOT, "%020d", revision);
    }

    static String cohort(FeatureRolloutDtos.Evaluation evaluation, boolean enabled) {
        if ("PERCENTAGE_EXCLUDED".equals(evaluation.reasonCode())) {
            return "holdout";
        }
        if (!enabled) {
            return "baseline";
        }
        BigDecimal exposure = evaluation.exposurePercentage();
        if (exposure == null || exposure.compareTo(BigDecimal.valueOf(100)) >= 0) {
            return "full";
        }
        if (exposure.compareTo(BigDecimal.TEN) <= 0) {
            return "eligible-10";
        }
        if (exposure.compareTo(BigDecimal.valueOf(25)) <= 0) {
            return "eligible-25";
        }
        if (exposure.compareTo(BigDecimal.valueOf(50)) <= 0) {
            return "eligible-50";
        }
        return "eligible-90";
    }
}
