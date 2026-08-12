package com.dwp.services.auth.scim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ScimConnectorDtos {

    private ScimConnectorDtos() {
    }

    public record CreateRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}") String connectorKey,
            @NotBlank @Size(max = 200) String displayName) {
    }

    public record LifecycleRequest(@NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|RETIRED") String state) {
    }

    public record ConnectorSummary(
            UUID connectorId,
            String connectorKey,
            String displayName,
            String tokenPrefix,
            List<String> allowedOperations,
            String lifecycleState,
            Instant lastUsedAt,
            String health,
            long events24h,
            long failedEvents24h,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            long version) {
    }

    public record ProvisioningEvent(
            UUID eventId,
            UUID connectorId,
            String connectorName,
            String operation,
            String resourceType,
            String resourceId,
            String outcome,
            String correlationId,
            String summary,
            Instant occurredAt) {
    }

    public record CredentialIssued(ConnectorSummary connector, String bearerToken) {
    }
}
