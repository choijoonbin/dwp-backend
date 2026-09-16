package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.ApprovedHomeApplicationCatalog;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, content-free projection from Notification counters into the approved 18-app dock. */
final class AppDockBadgeProjection {

    private static final Pattern VERSION = Pattern.compile("^[0-9]{1,40}$");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "appKey", "totalUnread", "actionableUnread", "urgentUnread", "lastActivityAt");
    private static final Set<String> APPROVED_APP_KEYS = ApprovedHomeApplicationCatalog
            .applications().stream().map(ApprovedHomeApplicationCatalog.Application::appKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private AppDockBadgeProjection() {
    }

    static Projection notRequested() {
        return new Projection(HomeReadModelDtos.BadgeState.NOT_REQUESTED, Map.of(), null, false);
    }

    static Projection forbidden() {
        return new Projection(HomeReadModelDtos.BadgeState.FORBIDDEN, Map.of(), null, false);
    }

    static Projection from(HomeWidgetProviderContract.WidgetResult result) {
        if (result == null) return notRequested();
        if (result.state() == HomeWidgetProviderContract.State.FORBIDDEN) return forbidden();
        if (result.state() == HomeWidgetProviderContract.State.UNAVAILABLE
                || result.state() == HomeWidgetProviderContract.State.STALE) {
            return unavailable();
        }
        if (result.state() == HomeWidgetProviderContract.State.EMPTY) {
            String version = validVersion(result.source().resultVersion())
                    ? result.source().resultVersion() : "0";
            return new Projection(HomeReadModelDtos.BadgeState.AVAILABLE, Map.of(), version, true);
        }
        if (result.state() != HomeWidgetProviderContract.State.AVAILABLE
                && result.state() != HomeWidgetProviderContract.State.PARTIAL) {
            return unavailable();
        }
        try {
            String version = text(result.payload().get("counterVersion"));
            if (!validVersion(version)) return unavailable();
            Object rawItems = result.payload().get("items");
            if (!(rawItems instanceof List<?> items)
                    || items.size() > HomeWidgetProviderContract.MAX_ITEM_LIMIT) {
                return unavailable();
            }
            Map<String, HomeReadModelDtos.Badge> badges = new LinkedHashMap<>();
            for (Object rawItem : items) {
                if (!(rawItem instanceof Map<?, ?> item) || !exactFields(item)) {
                    return unavailable();
                }
                String appKey = text(item.get("appKey"));
                int total = counter(item.get("totalUnread"));
                int actionable = counter(item.get("actionableUnread"));
                int urgent = counter(item.get("urgentUnread"));
                String activityAt = text(item.get("lastActivityAt"));
                if (appKey == null || total < 0 || actionable < 0 || urgent < 0
                        || actionable > total || urgent > total || !timestamp(activityAt)) {
                    return unavailable();
                }
                if (!APPROVED_APP_KEYS.contains(appKey)) continue;
                if (badges.putIfAbsent(
                        appKey, new HomeReadModelDtos.Badge(total, urgent, version)) != null) {
                    return unavailable();
                }
            }
            boolean complete = result.state() == HomeWidgetProviderContract.State.AVAILABLE;
            return new Projection(
                    complete ? HomeReadModelDtos.BadgeState.AVAILABLE
                            : HomeReadModelDtos.BadgeState.UNAVAILABLE,
                    Map.copyOf(badges), version, complete);
        } catch (RuntimeException ignored) {
            return unavailable();
        }
    }

    private static Projection unavailable() {
        return new Projection(HomeReadModelDtos.BadgeState.UNAVAILABLE, Map.of(), null, false);
    }

    private static boolean exactFields(Map<?, ?> item) {
        return item.size() == ITEM_FIELDS.size()
                && item.keySet().stream().allMatch(ITEM_FIELDS::contains);
    }

    private static int counter(Object value) {
        if (!(value instanceof Number number)) return -1;
        long counter = number.longValue();
        if (counter < 0 || counter > Integer.MAX_VALUE
                || number.doubleValue() != (double) counter) return -1;
        return (int) counter;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() && text.length() <= 160
                ? text : null;
    }

    private static boolean validVersion(String value) {
        return value != null && VERSION.matcher(value).matches();
    }

    private static boolean timestamp(String value) {
        if (value == null) return false;
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    record Projection(
            HomeReadModelDtos.BadgeState missingState,
            Map<String, HomeReadModelDtos.Badge> badges,
            String version,
            boolean complete) {

        HomeReadModelDtos.BadgeState state(String appKey) {
            return badges.containsKey(appKey)
                    ? HomeReadModelDtos.BadgeState.AVAILABLE : missingState;
        }

        HomeReadModelDtos.Badge badge(String appKey) {
            HomeReadModelDtos.Badge value = badges.get(appKey);
            if (value != null) return value;
            return complete && version != null
                    ? new HomeReadModelDtos.Badge(0, 0, version) : null;
        }
    }
}
