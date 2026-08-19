package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationModels.Summary;
import com.dwp.services.notification.domain.NotificationModels.Capabilities;
import com.dwp.services.notification.domain.NotificationModels.SubscriptionRuleUpdate;
import com.dwp.services.notification.domain.NotificationModels.VersionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationApiVersionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesVersionsBeyondJavaScriptSafeIntegerAsDecimalStrings() {
        String version = "9007199254740993";
        Summary summary = new Summary(
                false,
                List.of(),
                null,
                2,
                4,
                Map.of(
                        "PRIORITY", 2L,
                        "ALL", 4L,
                        "MENTIONS", 0L,
                        "SAVED", 0L,
                        "SNOOZED", 0L,
                        "DONE", 0L),
                version,
                version,
                Instant.parse("2026-08-19T00:00:00Z"));

        JsonNode json = objectMapper.valueToTree(summary);

        assertThat(json.path("changeVersion").isTextual()).isTrue();
        assertThat(json.path("counterVersion").isTextual()).isTrue();
        assertThat(json.path("changeVersion").textValue()).isEqualTo(version);
        assertThat(NotificationVersionCodec.nonNegative(version, "version"))
                .isEqualTo(9_007_199_254_740_993L);
    }

    @Test
    void mutationExpectedVersionRejectsJsonNumbers() throws Exception {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"expectedVersion\":9007199254740993}", VersionRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThat(objectMapper.readValue(
                "{\"expectedVersion\":\"9007199254740993\"}", VersionRequest.class)
                .expectedVersion()).isEqualTo("9007199254740993");
    }

    @Test
    void exposesOnlyImplementedDeliveryChannels() {
        Capabilities capabilities = new Capabilities(
                List.of("IN_APP"),
                List.of("EMAIL", "WEB_PUSH", "MOBILE_PUSH", "TEAMS", "SLACK"),
                "POSTGRESQL",
                "SSE_HINT_WITH_DURABLE_SYNC",
                "DISABLED",
                Instant.parse("2026-08-19T00:00:00Z"));

        JsonNode json = objectMapper.valueToTree(capabilities);

        assertThat(json.path("enabledChannels").size()).isEqualTo(1);
        assertThat(json.path("enabledChannels").get(0).textValue()).isEqualTo("IN_APP");
        assertThat(json.path("externalDeliveryState").textValue()).isEqualTo("DISABLED");
    }

    @Test
    void rejectsNullSubscriptionChannelOverridesAtTheApiBoundary() {
        assertThatThrownBy(() -> objectMapper.readValue(
                """
                {
                  "appKey": "dwp-approval-server",
                  "typeKey": "APPROVAL.ACTION_REQUIRED",
                  "mode": "MUTED",
                  "channels": {"IN_APP": null}
                }
                """,
                SubscriptionRuleUpdate.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }
}
