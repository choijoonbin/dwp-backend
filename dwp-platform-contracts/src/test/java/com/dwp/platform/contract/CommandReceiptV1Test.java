package com.dwp.platform.contract;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandReceiptV1Test {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsTheClosedTwelveFieldReceipt() throws Exception {
        CommandReceiptV1 receipt = receipt();
        byte[] bytes = mapper.writeValueAsBytes(receipt);

        assertThat(mapper.readValue(bytes, CommandReceiptV1.class)).isEqualTo(receipt);
        assertThat(mapper.readTree(bytes).size()).isEqualTo(12);
    }

    @Test
    void rejectsUnknownDuplicateMissingAndCoercedFields() {
        String valid = json();
        assertThatThrownBy(() -> mapper.readValue(
                valid.replace("\"createdAt\":", "\"extra\":true,\"createdAt\":"),
                CommandReceiptV1.class)).hasMessageContaining("unknown property extra");
        assertThatThrownBy(() -> mapper.readValue(
                valid.replace("\"status\":\"SUCCEEDED\"",
                        "\"status\":\"SUCCEEDED\",\"status\":\"FAILED\""),
                CommandReceiptV1.class)).hasMessageContaining("duplicate property status");
        assertThatThrownBy(() -> mapper.readValue(
                valid.replace("\"authorizationRevision\":3,", ""),
                CommandReceiptV1.class)).hasMessageContaining("missing required properties");
        assertThatThrownBy(() -> mapper.readValue(
                valid.replace("\"fieldPolicyRevision\":2", "\"fieldPolicyRevision\":\"2\""),
                CommandReceiptV1.class)).hasMessageContaining("JSON integer");
    }

    @Test
    void enforcesCanonicalCodesDigestsRevisionsAndStatus() {
        CommandReceiptV1 value = receipt();
        assertThatThrownBy(() -> new CommandReceiptV1(
                value.receiptId(), "lowercase", value.originatingAction(),
                value.subjectPrincipalPublicId(), value.populationScopeDigest(),
                value.fieldPolicyRevision(), value.purposeCode(),
                value.authorizationRevision(), value.status(), value.correlationId(),
                value.requestDigest(), value.createdAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandReceiptV1(
                value.receiptId(), value.commandType(), value.originatingAction(),
                value.subjectPrincipalPublicId(), "A".repeat(64),
                value.fieldPolicyRevision(), value.purposeCode(),
                value.authorizationRevision(), value.status(), value.correlationId(),
                value.requestDigest(), value.createdAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandReceiptV1(
                value.receiptId(), value.commandType(), value.originatingAction(),
                value.subjectPrincipalPublicId(), value.populationScopeDigest(),
                0, value.purposeCode(), value.authorizationRevision(), "DONE",
                value.correlationId(), value.requestDigest(), value.createdAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CommandReceiptV1 receipt() {
        return new CommandReceiptV1(
                uuid(1), "RECORD_EMPLOYEE_CHANGE_APPROVAL_DECISION",
                "HRM_EMPLOYEE_CHANGE_APPROVAL_DECISION_RECORD", uuid(2),
                "a".repeat(64), 2, "HRIS_APPROVAL_DECISION_CALLBACK", 3,
                "SUCCEEDED", uuid(4), "b".repeat(64),
                Instant.parse("2026-10-12T03:00:00Z"));
    }

    private static String json() {
        return """
                {"receiptId":"00000000-0000-0000-0000-000000000001",\
                "commandType":"RECORD_EMPLOYEE_CHANGE_APPROVAL_DECISION",\
                "originatingAction":"HRM_EMPLOYEE_CHANGE_APPROVAL_DECISION_RECORD",\
                "subjectPrincipalPublicId":"00000000-0000-0000-0000-000000000002",\
                "populationScopeDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",\
                "fieldPolicyRevision":2,"purposeCode":"HRIS_APPROVAL_DECISION_CALLBACK",\
                "authorizationRevision":3,"status":"SUCCEEDED",\
                "correlationId":"00000000-0000-0000-0000-000000000004",\
                "requestDigest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",\
                "createdAt":"2026-10-12T03:00:00Z"}
                """;
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
