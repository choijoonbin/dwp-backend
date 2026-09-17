package com.dwp.services.platform.personalsettings;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class PersonalSettingsDtos {

    private PersonalSettingsDtos() {
    }

    public record Favorite(
            String settingKey,
            boolean favorite,
            long version,
            LocalDateTime updatedAt) {
    }

    public record UpdateFavoriteRequest(
            @NotNull Boolean favorite,
            @NotNull @Min(0) Long version) {
    }

    public record Activity(
            UUID activityId,
            String settingKey,
            String activityType,
            List<String> changedFields,
            LocalDateTime occurredAt) {
    }

    public record RecordViewRequest(
            @NotBlank @Size(max = 64) String settingKey) {
    }

    public record Workspace(
            List<Favorite> favorites,
            List<Activity> recentActivity) {
    }

    public record Consent(
            UUID consentId,
            String purposeKey,
            String consentState,
            String noticeVersion,
            String source,
            LocalDateTime occurredAt) {
    }

    public record ConsentLedger(
            Consent currentProductAnalytics,
            List<Consent> history) {
    }

    public record UpdateConsentRequest(
            @NotNull Boolean granted,
            @NotBlank @Size(max = 64) String noticeVersion) {
    }

    public record CreatePrivacyRequest(
            @NotBlank
            @Pattern(regexp = "DATA_EXPORT|ACCOUNT_DELETION")
            String requestType,
            @NotBlank @Size(max = 64) String requestedScope,
            @Size(max = 1000) String reason,
            boolean accountDeletionAcknowledged) {

        @AssertTrue(message = "Account deletion requests require an explicit acknowledgement.")
        public boolean isAcknowledgementValid() {
            return !"ACCOUNT_DELETION".equals(requestType) || accountDeletionAcknowledged;
        }
    }

    public record PrivacyRequest(
            UUID requestId,
            String requestType,
            String requestState,
            String requestedScope,
            String reason,
            boolean fulfillmentAvailable,
            String fulfillmentBoundary,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }
}
