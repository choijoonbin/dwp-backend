package com.dwp.services.people.integration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HrisDtos {

    private HrisDtos() {
    }

    public record SourceSystem(
            Long sourceSystemId,
            String sourceKey,
            String systemType,
            String name,
            String lifecycleState,
            long version) {
    }

    public record ConnectorInstance(
            UUID connectorInstanceId,
            long sourceSystemId,
            String sourceKey,
            String connectorKey,
            String connectorType,
            String endpointUri,
            String authMode,
            String credentialReference,
            String scheduleExpression,
            String lifecycleState,
            String healthState,
            Instant lastHealthCheckedAt,
            Instant lastSuccessfulSyncAt,
            long version) {
    }

    public record CreateConnectorRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,79}") String sourceKey,
            @NotBlank @Pattern(regexp = "WORKDAY|ORACLE_HCM|SAP_HCM|SCIM|CUSTOM") String sourceType,
            @NotBlank @Size(max = 200) String sourceName,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}") String connectorKey,
            @NotBlank @Pattern(regexp = "WORKDAY_REST|WORKDAY_SOAP|ORACLE_HCM_REST|SAP_SUCCESSFACTORS|SCIM_BRIDGE|CUSTOM_REST|FILE_IMPORT") String connectorType,
            @Size(max = 1000) String endpointUri,
            @NotBlank @Pattern(regexp = "NONE|BASIC|OAUTH2_CLIENT_CREDENTIALS|MTLS|SIGNED_REQUEST") String authMode,
            @Size(max = 255) String credentialReference,
            @Size(max = 120) String scheduleExpression) {
    }

    public record UpdateConnectorRequest(
            @Size(max = 1000) String endpointUri,
            @Size(max = 255) String credentialReference,
            @Size(max = 120) String scheduleExpression,
            @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|SUSPENDED|RETIRED") String lifecycleState,
            @NotNull @Min(0) Long version) {
    }

    public record ConfigurationCheck(
            UUID connectorInstanceId,
            boolean valid,
            String healthState,
            boolean externalConnectivityTested,
            List<String> issues,
            Instant checkedAt) {
    }

    public record MappingProfile(
            UUID mappingProfileId,
            String profileKey,
            String adapterType,
            String sourceSchemaVersion,
            String targetSchemaVersion,
            String lifecycleState,
            long version) {
    }

    public record SyncRun(
            UUID syncRunId,
            String sourceKey,
            String syncMode,
            String lifecycleState,
            String requestedWatermark,
            String committedWatermark,
            long readCount,
            long createdCount,
            long updatedCount,
            long rejectedCount,
            Instant startedAt,
            Instant completedAt) {
    }

    public record ImportResult(
            UUID syncRunId,
            String sourceKey,
            String lifecycleState,
            long readCount,
            long createdCount,
            long updatedCount,
            long rejectedCount,
            boolean replayed,
            boolean syntheticFixture,
            List<String> emittedEventTypes) {
    }
}
