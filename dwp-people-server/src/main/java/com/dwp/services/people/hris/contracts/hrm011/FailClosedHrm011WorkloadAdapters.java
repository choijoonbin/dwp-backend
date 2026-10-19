package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Fail-closed data adapter until a typed payroll fact provider is separately admitted. */
public final class FailClosedHrm011WorkloadAdapters {
    private FailClosedHrm011WorkloadAdapters() {
    }

    @Component
    @ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
            name = "enabled", havingValue = "true", matchIfMissing = false)
    static final class PayrollFacts implements Hrm011WorkloadIngress.PayrollWorkerFactSource {
        @Override
        public Hrm011WorkloadIngress.PayrollSlice read(
                long tenantId, String projection, Instant asOf,
                int limit, String afterCanonicalKey) {
            throw EmploymentContractException.unavailable(
                    "The typed payroll worker-fact provider is unavailable.");
        }
    }
}
