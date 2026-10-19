package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Production-safe placeholder: no PDX-015 provider is admitted by this slice. */
@Component
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class FailClosedEmploymentContractTemplateQueryAdapter
        implements EmploymentContractTemplateQueryPort {

    @Override
    public EmploymentContractTemplateSnapshotV1 requirePublished(TemplateQuery query) {
        throw EmploymentContractException.unavailable(
                "The PDX-015 employment-contract template provider is unavailable.");
    }
}
