package com.dwp.services.platform.home.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeProviderClientConfigurationTest {

    private final HomeProviderClientConfiguration configuration =
            new HomeProviderClientConfiguration();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HomeRuntimeProperties properties = new HomeRuntimeProperties(
            true, true, false, Duration.ofMillis(900), Duration.ofMillis(400),
            Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);

    @Test
    void selectsFailClosedFallbackOrSignedDwaionClientFromExplicitConfiguration() {
        assertThat(provider("")).isInstanceOf(InactiveDwaionWidgetProvider.class);
        assertThat(provider("0123456789abcdef0123456789abcdef"))
                .isInstanceOf(DwaionHomeWidgetProviderClient.class);
        assertThatThrownBy(() -> provider("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private WidgetProviderPort provider(String secret) {
        return configuration.dwaionHomeWidgetProvider(
                RestClient.builder(), mapper, CircuitBreakerRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(), properties, "http://127.0.0.1:1", secret,
                "platform-dwaion-home-v1");
    }
}
