package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HomeRuntimeCacheSecurityTest {

    @Test
    void authorityRevisionChangeCannotReuseAnOtherwiseIdenticalRecipientEntry() {
        RecipientBoundWidgetCache cache = new RecipientBoundWidgetCache(properties());
        RecipientBoundWidgetCache.Key first = key("fingerprint-a", "authority-7");
        RecipientBoundWidgetCache.Key revised = key("fingerprint-a", "authority-8");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeWidgetProviderContract.BatchResponse response = response("authority-7");
        cache.put(first, response, now.plusSeconds(30), now.plusMinutes(2));

        assertThat(cache.fresh(first)).contains(response);
        assertThat(cache.fresh(revised)).isEmpty();
        assertThat(cache.stale(revised)).isEmpty();
    }

    @Test
    void expiredAuthorityEvidenceCannotPopulateFreshOrStaleCache() {
        RecipientBoundWidgetCache cache = new RecipientBoundWidgetCache(properties());
        RecipientBoundWidgetCache.Key key = key("fingerprint-a", "authority-7");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        cache.put(key, response("authority-7"), now.plusSeconds(30), now.minusSeconds(1));

        assertThat(cache.fresh(key)).isEmpty();
        assertThat(cache.stale(key)).isEmpty();
    }

    private RecipientBoundWidgetCache.Key key(String fingerprint, String revision) {
        return new RecipientBoundWidgetCache.Key(
                71L, 82L, fingerprint, revision, "ko-KR", "Asia/Seoul",
                "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1",
                "policy-1", "safety-1", "platform",
                Set.of("core.work.focus"), "request-1");
    }

    private HomeWidgetProviderContract.BatchResponse response(String revision) {
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                71L, 82L, revision, List.of());
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                true, false, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }
}
