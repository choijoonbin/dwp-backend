package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQuality;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.FourEyesGovernance;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseFindingSeverity;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQualityQuery;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseRisk;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTimeRange;
import com.dwp.services.notification.domain.NotificationNoiseQualityService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationNoiseQualityControllerTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NotificationNoiseQualityService service =
            mock(NotificationNoiseQualityService.class);
    private final NotificationNoiseQualityController controller =
            new NotificationNoiseQualityController(service);

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void exposesThePrivacyGuardedAdminSummary() {
        NoiseQuality summary = new NoiseQuality(
                false, List.of(), null, false, 10, null,
                null, null, null, null, List.of(), List.of(),
                new FourEyesGovernance("NOT_CONFIGURED", 0, 0, false, null),
                NoiseTimeRange.LAST_7_DAYS,
                Instant.parse("2026-09-09T00:00:00Z"),
                Instant.parse("2026-09-16T00:00:00Z"));
        when(service.summary(ACTOR, new NoiseQualityQuery(
                NoiseTimeRange.LAST_7_DAYS,
                "messaging",
                NoiseFindingSeverity.WARNING,
                NoiseRisk.HIGH_MUTE_RATE))).thenReturn(summary);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.noiseQuality(
                NoiseTimeRange.LAST_7_DAYS,
                "messaging",
                NoiseFindingSeverity.WARNING,
                NoiseRisk.HIGH_MUTE_RATE).data()).isSameAs(summary);
    }
}
