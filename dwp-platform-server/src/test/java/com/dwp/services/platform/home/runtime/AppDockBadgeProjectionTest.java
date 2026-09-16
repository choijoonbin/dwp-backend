package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AppDockBadgeProjectionTest {

    @Test
    void completeSnapshotMapsKnownCountersAndTreatsMissingApprovedAppsAsZero() {
        var projection = AppDockBadgeProjection.from(result(
                HomeWidgetProviderContract.State.AVAILABLE,
                Map.of(
                        "counterVersion", "42",
                        "items", List.of(item("dwp-work", 7, 3, 2))),
                List.of()));

        assertThat(projection.state("dwp-work"))
                .isEqualTo(HomeReadModelDtos.BadgeState.AVAILABLE);
        assertThat(projection.badge("dwp-work"))
                .isEqualTo(new HomeReadModelDtos.Badge(7, 2, "42"));
        assertThat(projection.state("dwp-calendar"))
                .isEqualTo(HomeReadModelDtos.BadgeState.AVAILABLE);
        assertThat(projection.badge("dwp-calendar"))
                .isEqualTo(new HomeReadModelDtos.Badge(0, 0, "42"));
    }

    @Test
    void partialSnapshotKeepsPresentCountersAndMarksUnknownCoverageUnavailable() {
        var projection = AppDockBadgeProjection.from(result(
                HomeWidgetProviderContract.State.PARTIAL,
                Map.of(
                        "counterVersion", "43",
                        "items", List.of(item("dwp-work", 4, 1, 1))),
                List.of("NOTIFICATION_SOURCE_UNAVAILABLE")));

        assertThat(projection.state("dwp-work"))
                .isEqualTo(HomeReadModelDtos.BadgeState.AVAILABLE);
        assertThat(projection.state("dwp-calendar"))
                .isEqualTo(HomeReadModelDtos.BadgeState.UNAVAILABLE);
        assertThat(projection.badge("dwp-calendar")).isNull();
    }

    @Test
    void malformedOrDuplicateCountersFailClosedWithoutLeakingAValue() {
        Map<String, Object> duplicate = item("dwp-work", 4, 1, 1);
        var projection = AppDockBadgeProjection.from(result(
                HomeWidgetProviderContract.State.AVAILABLE,
                Map.of("counterVersion", "44", "items", List.of(duplicate, duplicate)),
                List.of()));

        assertThat(projection.state("dwp-work"))
                .isEqualTo(HomeReadModelDtos.BadgeState.UNAVAILABLE);
        assertThat(projection.badge("dwp-work")).isNull();
    }

    @Test
    void forbiddenSourceIsDistinctFromUnavailableAndNotRequested() {
        assertThat(AppDockBadgeProjection.from(result(
                HomeWidgetProviderContract.State.FORBIDDEN, Map.of(), List.of()))
                .state("dwp-work")).isEqualTo(HomeReadModelDtos.BadgeState.FORBIDDEN);
        assertThat(AppDockBadgeProjection.from(result(
                HomeWidgetProviderContract.State.UNAVAILABLE, Map.of(), List.of()))
                .state("dwp-work")).isEqualTo(HomeReadModelDtos.BadgeState.UNAVAILABLE);
        assertThat(AppDockBadgeProjection.notRequested().state("dwp-work"))
                .isEqualTo(HomeReadModelDtos.BadgeState.NOT_REQUESTED);
    }

    private Map<String, Object> item(
            String appKey, int total, int actionable, int urgent) {
        return Map.of(
                "appKey", appKey,
                "totalUnread", total,
                "actionableUnread", actionable,
                "urgentUnread", urgent,
                "lastActivityAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    private HomeWidgetProviderContract.WidgetResult result(
            HomeWidgetProviderContract.State state,
            Map<String, Object> payload,
            List<String> redactions) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeWidgetProviderContract.WidgetResult(
                UUID.randomUUID(), "notification.app-badges",
                "a".repeat(64), "binding", state,
                new HomeWidgetProviderContract.SourceState(
                        "NOTIFICATION_APP_SUMMARY", now, now.plusSeconds(30),
                        state == HomeWidgetProviderContract.State.UNAVAILABLE ? null : now,
                        state == HomeWidgetProviderContract.State.AVAILABLE ? null : "SOURCE_STATE",
                        state == HomeWidgetProviderContract.State.UNAVAILABLE, "44"),
                payload, List.of(), redactions);
    }
}
