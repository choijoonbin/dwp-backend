package com.dwp.platform.contract.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeWidgetProviderRequestCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodesPinnedBoundedBatch() throws Exception {
        var request = HomeWidgetProviderRequestCodec.decode(
                objectMapper.readTree(batch("{}")), objectMapper);
        assertThat(request.widgets()).hasSize(1);
        assertThat(request.widgets().getFirst().definitionKey())
                .isEqualTo("meetings.next-prep");
    }

    @Test
    void rejectsUnknownRecipientSpoofAndMultibyteOversize() throws Exception {
        assertReason(batch("{}", ",\"recipientId\":99"), "HOME_PROVIDER_FIELD_REJECTED");
        assertReason(batch("{\"authorityRevision\":\"spoof\"}"),
                "HOME_PROVIDER_CONFIGURATION_AUTHORITY_REJECTED");
        String multibyte = "한".repeat(23_000);
        assertReason(batch("{\"label\":\"" + multibyte + "\"}"),
                "HOME_PROVIDER_BATCH_INVALID");
    }

    @Test
    void canonicalResultVersionDoesNotDependOnMapInsertionOrder() {
        var request = new HomeWidgetProviderContract.WidgetRequest(
                java.util.UUID.randomUUID(), "meetings.next-prep", "1.0.0",
                "a".repeat(64), "binding-1", Map.of(), 3);
        var first = HomeWidgetProviderResponses.available(
                request, "MEETING_HOME", new java.util.LinkedHashMap<>(Map.of("a", 1, "b", 2)),
                "/meetings/home");
        var reversed = new java.util.LinkedHashMap<String, Object>();
        reversed.put("b", 2);
        reversed.put("a", 1);
        var second = HomeWidgetProviderResponses.available(
                request, "MEETING_HOME", reversed, "/meetings/home");
        assertThat(first.source().resultVersion()).isEqualTo(second.source().resultVersion());
    }

    private void assertReason(String json, String reason) throws Exception {
        JsonNode raw = objectMapper.readTree(json);
        assertThatThrownBy(() -> HomeWidgetProviderRequestCodec.decode(raw, objectMapper))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo(reason);
    }

    private String batch(String configuration) {
        return batch(configuration, "");
    }

    private String batch(String configuration, String extraWidgetField) {
        return "{\"schemaVersion\":1,\"widgets\":[{"
                + "\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"definitionKey\":\"meetings.next-prep\","
                + "\"definitionVersion\":\"1.0.0\","
                + "\"definitionManifestHash\":\"" + "a".repeat(64) + "\","
                + "\"rendererBindingRevision\":\"binding-1\","
                + "\"configuration\":" + configuration + ",\"itemLimit\":3"
                + extraWidgetField + "}]}";
    }
}
