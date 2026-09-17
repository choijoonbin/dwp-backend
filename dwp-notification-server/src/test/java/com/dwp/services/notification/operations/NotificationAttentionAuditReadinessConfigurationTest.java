package com.dwp.services.notification.operations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionAuditReadinessConfigurationTest {

    @Test
    void includesTheAuditRelayInTheReadinessProbe() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("application.yml")) {
            assertThat(stream).isNotNull();
            String configuration = new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration)
                    .contains("readiness:")
                    .contains("include: readinessState,notificationAttentionAuditRelayLifecycle");
        }
    }
}
