package com.dwp.services.people.hris.employeeservice.employmentcontract;

/** Render/seal/object-store boundary for an immutable employment contract. */
public interface EmploymentContractArtifactPort {
    EmploymentContractDtos.ArtifactResult issue(
            EmploymentContractDtos.ArtifactRequest request);
}
