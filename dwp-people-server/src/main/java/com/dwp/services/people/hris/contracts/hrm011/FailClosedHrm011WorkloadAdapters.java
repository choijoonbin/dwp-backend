package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Fail-closed adapters until central admits the final People security chain and data source. */
public final class FailClosedHrm011WorkloadAdapters {
    private FailClosedHrm011WorkloadAdapters() {
    }

    @Component
    @ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
            name = "enabled", havingValue = "true", matchIfMissing = false)
    static final class Authority implements Hrm011WorkloadIngress.WorkloadAuthorityPort {
        @Override
        public Hrm011WorkloadIngress.TrustedWorkload require(
                Hrm011WorkloadIngress.WorkloadEvidence evidence, String purposeCode) {
            throw EmploymentContractException.denied(
                    "The HRM-011 workload ingress is not admitted to the final security chain.");
        }
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
