package com.dwp.core.autoconfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DwpHttpClientPropertiesTest {

    @Test
    void suppliesBoundedEnterpriseDefaults() {
        var properties = new DwpHttpClientProperties(null, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsUnboundedTimeouts() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DwpHttpClientProperties(Duration.ofSeconds(31), Duration.ofSeconds(5)));
    }
}
