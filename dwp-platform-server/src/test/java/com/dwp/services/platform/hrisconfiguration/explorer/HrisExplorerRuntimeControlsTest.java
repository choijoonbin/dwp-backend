package com.dwp.services.platform.hrisconfiguration.explorer;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisExplorerRuntimeControlsTest {

    @Test
    void providerBoundsAreClosedAndSmall() {
        HrisExplorerRuntimeControls controls = new HrisExplorerRuntimeControls(
                Duration.ofMillis(500), Duration.ofMinutes(5), 5);
        assertThat(controls.providerTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(controls.providerFreshness()).isEqualTo(Duration.ofMinutes(5));
        assertThat(controls.maxProviderConcurrency()).isEqualTo(5);

        assertThatThrownBy(() -> new HrisExplorerRuntimeControls(
                Duration.ofSeconds(6), Duration.ofMinutes(5), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HrisExplorerRuntimeControls(
                Duration.ofMillis(500), Duration.ofHours(2), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HrisExplorerRuntimeControls(
                Duration.ofMillis(500), Duration.ofMinutes(5), 6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
