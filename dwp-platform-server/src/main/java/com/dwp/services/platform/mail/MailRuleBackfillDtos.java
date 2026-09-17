package com.dwp.services.platform.mail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class MailRuleBackfillDtos {

    private MailRuleBackfillDtos() {
    }

    public record Request(
            @NotNull UUID requestId,
            @NotBlank
            @Pattern(regexp = "^[0-9a-f]{64}$")
            String previewFingerprint,
            @Size(max = 512)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$")
            String continuationToken) {

        public Request(UUID requestId, String previewFingerprint) {
            this(requestId, previewFingerprint, null);
        }
    }

    public record Preview(
            UUID accountId,
            String continuationToken,
            String nextContinuationToken,
            String previewFingerprint,
            int enabledRuleCount,
            int scannedCount,
            int matchedThreadCount,
            int plannedApplicationCount,
            boolean truncated,
            OffsetDateTime generatedAt) {

        public Preview(
                UUID accountId,
                String previewFingerprint,
                int enabledRuleCount,
                int scannedCount,
                int matchedThreadCount,
                int plannedApplicationCount,
                boolean truncated,
                OffsetDateTime generatedAt) {
            this(accountId, null, null, previewFingerprint, enabledRuleCount, scannedCount,
                    matchedThreadCount, plannedApplicationCount, truncated, generatedAt);
        }
    }

    public record Result(
            UUID executionId,
            UUID requestId,
            UUID accountId,
            String status,
            boolean replayed,
            int scannedCount,
            int matchedThreadCount,
            int applicationCount,
            int changedCount,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {

        Result asReplay() {
            return new Result(
                    executionId,
                    requestId,
                    accountId,
                    status,
                    true,
                    scannedCount,
                    matchedThreadCount,
                    applicationCount,
                    changedCount,
                    startedAt,
                    completedAt);
        }
    }
}
