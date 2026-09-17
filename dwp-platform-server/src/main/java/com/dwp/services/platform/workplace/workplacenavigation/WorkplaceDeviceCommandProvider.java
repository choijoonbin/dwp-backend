package com.dwp.services.platform.workplace.workplacenavigation;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

/**
 * Adapter boundary for an approved device-management provider. Implementations receive no
 * reusable device credential; the persisted device identity is an opaque server-side binding.
 */
public interface WorkplaceDeviceCommandProvider {
    Optional<ProviderBinding> binding(String providerCode, long configurationVersion);

    boolean ready(ProviderBinding binding);

    ProviderCommandOutcome execute(ProviderCommand command);

    /** Read-only lookup by the stable command identity. It must never repeat the mutation. */
    ProviderCommandOutcome status(ProviderCommand command);

    Optional<ProviderRuntimeObservation> observe(ProviderObservationCommand command);

    record ProviderBinding(
            String providerCode,
            long configurationVersion,
            String credentialReference) { }

    record ProviderCommand(
            long tenantId,
            UUID commandId,
            UUID deviceId,
            DeviceCommandType type,
            Map<String, String> payload,
            String correlationId,
            ProviderBinding binding) { }

    record ProviderCommandOutcome(
            OutcomeState state,
            String providerOperationReference,
            String resultCode) { }

    record ProviderObservationCommand(
            long tenantId,
            ProviderCapability capability,
            ProviderBinding binding) { }

    record ProviderRuntimeObservation(
            ProviderReportedState state,
            String evidenceReference,
            java.time.OffsetDateTime sourceAt,
            java.time.OffsetDateTime lastSuccessAt,
            String errorCode) { }

    enum OutcomeState { SUCCEEDED, FAILED, RESULT_UNKNOWN }
}
