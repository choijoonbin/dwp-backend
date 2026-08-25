package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.DecimalVersionStringDeserializer;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NotificationModels {

    private NotificationModels() {
    }

    public record NotificationSource(
            String appKey,
            String appName,
            String iconKey,
            String accent) {
    }

    public record NotificationReason(
            String kind,
            String label,
            String detail) {
    }

    public record NotificationAction(
            String actionKey,
            String label,
            String href,
            boolean enabled,
            String disabledReason,
            boolean primary) {
    }

    public record InboxItem(
            UUID notificationId,
            String threadKey,
            long threadCount,
            NotificationSource source,
            String typeKey,
            String title,
            String preview,
            String actorLabel,
            String priority,
            NotificationReason reason,
            Instant receivedAt,
            Instant lastActivityAt,
            Instant dueAt,
            Instant readAt,
            Instant savedAt,
            Instant completedAt,
            Instant snoozedUntil,
            boolean actionable,
            boolean sensitive,
            List<NotificationAction> actions,
            String version) {
    }

    public record TimelineEntry(
            String entryId,
            String title,
            String detail,
            Instant occurredAt,
            String actorLabel) {
    }

    public record Detail(
            InboxItem item,
            String reasonExplanation,
            Instant absoluteOccurredAt,
            String targetState,
            String targetStateReason,
            List<TimelineEntry> timeline) {
    }

    public record TargetResolution(
            UUID notificationId,
            String targetState,
            NotificationAction action) {
    }

    public record Summary(
            boolean partial,
            List<String> unavailableSources,
            String message,
            long actionableUnread,
            long totalUnread,
            Map<String, Long> viewCounts,
            String changeVersion,
            String counterVersion,
            Instant generatedAt) {
    }

    public record InboxPage(
            boolean partial,
            List<String> unavailableSources,
            String message,
            List<InboxItem> items,
            String nextCursor,
            boolean hasMore,
            Long approximateTotal,
            String changeVersion) {
    }

    public record SyncResponse(
            String changeVersion,
            String counterVersion,
            List<UUID> changedIds,
            List<UUID> deletedIds,
            boolean hasMore,
            Summary summary) {
    }

    public record Capabilities(
            List<String> enabledChannels,
            List<String> unavailableChannels,
            String canonicalStore,
            String realtimeTransport,
            String externalDeliveryState,
            Instant generatedAt) {
    }

    public record VersionRequest(
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion) {
    }

    public record SnoozeRequest(
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion,
            @NotNull @Future Instant snoozedUntil) {
    }

    public record ActionResult(
            InboxItem item,
            String changeVersion,
            Summary summary) {
    }

    public record BulkActionRequest(
            @NotEmpty @Size(max = 100) List<@NotNull UUID> notificationIds,
            @NotBlank @Pattern(regexp = "READ|UNREAD|SAVE|UNSAVE|COMPLETE|RESTORE|SNOOZE") String action,
            Instant snoozedUntil) {
    }

    public record BulkItemResult(
            UUID notificationId,
            String outcome,
            InboxItem item,
            String message) {
    }

    public record BulkResult(
            List<BulkItemResult> results,
            String changeVersion,
            Summary summary,
            UUID undoToken,
            Instant undoExpiresAt) {
    }

    public record QuietHours(
            boolean enabled,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String start,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String end,
            @NotBlank @Size(max = 80) String timeZone,
            @NotNull @Size(max = 7) List<@Min(1) @Max(7) Integer> days,
            boolean allowUrgentBypass) {
    }

    public record Digest(
            @NotBlank @Pattern(regexp = "OFF|DAILY|WEEKLY") String mode,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String deliveryTime,
            @Min(1) @Max(7) Integer dayOfWeek) {
    }

    public record Presentation(
            @NotBlank @Pattern(regexp = "SMART|HIGH_PRIORITY_ONLY|OFF") String bannerMode,
            @NotBlank @Pattern(regexp = "FULL|TITLE_ONLY|HIDDEN") String previewMode) {
    }

    public record DeliveryProfile(
            @NotNull Map<@Pattern(regexp = "IN_APP|EMAIL|WEB_PUSH|MOBILE_PUSH|TEAMS|SLACK") String, Boolean> channels,
            @NotNull @Valid QuietHours quietHours,
            @NotNull @Valid Digest digest,
            @NotNull @Valid Presentation presentation,
            String version,
            Instant updatedAt) {
    }

    public record DeliveryProfileUpdate(
            @NotNull Map<@Pattern(regexp = "IN_APP|EMAIL|WEB_PUSH|MOBILE_PUSH|TEAMS|SLACK") String, Boolean> channels,
            @NotNull @Valid QuietHours quietHours,
            @NotNull @Valid Digest digest,
            @NotNull @Valid Presentation presentation,
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String version,
            Instant updatedAt) {
    }

    public record SubscriptionRuleUpdate(
            @NotBlank @Size(max = 100) String appKey,
            @NotBlank @Size(max = 160) String typeKey,
            @NotBlank @Pattern(regexp = "IMMEDIATE|DAILY_DIGEST|WEEKLY_DIGEST|MUTED") String mode,
            @JsonSetter(contentNulls = Nulls.FAIL)
            @NotNull Map<@Pattern(regexp = "IN_APP|EMAIL|WEB_PUSH|MOBILE_PUSH|TEAMS|SLACK") String, @NotNull Boolean> channels,
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion) {

        public SubscriptionRuleUpdate {
            if (channels != null && channels.values().stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException(
                        "Notification channel overrides must be boolean values.");
            }
        }
    }

    public record SubscriptionRule(
            String appKey,
            String typeKey,
            String mode,
            Map<String, Boolean> channels,
            UUID ruleId,
            String version,
            Instant updatedAt) {
    }

    public record ManagedValue<T>(
            T effectiveValue,
            String source,
            boolean managed,
            boolean exceptionAllowed,
            String ownerLabel) {
    }

    public record NotificationTypeSetting(
            String typeKey,
            String typeName,
            String description,
            ManagedValue<String> mode,
            Map<String, ManagedValue<Boolean>> channels,
            boolean mandatory,
            boolean quietHoursBypass,
            UUID ruleId,
            String ruleVersion) {
    }

    public record NotificationAppSetting(
            String appKey,
            String appName,
            String iconKey,
            List<NotificationTypeSetting> types) {
    }

    public record EffectiveSettings(
            boolean partial,
            List<String> unavailableSources,
            String message,
            Map<String, ManagedValue<Boolean>> globalChannels,
            List<NotificationAppSetting> apps,
            Instant generatedAt) {
    }

    public record AdminMetric(
            String key,
            String label,
            double value,
            String unit,
            Double baseline,
            String state) {
    }

    public record AdminTrendPoint(
            String bucket,
            long created,
            long actionable,
            long failed,
            long muted) {
    }

    public record OperationalFinding(
            String findingId,
            String category,
            String severity,
            String title,
            String detail,
            long count,
            String ownerLabel,
            Instant detectedAt,
            String href) {
    }

    public record AdminOverview(
            boolean partial,
            List<String> unavailableSources,
            String message,
            Instant generatedAt,
            List<AdminMetric> metrics,
            List<AdminTrendPoint> trend,
            List<OperationalFinding> findings) {
    }

    public record TypeContract(
            UUID contractId,
            String typeKey,
            String displayName,
            String description,
            String appKey,
            String appName,
            String ownerLabel,
            String sourceEventType,
            String priority,
            List<String> channels,
            boolean mandatory,
            String state,
            String contractHealth,
            long volume24Hours,
            int schemaVersion,
            String version,
            Instant updatedAt) {
    }

    public record TypeContractPage(
            boolean partial,
            List<String> unavailableSources,
            String message,
            List<TypeContract> items,
            String nextCursor,
            boolean hasMore) {
    }

    public record DeliveryLane(
            String lane,
            long queued,
            long oldestAgeSeconds,
            double throughputPerMinute,
            double failureRatePercent,
            String state) {
    }

    public record ProviderHealth(
            String providerKey,
            String displayName,
            String channel,
            String state,
            double successRatePercent,
            long p95LatencyMs,
            String circuitState,
            Instant lastCheckedAt) {
    }

    public record DeliveryOperations(
            boolean partial,
            List<String> unavailableSources,
            String message,
            Instant generatedAt,
            List<DeliveryLane> lanes,
            List<ProviderHealth> providers,
            long retryQueue,
            long deadLetterQueue,
            long unknownOutcomes,
            List<OperationalFinding> findings) {
    }

    public record PolicyChannelRule(
            @NotBlank
            @Pattern(regexp = "IN_APP|EMAIL|WEB_PUSH|MOBILE_PUSH|TEAMS|SLACK")
            String channel,
            boolean enabled,
            @NotBlank @Pattern(regexp = "IMMEDIATE|DIGEST|MUTED") String defaultMode,
            boolean userOverridable,
            @Min(1) @Max(10000) Integer maxPerWindow) {
    }

    public record TenantPolicyChangeRequest(
            @NotBlank @Pattern(regexp = "APP|TYPE") String scopeType,
            @NotBlank @Size(max = 200) String scopeKey,
            boolean mandatory,
            boolean quietHoursBypass,
            @NotBlank @Pattern(regexp = "IMMEDIATE|DAILY|WEEKLY") String digestMode,
            @NotEmpty @Size(max = 6) List<@NotNull @Valid PolicyChannelRule> channels,
            @NotBlank @Size(min = 10, max = 500) String changeReason,
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion) {
    }

    public record TenantPolicy(
            UUID policyId,
            String scopeType,
            String scopeKey,
            String scopeLabel,
            String source,
            String state,
            boolean mandatory,
            boolean quietHoursBypass,
            String digestMode,
            List<PolicyChannelRule> channels,
            String changeReason,
            Long createdBy,
            Long approvedBy,
            Instant approvedAt,
            String version,
            Instant createdAt) {
    }

    public record TenantPolicyPage(
            List<TenantPolicy> effectivePolicies,
            List<TenantPolicy> drafts,
            Instant generatedAt) {
    }

    public record TenantPolicyPreview(
            TenantPolicy currentPolicy,
            TenantPolicy proposedPolicy,
            long affectedTypeCount,
            long observedRecipients30Days,
            List<PolicyRuntimeChannelPreview> runtimeChannels,
            List<String> riskFlags) {
    }

    public record PolicyRuntimeChannelPreview(
            String channel,
            boolean enabled,
            String effectiveMode,
            boolean managed,
            boolean userOverridable,
            boolean defaultDeliveryAdmitted) {
    }

    public record PolicyPublishRequest(
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion,
            @NotBlank @Size(min = 10, max = 500) String approvalReason) {
    }

    public record DirectMaterializationRequest(
            @NotNull UUID sourceEventId,
            @NotBlank @Size(max = 200) String sourceEventType,
            @Min(1) int sourceSchemaVersion,
            @NotBlank @Size(max = 160) String typeKey,
            @NotEmpty @Size(max = 100) List<@Min(1) Long> recipientUserIds,
            @Size(max = 200) String threadKey,
            @Size(max = 35) String locale,
            @Size(max = 200) String reasonCode,
            @Size(max = 300) String actorReference,
            @Size(max = 300) String subjectReference,
            @Size(max = 300) String targetReference,
            Instant occurredAt,
            Instant dueAt,
            boolean actionRequired,
            @NotNull Map<String, Object> variables) {
    }

    public record MaterializationResult(
            UUID intentId,
            UUID notificationId,
            int recipientCount,
            boolean duplicate,
            String highestChangeVersion) {
    }

    public record ChangeSignal(long tenantId, long userId, long changeVersion, UUID notificationId) {
    }

    public enum InboxView {
        PRIORITY,
        ALL,
        MENTIONS,
        SAVED,
        SNOOZED,
        DONE;

        public static InboxView from(String value) {
            if (value == null || value.isBlank()) return PRIORITY;
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported notification view.");
            }
        }
    }
}
