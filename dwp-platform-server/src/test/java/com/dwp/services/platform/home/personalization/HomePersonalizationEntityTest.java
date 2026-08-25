package com.dwp.services.platform.home.personalization;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class HomePersonalizationEntityTest {

    @Test
    void auditClockIsAnAbsoluteUtcTimestampIndependentOfTheJvmDefaultZone() {
        HomeView view = new HomeView();

        view.stampCreation();

        assertThat(view.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(view.getUpdatedAt()).isEqualTo(view.getCreatedAt());
    }
}
