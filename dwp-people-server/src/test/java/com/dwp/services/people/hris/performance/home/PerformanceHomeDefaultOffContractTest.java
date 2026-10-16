package com.dwp.services.people.hris.performance.home;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.filter.OncePerRequestFilter;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceHomeDefaultOffContractTest {

    @Test
    void configurationKeepsFeatureAndPublicationOffAndSecretUnset() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains(
                "enabled: ${DWP_PERFORMANCE_HOME_PRODUCER_ENABLED:false}",
                "event-publication-enabled: "
                        + "${DWP_PERFORMANCE_HOME_EVENT_PUBLICATION_ENABLED:false}",
                "hmac-secret-base64: ${DWP_PERFORMANCE_HOME_HMAC_SECRET_BASE64:}");
        ConditionalOnProperty condition = PerformanceHomeController.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void exactWorkloadBoundaryIsImplementedAsAPreSecurityFilter() {
        assertThat(OncePerRequestFilter.class)
                .isAssignableFrom(PerformanceHomeController.class);
        assertThat(PerformanceHomeController.class.getAnnotation(
                org.springframework.web.bind.annotation.RestController.class)).isNull();
    }
}
