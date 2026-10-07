package com.dwp.services.people.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Wire contract for one disposable, run-bound People workforce fixture. */
final class LocalSyntheticPeopleWorkforceBootstrapDtos {

    private LocalSyntheticPeopleWorkforceBootstrapDtos() {
    }

    /**
     * {@code administratorActorId} is the positive Auth user id asserted by the
     * run-bound caller. People validates the active tenant binding and binds the
     * assertion into the response receipt; it does not invent an Auth mapping.
     * The runner must cross-check this value against Auth's provision/activation
     * response before login.
     */
    record BootstrapRequest(
            @NotBlank @Pattern(regexp = "w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}") String runId,
            @NotNull UUID providerTenantId,
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long administratorActorId,
            @NotNull @Size(max = 3) List<
                    @Pattern(regexp = "(?:HR_ADMIN|PAYROLL_ADMIN|PEOPLE_ADMIN)") String>
                    plannedIdentityRoleCodes) {
    }

    record BootstrapResponse(
            String runId,
            UUID providerTenantId,
            long tenantId,
            long administratorActorId,
            List<String> plannedIdentityRoleCodes,
            UUID actorPersonPublicId,
            UUID actorWorkerPublicId,
            UUID actorAssignmentPublicId,
            UUID actorLegalEmployerPublicId,
            String actorWorkerNumber,
            UUID targetPersonPublicId,
            UUID targetWorkerPublicId,
            UUID targetAssignmentPublicId,
            UUID syncRunId,
            boolean importReplayed,
            long importedWorkerCount,
            UUID workforceAccessPolicyId,
            long workforceAccessPolicyVersion,
            long targetPopulationCount,
            String targetPopulationRevision,
            AuthWorkforceBinding authWorkforceBinding,
            String receiptSha256) {
    }

    record AuthWorkforceBinding(
            String endpoint,
            String tokenHeader,
            long expectedAdministratorUserId,
            AuthWorkforceEvent event) {
    }

    /** Exact request body accepted by Auth's official workforce-events endpoint. */
    record AuthWorkforceEvent(
            UUID eventId,
            UUID providerTenantId,
            UUID personPublicId,
            String externalId,
            String workerNumber,
            String displayName,
            String givenName,
            String familyName,
            String workEmail,
            String jobTitle,
            String preferredLocale,
            String workerStatus,
            String sourceVersion) {
    }
}
