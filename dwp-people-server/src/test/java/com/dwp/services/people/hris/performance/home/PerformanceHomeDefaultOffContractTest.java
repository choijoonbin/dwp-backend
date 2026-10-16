package com.dwp.services.people.hris.performance.home;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
    void exposesOnlyTheExactInternalGetWithoutBodyOrTenantParameter() {
        var method = Arrays.stream(PerformanceHomeController.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(GetMapping.class))
                .findFirst().orElseThrow();
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertThat(mapping.value()).containsExactly(
                "/internal/hris-performance/v1/home-contributions/{payloadRef}");
        assertThat(method.getParameterTypes()).containsExactly(
                String.class, jakarta.servlet.http.HttpServletRequest.class);
        assertThat(Arrays.stream(method.getParameters())
                .noneMatch(parameter -> parameter.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RequestBody.class))).isTrue();
    }
}
