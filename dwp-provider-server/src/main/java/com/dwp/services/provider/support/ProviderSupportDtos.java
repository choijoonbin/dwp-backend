package com.dwp.services.provider.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Security-sensitive support contracts are deliberately separate from the
 * general provider control-plane DTO catalog. Browser projections never carry
 * an auth tenant identifier; only the service-to-service verified projection
 * contains the routing identity consumed by Gateway.
 */
public final class ProviderSupportDtos {

    private ProviderSupportDtos() {
    }

    public static AccessRequestLedgerItem accessRequestLedgerItem(
            ProviderSupportRequestView request,
            boolean requesterOwned) {
        return new AccessRequestLedgerItem(
                request.supportAccessRequestId(), request.tenantId(), request.tenantKey(),
                request.tenantName(), requesterOwned, request.requesterName(),
                request.lifecycleState(), request.accessMode(), request.justification(),
                request.scopes(), request.durationMinutes(), request.approvalReference(),
                request.customerApprovalRequired(), request.riskTier(), request.requestedAt(),
                request.decisionDueAt(), request.supportSessionId(), request.activatedAt(),
                request.completedAt(), request.postReviewState(), request.version());
    }

    public record BrowserSessionContext(
            UUID supportSessionId,
            UUID tenantId,
            String tenantKey,
            String tenantName,
            String environmentKey,
            String dataRegion,
            List<String> scopes,
            String accessMode,
            Instant expiresAt,
            long version) {
    }

    public record VerifiedSessionContext(
            UUID supportSessionId,
            UUID providerTenantId,
            Long authTenantId,
            String tenantKey,
            String tenantName,
            List<String> scopes,
            String accessMode,
            Instant expiresAt,
            long version) {
    }

    public record DisableActivationRequest(
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record AccessRequestLedgerItem(
            UUID supportAccessRequestId,
            UUID tenantId,
            String tenantKey,
            String tenantName,
            boolean requesterOwned,
            String requesterName,
            String lifecycleState,
            String accessMode,
            String justification,
            List<String> scopes,
            int durationMinutes,
            String approvalReference,
            boolean customerApprovalRequired,
            String riskTier,
            Instant requestedAt,
            Instant decisionDueAt,
            UUID supportSessionId,
            Instant activatedAt,
            Instant completedAt,
            String postReviewState,
            long version) {
    }

    public record SessionLedgerItem(
            UUID supportSessionId,
            UUID supportAccessRequestId,
            UUID tenantId,
            String tenantKey,
            String tenantName,
            boolean operatorOwned,
            String operatorName,
            String lifecycleState,
            List<String> scopes,
            String accessMode,
            String riskTier,
            Instant startedAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            long version) {
    }
}
