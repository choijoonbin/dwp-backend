package com.dwp.services.people.hris.employeeservice.employmentcontract;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Production-safe placeholder: no renderer/provider binding is claimed. */
@Component
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class FailClosedEmploymentContractArtifactAdapter
        implements EmploymentContractArtifactPort {

    @Override
    public EmploymentContractDtos.ArtifactResult issue(
            EmploymentContractDtos.ArtifactRequest request) {
        throw EmploymentContractException.unavailable(
                "The employment-contract artifact provider is unavailable.");
    }
}
