package com.dwp.services.notification.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRedisSignalCodecTest {

    private final NotificationRedisSignalCodec codec = new NotificationRedisSignalCodec(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTripsOnlyContentFreeIdentityVersionEnvelope() {
        UUID notificationId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        NotificationRealtimeEnvelope envelope = new NotificationRealtimeEnvelope(
                7,
                900018,
                "9007199254740993",
                "9007199254740993",
                List.of(notificationId),
                List.of(notificationId));

        String encoded = codec.encode(envelope);
        NotificationRealtimeEnvelope decoded = codec.decode(
                encoded.getBytes(StandardCharsets.UTF_8));

        assertThat(decoded).isEqualTo(envelope);
        assertThat(encoded).containsOnlyOnce("tenantId")
                .containsOnlyOnce("userId")
                .containsOnlyOnce("changeVersion")
                .containsOnlyOnce("counterVersion")
                .containsOnlyOnce("changedIds")
                .containsOnlyOnce("arrivalIds")
                .doesNotContain("title", "body", "preview", "actor", "action", "payload");
    }

    @Test
    void rejectsUnknownFieldsMalformedPayloadAndOversizedPayload() {
        assertThatThrownBy(() -> codec.decode((
                "{\"tenantId\":1,\"userId\":2,\"changeVersion\":\"1\","
                        + "\"counterVersion\":\"1\",\"changedIds\":[],"
                        + "\"arrivalIds\":[],\"title\":\"secret\"}")
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("not-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(new byte[8 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNumericVersionsToProtectJavaScriptPrecision() {
        String numeric = "{\"tenantId\":1,\"userId\":2,"
                + "\"changeVersion\":9007199254740993,\"counterVersion\":\"1\","
                + "\"changedIds\":[\"40000000-0000-0000-0000-000000000001\"],"
                + "\"arrivalIds\":[]}";

        assertThatThrownBy(() -> codec.decode(numeric.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsLegacyRefreshOnlySignalsDuringRollingDeployment() {
        String legacy = "{\"tenantId\":1,\"userId\":2,"
                + "\"changeVersion\":\"7\",\"counterVersion\":\"7\","
                + "\"changedIds\":[\"40000000-0000-0000-0000-000000000001\"]}";

        NotificationRealtimeEnvelope decoded = codec.decode(
                legacy.getBytes(StandardCharsets.UTF_8));

        assertThat(decoded.arrivalIds()).isEmpty();
        assertThat(decoded.changedIds()).hasSize(1);
    }
}
