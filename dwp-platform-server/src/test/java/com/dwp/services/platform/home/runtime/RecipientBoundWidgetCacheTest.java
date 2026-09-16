package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientBoundWidgetCacheTest {

    private final RecipientBoundWidgetCache cache = new RecipientBoundWidgetCache(properties());

    @Test
    void recipientAuthorityLocaleModeAndDeviceArePartOfTheCacheIdentity() {
        RecipientBoundWidgetCache.Key original = key(1L, 2L, "authority-a", "ko-KR",
                "CLASSIC", "DESKTOP_STANDARD", Set.of("core.work.focus"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.BatchResponse response = new HomeWidgetProviderContract.BatchResponse(
                1, 1L, 2L, "revision-1", List.of());
        cache.put(original, response, now.plusSeconds(20), now.plusMinutes(2));

        assertThat(cache.fresh(original)).contains(response);
        assertThat(cache.fresh(key(1L, 3L, "authority-a", "ko-KR",
                "CLASSIC", "DESKTOP_STANDARD", Set.of("core.work.focus")))).isEmpty();
        assertThat(cache.fresh(key(1L, 2L, "authority-b", "ko-KR",
                "CLASSIC", "DESKTOP_STANDARD", Set.of("core.work.focus")))).isEmpty();
        assertThat(cache.fresh(key(1L, 2L, "authority-a", "en-US",
                "CLASSIC", "DESKTOP_STANDARD", Set.of("core.work.focus")))).isEmpty();
        assertThat(cache.fresh(key(1L, 2L, "authority-a", "ko-KR",
                "FLOW_V1", "MOBILE_STANDARD", Set.of("core.work.focus")))).isEmpty();
    }

    @Test
    void definitionInvalidationUsesExactSetMembership() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.BatchResponse response = new HomeWidgetProviderContract.BatchResponse(
                1, 1L, 2L, "revision-1", List.of());
        RecipientBoundWidgetCache.Key work = key(1L, 2L, "authority-a", "ko-KR",
                "CLASSIC", "DESKTOP_STANDARD", Set.of("APP.WORK"));
        RecipientBoundWidgetCache.Key workflow = key(1L, 2L, "authority-a", "ko-KR",
                "CLASSIC", "DESKTOP_STANDARD", Set.of("APP.WORKFLOW"));
        cache.put(work, response, now.plusSeconds(20), now.plusMinutes(2));
        cache.put(workflow, response, now.plusSeconds(20), now.plusMinutes(2));

        cache.invalidateDefinition("APP.WORK");

        assertThat(cache.fresh(work)).isEmpty();
        assertThat(cache.fresh(workflow)).contains(response);
    }

    @Test
    void expiredAuthorityLeaseIsNeverInserted() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RecipientBoundWidgetCache.Key key = key(
                1L, 2L, "authority-a", "ko-KR", "CLASSIC", "DESKTOP_STANDARD",
                Set.of("core.work.focus"));
        HomeWidgetProviderContract.BatchResponse response =
                new HomeWidgetProviderContract.BatchResponse(
                        1, 1L, 2L, "revision-1", List.of());

        cache.put(key, response, now.plusSeconds(20), now.minusNanos(1));

        assertThat(cache.size()).isZero();
        assertThat(cache.fresh(key)).isEmpty();
    }

    private RecipientBoundWidgetCache.Key key(
            long tenant, long user, String authority, String locale, String mode,
            String device, Set<String> definitions) {
        return new RecipientBoundWidgetCache.Key(
                tenant, user, authority, "revision-1", locale, "Asia/Seoul",
                mode, device, "view-1", "catalog-1", "policy-1", "safety-1",
                "platform", definitions, "request-1");
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                true, false, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }
}
