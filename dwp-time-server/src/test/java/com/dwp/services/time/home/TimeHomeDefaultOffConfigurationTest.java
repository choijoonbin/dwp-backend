package com.dwp.services.time.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TimeHomeDefaultOffConfigurationTest {

    @Test
    void producerAndEventPublicationRemainDefaultOff() throws Exception {
        String application = application();

        assertThat(application)
                .contains("enabled: ${DWP_HRIS_TIME_HOME_ENABLED:false}")
                .contains("event-publication-enabled: "
                        + "${DWP_HRIS_TIME_HOME_EVENT_PUBLICATION_ENABLED:false}")
                .contains("payload-reference-hmac-secret: "
                        + "${DWP_HRIS_TIME_HOME_PAYLOAD_REFERENCE_HMAC_SECRET:}");
    }

    @Test
    void enabledConfigurationRejectsMissingOrWeakHmacMaterial() {
        var configuration = new TimeHomeProducerConfiguration();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                configuration.timeHomePayloadReferenceCodec(""))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                configuration.timeHomePayloadReferenceCodec(
                        java.util.Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String application() throws IOException {
        Path direct = Path.of("dwp-time-server/src/main/resources/application.yml");
        Path path = Files.exists(direct) ? direct : Path.of("..").resolve(direct);
        return Files.readString(path);
    }
}
