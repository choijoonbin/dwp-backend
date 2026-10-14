package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class PayrollFieldProjectionGuard {

    private final PayrollFieldProjectionDecisionPort decisions;

    public PayrollFieldProjectionGuard(PayrollFieldProjectionDecisionPort decisions) {
        this.decisions = decisions;
    }

    public void requireFull(PayrollCalculationAuthority.Subject subject) {
        PayrollCalculationRoute route = java.util.Arrays.stream(
                        PayrollCalculationRoute.values())
                .filter(candidate -> candidate.action() == subject.routeAction())
                .findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Generated Payroll route projection is unavailable."));
        requireFull(subject, route);
    }

    void requireFull(
            PayrollCalculationAuthority.Subject subject,
            PayrollCalculationRoute route) {
        for (String fieldGroup : route.fieldGroups()) {
            PayrollFieldProjectionDecisionPort.Decision decision =
                    decisions.decision(subject, fieldGroup);
            if (decision == PayrollFieldProjectionDecisionPort.Decision.FULL) continue;
            if (decision == PayrollFieldProjectionDecisionPort.Decision.UNAVAILABLE) {
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Trusted Payroll field projection is unavailable.");
            }
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The closed Payroll response cannot represent the current field projection.");
        }
    }
}
