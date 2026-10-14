package com.dwp.services.payroll.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(PayrollFieldProjectionDecisionPort.class)
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class UnavailablePayrollFieldProjectionDecisionPort
        implements PayrollFieldProjectionDecisionPort {

    @Override
    public Decision decision(
            PayrollCalculationAuthority.Subject subject,
            String responseFieldGroup) {
        return Decision.UNAVAILABLE;
    }
}
