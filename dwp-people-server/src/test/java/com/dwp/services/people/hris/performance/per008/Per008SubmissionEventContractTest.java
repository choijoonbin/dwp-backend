package com.dwp.services.people.hris.performance.per008;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Per008SubmissionEventContractTest {
    @Test
    void submissionEventHasExactlyThreeNonSensitiveFields() throws Exception {
        UUID request = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID assignment = UUID.fromString("20000000-0000-0000-0000-000000000002");
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-23T00:00:00Z");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        JsonNode payload = mapper.readTree(JdbcPer008Repository.submissionEventPayload(
                mapper, request, assignment, submittedAt));

        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "feedbackRequestId", "feedbackAssignmentId", "submittedAt");
        assertThat(payload.path("feedbackRequestId").asText()).isEqualTo(request.toString());
        assertThat(payload.path("feedbackAssignmentId").asText()).isEqualTo(assignment.toString());
        assertThat(payload.path("submittedAt").asText()).isEqualTo(submittedAt.toString());
        assertThat(payload.toString()).doesNotContain("answer", "narrative", "score", "count",
                "digest", "hash", "provider", "worker", "encryption", "threshold");
    }
}
