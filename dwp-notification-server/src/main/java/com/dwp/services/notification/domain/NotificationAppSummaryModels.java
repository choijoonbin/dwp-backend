package com.dwp.services.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Content-free notification counters for home and launcher surfaces. */
public final class NotificationAppSummaryModels {

    private static final Pattern STABLE_APP_KEY =
            Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private NotificationAppSummaryModels() {
    }

    public record AppNotificationCounter(
            String appKey,
            long totalUnread,
            long actionableUnread,
            long urgentUnread,
            Instant lastActivityAt) {

        public AppNotificationCounter {
            if (appKey == null || !STABLE_APP_KEY.matcher(appKey).matches()) {
                throw new IllegalArgumentException("A stable notification owner app key is required.");
            }
            if (totalUnread < 0 || actionableUnread < 0 || urgentUnread < 0) {
                throw new IllegalArgumentException("Notification app counters cannot be negative.");
            }
            if (actionableUnread > totalUnread || urgentUnread > totalUnread) {
                throw new IllegalArgumentException(
                        "Notification app counter subsets cannot exceed total unread.");
            }
            Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        }
    }

    public record AppNotificationSummary(
            boolean partial,
            List<String> unavailableSources,
            List<AppNotificationCounter> apps,
            String changeVersion,
            String counterVersion,
            Instant generatedAt) {

        public AppNotificationSummary {
            unavailableSources = List.copyOf(unavailableSources);
            apps = List.copyOf(apps);
            Objects.requireNonNull(changeVersion, "changeVersion");
            Objects.requireNonNull(counterVersion, "counterVersion");
            Objects.requireNonNull(generatedAt, "generatedAt");
            if (!partial && !unavailableSources.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unavailable sources require a partial app notification summary.");
            }
        }
    }
}
