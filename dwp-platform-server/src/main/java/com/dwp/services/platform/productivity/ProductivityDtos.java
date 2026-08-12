package com.dwp.services.platform.productivity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;

public final class ProductivityDtos {

    private ProductivityDtos() {
    }

    public record Overview(
            long connectors,
            long activeConnectors,
            long connectedSubjects,
            long staleStreams,
            long failedRuns24h,
            Instant lastSuccessfulSyncAt,
            List<Connector> connectorHealth,
            List<SyncRun> recentRuns) {
    }

    public record Connector(
            UUID connectorId,
            String connectorKey,
            String displayName,
            ProviderType providerType,
            AuthMode authMode,
            String providerTenantId,
            String clientId,
            String credentialReference,
            String redirectUri,
            List<String> requestedScopes,
            List<String> capabilities,
            ConnectorLifecycle lifecycleState,
            ConnectorHealth healthState,
            PolicyState policyState,
            String safeErrorCode,
            Instant lastConfigurationCheckAt,
            Instant lastSuccessfulSyncAt,
            int consecutiveFailures,
            long version) {
    }

    public record SaveConnectorRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,79}") String connectorKey,
            @NotBlank @Size(max = 160) String displayName,
            @NotNull ProviderType providerType,
            @NotNull AuthMode authMode,
            @NotBlank @Size(max = 160) String providerTenantId,
            @NotBlank @Size(max = 160) String clientId,
            @NotBlank @Pattern(regexp = "env:[A-Z][A-Z0-9_]{1,126}") String credentialReference,
            @NotBlank @Size(max = 1000) String redirectUri,
            @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 120) String> requestedScopes,
            @NotNull PolicyState policyState,
            Long version) {
    }

    public record ConfigurationCheck(
            UUID connectorId,
            boolean ready,
            ConnectorHealth healthState,
            List<String> checks,
            List<String> blockingCodes,
            Instant checkedAt) {
    }

    public record LifecycleRequest(@NotNull Long version) {
    }

    public record Subject(
            UUID subjectId,
            UUID connectorId,
            long userId,
            ConsentState consentState,
            List<String> grantedScopes,
            Instant tokenExpiresAt,
            Instant lastSuccessfulSyncAt,
            String lastErrorCode) {
    }

    public record SyncRun(
            UUID runId,
            UUID connectorId,
            long userId,
            ResourceKind resourceKind,
            SyncMode syncMode,
            SyncRunState runState,
            Instant startedAt,
            Instant completedAt,
            int upsertCount,
            int deleteCount,
            int skipCount,
            int errorCount,
            boolean partialResult,
            Instant retryAfterAt,
            String safeErrorCode,
            String correlationId) {
    }

    public record Connection(
            UUID connectorId,
            String connectorKey,
            String displayName,
            ProviderType providerType,
            ConnectorLifecycle lifecycleState,
            ConnectorHealth healthState,
            ConsentState consentState,
            List<String> requestedScopes,
            List<String> grantedScopes,
            Instant lastSuccessfulSyncAt,
            String actionRequiredCode) {
    }

    public record AuthorizationStart(
            UUID transactionId,
            String authorizationUrl,
            Instant expiresAt) {
    }

    public record AuthorizationCallbackRequest(
            @NotBlank @Size(max = 4096) String code,
            @NotBlank @Size(max = 512) String state) {
    }

    public record SyncRequest(@NotNull ResourceKind resourceKind, boolean reset) {
    }

    public record ProductivityItem(
            UUID itemId,
            ResourceKind resourceKind,
            String title,
            String sourceUrl,
            Instant occurredAt,
            Instant endsAt,
            String importance,
            Boolean read,
            boolean cancelled,
            String classification) {
    }

    public record ItemPage(List<ProductivityItem> content, int page, int size, long totalElements) {
    }
}
