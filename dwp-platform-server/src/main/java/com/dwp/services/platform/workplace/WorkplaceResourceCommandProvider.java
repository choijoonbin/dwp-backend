package com.dwp.services.platform.workplace;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceResourceCommandDtos.*;

/** Provider boundary for commands that can change a physical Workplace resource. */
public interface WorkplaceResourceCommandProvider {
    Optional<ProviderBinding> binding(String providerCode, long configurationVersion);

    boolean ready(ProviderBinding binding);

    ProviderOutcome execute(ProviderCommand command);

    /** Read-only provider lookup. Implementations must never repeat the mutation. */
    ProviderOutcome status(ProviderCommand command);

    record ProviderBinding(
            String providerCode,
            long configurationVersion,
            String credentialReference) { }

    record ProviderCommand(
            long tenantId,
            UUID commandId,
            UUID bookingId,
            UUID resourceId,
            ResourceCommandType commandType,
            Map<String, String> parameters,
            String correlationId,
            ProviderBinding binding) { }

    record ProviderOutcome(
            ResourceCommandState state,
            String providerOperationReference,
            String resultCode) { }
}
