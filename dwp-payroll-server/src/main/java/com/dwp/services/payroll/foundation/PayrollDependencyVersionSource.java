package com.dwp.services.payroll.foundation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.DependencyPin;

interface PayrollDependencyVersionSource {

    OptionalLong currentVersion(long tenantId, DependencyPin pin);
}

/** External owner versions must be supplied by a later owner-contract adapter, never guessed. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class FailClosedPayrollDependencyVersionSource implements PayrollDependencyVersionSource {

    @Override
    public OptionalLong currentVersion(long tenantId, DependencyPin pin) {
        return OptionalLong.empty();
    }
}
