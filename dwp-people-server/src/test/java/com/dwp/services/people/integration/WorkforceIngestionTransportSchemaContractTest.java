package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkforceIngestionTransportSchemaContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsExactlySixFieldsAndTwoModes() throws Exception {
        String base = "{\"objectRef\":\"obj_abcdefghijklmnop\","
                + "\"objectDigest\":\"" + "a".repeat(64) + "\","
                + "\"schemaKey\":\"workforce.v1\",\"schemaVersion\":1,"
                + "\"mode\":\"%s\",\"effectiveDate\":\"2026-10-22\"}";

        assertThat(mapper.readValue(base.formatted("VALIDATE_ONLY"),
                Hrm012WorkforceIngestionDtos.Command.class).mode())
                .isEqualTo(Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY);
        assertThat(mapper.readValue(base.formatted("APPLY"),
                Hrm012WorkforceIngestionDtos.Command.class).mode())
                .isEqualTo(Hrm012WorkforceIngestionContract.Mode.APPLY);
        assertThatThrownBy(() -> mapper.readValue(base.formatted("REPLAY"),
                Hrm012WorkforceIngestionDtos.Command.class)).isInstanceOf(
                com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void rejectsUnknownDuplicateAndInvalidDateAsClosedBadRequestInput() {
        String digest = "a".repeat(64);
        String unknown = "{\"objectRef\":\"obj_abcdefghijklmnop\","
                + "\"objectDigest\":\"" + digest + "\",\"schemaKey\":\"x\","
                + "\"schemaVersion\":1,\"mode\":\"APPLY\","
                + "\"effectiveDate\":\"2026-10-22\",\"connectorPublicId\":\"x\"}";
        String invalidDate = unknown.replace(",\"connectorPublicId\":\"x\"", "")
                .replace("2026-10-22", "2026-02-30");
        String valid = invalidDate.replace("2026-02-30", "2026-10-22");
        String trailingRoot = valid + " {}";
        assertThatThrownBy(() -> mapper.readValue(unknown,
                Hrm012WorkforceIngestionDtos.Command.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> mapper.readValue(invalidDate,
                Hrm012WorkforceIngestionDtos.Command.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> mapper.readValue(trailingRoot,
                Hrm012WorkforceIngestionDtos.Command.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void headersAcceptOnlyStrongIfMatchAndFixedPurpose() {
        assertThat(Hrm012TestFixtures.headers().expectedVersion()).isEqualTo(3);
        assertThatThrownBy(() -> new Hrm012WorkforceIngestionContract.CommandHeaders(
                "Bearer token", Hrm012TestFixtures.IDEMPOTENCY, "W/\"3\"",
                Hrm012TestFixtures.CORRELATION, "HR_OPERATIONS"))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                        .isEqualTo(400));
    }

    @Test
    void receiptResponseMatchesPinnedCanonicalSchemaWithoutRunOrReplayInternals() {
        String self = "/v1/hris/command-receipts/" + Hrm012TestFixtures.RECEIPT;
        var contract = new Hrm012WorkforceIngestionContract.RunReceipt(
                Hrm012TestFixtures.RECEIPT,
                Hrm012WorkforceIngestionContract.COMMAND_TYPE, "RECEIVED",
                Hrm012TestFixtures.DIGEST_A, Hrm012TestFixtures.CORRELATION,
                Hrm012TestFixtures.NOW,
                Hrm012WorkforceIngestionContract.ORIGINATING_ACTION,
                Hrm012TestFixtures.PERSON, Hrm012TestFixtures.DIGEST_B, 14,
                Hrm012WorkforceIngestionContract.PURPOSE, 12,
                Map.of("self", self));

        var json = mapper.valueToTree(Hrm012WorkforceIngestionDtos.Receipt.from(contract));

        assertThat(json.properties().stream().map(Map.Entry::getKey).collect(
                java.util.stream.Collectors.toSet())).isEqualTo(Set.of(
                "receiptId", "commandType", "status", "requestHash",
                "correlationId", "receivedAt", "originatingAction",
                "subjectPrincipalPublicId", "populationScopeDigest",
                "fieldPolicyRevision", "purposeCode", "authorizationRevision", "links"));
        assertThat(json.path("links").properties().stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet())).containsExactly("self");
        assertThat(json.path("links").path("self").asText()).isEqualTo(self);
        assertThat(json.has("runPublicId")).isFalse();
        assertThat(json.has("replayed")).isFalse();
    }
}
