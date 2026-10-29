package com.dwp.services.messaging.operations;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingRuntimeConfigurationInvariantTest {

    @Test
    void destructiveRetentionAndExternalProvidersDefaultFailClosed() throws IOException {
        String application = application();

        assertThat(application)
                .contains("enabled: ${DWP_MESSAGING_RETENTION_ENABLED:false}")
                .contains("mode: ${DWP_MESSAGING_RETENTION_MODE:DRY_RUN}")
                .contains("hold-authority-ready: ${DWP_MESSAGING_RETENTION_HOLD_AUTHORITY_READY:false}")
                .contains("downstream-lifecycle-ready: ${DWP_MESSAGING_RETENTION_DOWNSTREAM_LIFECYCLE_READY:false}")
                .contains("storage: ${DWP_MESSAGING_ATTACHMENT_STORAGE:local}")
                .contains("scanner: ${DWP_MESSAGING_ATTACHMENT_SCANNER:local}")
                .contains("disabled: ${OTEL_SDK_DISABLED:true}");
    }

    @Test
    void providerEvidenceTimeoutsAndProductionRedisTlsRemainEnvironmentBound()
            throws IOException {
        assertThat(application())
                .contains("maximum-age: ${DWP_MESSAGING_READINESS_MAXIMUM_AGE:PT3M}")
                .contains("evidence-maximum-age: ${DWP_MESSAGING_RETENTION_EVIDENCE_MAXIMUM_AGE:PT2H}")
                .contains("readiness-probe-timeout: ${DWP_MESSAGING_REDIS_READINESS_PROBE_TIMEOUT:PT1S}")
                .contains("enabled: ${DWP_MESSAGING_REDIS_SSL_ENABLED:false}");
    }

    @Test
    void everyDocumentedMessagingHealthGroupIsConfigured() throws IOException {
        assertThat(application())
                .contains("presence:\n          include: messagingPresenceHealth")
                .contains("retention:\n          include: messagingRetentionHealth")
                .contains("scheduled-delivery:\n          include: messagingScheduledDeliveryHealth")
                .contains("messaging-operations:")
                .contains("messaging-providers:\n          include: messagingProviderReadinessHealth");
    }

    private String application() throws IOException {
        return new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
