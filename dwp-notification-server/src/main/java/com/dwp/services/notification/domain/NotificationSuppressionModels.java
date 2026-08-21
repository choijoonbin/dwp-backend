package com.dwp.services.notification.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationSuppressionModels {

    private NotificationSuppressionModels() {
    }

    public record SuppressionCommand(
            @NotBlank @Size(max = 20) String scopeType,
            @NotBlank @Size(max = 200) String scopeKey,
            @NotBlank @Size(max = 30) String channel,
            Instant startsAt,
            @NotNull Instant expiresAt,
            boolean criticalBypass,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record SuppressionRevokeCommand(
            @NotBlank String expectedVersion,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record SuppressionPreview(
            String scopeType,
            String scopeKey,
            String channel,
            Instant startsAt,
            Instant expiresAt,
            boolean criticalBypass,
            long affectedTypeCount,
            long observedNotifications7Days,
            long criticalBypassCandidates7Days,
            long overlappingSuppressionCount,
            List<String> matchedTypeKeys,
            List<String> riskFlags,
            Instant generatedAt) {
    }

    public record Suppression(
            UUID suppressionId,
            String scopeType,
            String scopeKey,
            String channel,
            Instant startsAt,
            Instant expiresAt,
            boolean criticalBypass,
            String reason,
            long createdBy,
            Instant revokedAt,
            Long revokedBy,
            String revokeReason,
            String version,
            Instant createdAt,
            Instant updatedAt) {

        public String state(Instant now) {
            if (revokedAt != null) return "REVOKED";
            if (!expiresAt.isAfter(now)) return "EXPIRED";
            if (startsAt.isAfter(now)) return "SCHEDULED";
            return "ACTIVE";
        }
    }

    public record SuppressionPage(
            List<Suppression> items,
            Instant generatedAt) {
    }
}
