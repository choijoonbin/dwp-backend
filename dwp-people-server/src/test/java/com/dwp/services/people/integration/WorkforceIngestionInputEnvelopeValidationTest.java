package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceInputEnvelopeV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkforceInputEnvelopeValidationTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptedEnvelopeHasClosedIdentityAndStableOneBasedRecordSequence() {
        Hrm012WorkforceInputEnvelopeV1 envelope = Hrm012WorkforceInputEnvelopeV1.parse(
                Hrm012TestFixtures.ENVELOPE, "workforce.v1", 1, mapper);

        assertThat(envelope.schemaKey()).isEqualTo("workforce.v1");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.records()).singleElement().satisfies(record -> {
            assertThat(record.sequence()).isEqualTo(1);
            assertThat(record.sourceRecordKey()).isEqualTo("employee-1");
            assertThat(record.sourceVersion()).isEqualTo("v1");
            assertThat(record.payload().path("displayName").asText()).isEqualTo("redacted");
        });
    }

    @Test
    void duplicateAndUnknownEnvelopeOrRecordFieldsFailClosed() {
        String digestFreePrefix = "{\"schemaKey\":\"workforce.v1\",";
        String duplicate = digestFreePrefix
                + "\"schemaKey\":\"workforce.v1\",\"schemaVersion\":1,"
                + "\"records\":[{\"sourceRecordKey\":\"a\","
                + "\"sourceVersion\":\"v1\",\"payload\":{\"x\":1}}]}";
        String unknownEnvelope = new String(
                Hrm012TestFixtures.ENVELOPE, StandardCharsets.UTF_8)
                .replace("\"records\":", "\"authority\":\"caller\",\"records\":");
        String unknownRecord = new String(
                Hrm012TestFixtures.ENVELOPE, StandardCharsets.UTF_8)
                .replace("\"payload\":", "\"connectorPublicId\":\"caller\",\"payload\":");

        String trailingRoot = new String(
                Hrm012TestFixtures.ENVELOPE, StandardCharsets.UTF_8) + " {}";

        for (String invalid : new String[]{
                duplicate, unknownEnvelope, unknownRecord, trailingRoot}) {
            assertThatThrownBy(() -> Hrm012WorkforceInputEnvelopeV1.parse(
                    invalid.getBytes(StandardCharsets.UTF_8),
                    "workforce.v1", 1, mapper))
                    .isInstanceOf(Hrm012Problem.class)
                    .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                            .isEqualTo(400));
        }
    }

    @Test
    void schemaMismatchEmptyPayloadAndScalarPayloadAreRejectedBeforeMapping() {
        assertThatThrownBy(() -> Hrm012WorkforceInputEnvelopeV1.parse(
                Hrm012TestFixtures.ENVELOPE, "other.v1", 1, mapper))
                .isInstanceOf(Hrm012Problem.class);
        String source = new String(Hrm012TestFixtures.ENVELOPE, StandardCharsets.UTF_8);
        for (String invalid : new String[]{
                source.replace("{\"displayName\":\"redacted\"}", "{}"),
                source.replace("{\"displayName\":\"redacted\"}", "\"secret\"")}) {
            assertThatThrownBy(() -> Hrm012WorkforceInputEnvelopeV1.parse(
                    invalid.getBytes(StandardCharsets.UTF_8),
                    "workforce.v1", 1, mapper))
                    .isInstanceOf(Hrm012Problem.class);
        }
    }
}
