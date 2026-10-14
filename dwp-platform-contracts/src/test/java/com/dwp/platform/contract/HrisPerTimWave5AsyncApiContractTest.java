package com.dwp.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HrisPerTimWave5AsyncApiContractTest {

    @Test
    void perAndTimPublishOnlyTheTwoSealedTypedEventIdentities() throws Exception {
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(
                rootFile("contracts/asyncapi/domain-events.yaml").toFile());

        assertEvent(document, "performanceReviewSubmitted",
                "PerformanceReviewSubmitted.v1", "PerformanceReviewSubmittedEnvelope",
                "PerformanceReviewSubmittedData", "PERFORMANCE_REVIEW",
                List.of("reviewId", "participantId", "reviewerAssignmentId", "submissionId",
                        "templateVersionId", "contentHash"));
        assertEvent(document, "timecardDecisionRecorded",
                "TimecardDecisionRecorded.v1", "TimecardDecisionRecordedEnvelope",
                "TimecardDecisionRecordedData", "TIMECARD",
                List.of("timecardId", "workerId", "status", "ledgerRevision", "decisionAt"));

        assertThat(document.at("/components/messages/TimecardDecisionRecorded").isMissingNode())
                .isTrue();
        assertThat(textValues(document.at(
                "/components/schemas/TimecardDecisionRecordedData/properties/status/enum")))
                .containsExactly("APPROVED", "REJECTED", "CORRECTION_REQUIRED");
        assertThat(document.at(
                "/components/schemas/TimecardDecisionRecordedData/properties/ledgerRevision/minimum")
                .asLong()).isOne();
        assertThat(document.at(
                "/components/schemas/PerformanceReviewSubmittedData/properties/contentHash/pattern")
                .asText()).isEqualTo("^[0-9a-f]{64}$");
    }

    private static void assertEvent(
            JsonNode document,
            String messageKey,
            String eventType,
            String envelopeName,
            String dataName,
            String aggregateType,
            List<String> fields) {
        JsonNode envelope = document.at("/components/schemas/" + envelopeName);
        assertThat(envelope.at("/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/DomainEventEnvelope");
        JsonNode typed = envelope.at("/allOf/1");
        assertThat(textValues(typed.path("required")))
                .containsExactly("type", "schemaVersion", "aggregateType", "data");
        assertThat(typed.at("/properties/type/const").asText()).isEqualTo(eventType);
        assertThat(typed.at("/properties/schemaVersion/const").asInt()).isOne();
        assertThat(typed.at("/properties/aggregateType/const").asText())
                .isEqualTo(aggregateType);
        assertThat(typed.at("/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/" + dataName);

        JsonNode data = document.at("/components/schemas/" + dataName);
        assertThat(data.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(data.path("properties"))).containsExactlyElementsOf(fields);
        assertThat(textValues(data.path("required"))).containsExactlyElementsOf(fields);

        assertThat(document.at("/components/messages/" + messageKey + "/name").asText())
                .isEqualTo(eventType);
        assertThat(document.at("/components/messages/" + messageKey + "/payload/$ref").asText())
                .isEqualTo("#/components/schemas/" + envelopeName);
        assertThat(document.at("/channels/domainEvents/address").asText())
                .isEqualTo("dwp.domain-events.v1");
        assertThat(document.at("/channels/domainEvents/messages/" + messageKey + "/$ref")
                .asText()).isEqualTo("#/components/messages/" + messageKey);
        assertThat(textValues(document.at("/operations/publishDomainEvent/messages")))
                .contains("#/channels/domainEvents/messages/" + messageKey);
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> values = new ArrayList<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.has("$ref")
                ? value.path("$ref").asText()
                : value.asText()));
        return values;
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
