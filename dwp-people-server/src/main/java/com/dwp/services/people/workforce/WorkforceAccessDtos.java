package com.dwp.services.people.workforce;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkforceAccessDtos {

    private WorkforceAccessDtos() {
    }

    public record Policy(
            UUID policyId,
            String subjectType,
            String subjectRef,
            String populationType,
            UUID organizationId,
            String organizationName,
            List<String> fieldGroups,
            List<String> actionCodes,
            Instant validFrom,
            Instant validTo,
            String lifecycleState,
            String justification,
            long version) {
    }

    public record CreatePolicyRequest(
            @NotBlank @Pattern(regexp = "ROLE|USER") String subjectType,
            @NotBlank @Size(max = 80) String subjectRef,
            @NotBlank @Pattern(regexp = "TENANT|ORG_UNIT|ORG_TREE") String populationType,
            UUID organizationId,
            @NotEmpty @Size(max = 4) List<@NotBlank String> fieldGroups,
            @NotEmpty @Size(max = 2) List<@NotBlank String> actionCodes,
            Instant validFrom,
            @Future Instant validTo,
            @NotBlank @Size(max = 1000) String justification) {
    }

    public record RevokePolicyRequest(
            @NotNull Long version,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record OrganizationOption(
            UUID organizationId,
            String organizationKey,
            String name,
            UUID parentOrganizationId) {
    }
}
