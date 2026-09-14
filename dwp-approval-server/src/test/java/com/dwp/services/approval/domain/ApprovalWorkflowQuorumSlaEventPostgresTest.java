package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumSlaEventPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ApprovalWorkflowQuorumPostgresFixture f;
    @BeforeEach void initialize() { f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES); }

    @Test void finalizedEventBindsCurrentRequestStagePayloadPolicyAndActualFrozenTaskSeats() throws Exception {
        f.start(one(ApprovalWorkflowQuorum.Mode.ALL, null)); f.dueTimers();
        var lease = f.sla.claim("v1-worker", 60, 1).getFirst(); assertTrue(f.sla.finish(lease));
        JsonNode envelope = envelope(); JsonNode body = envelope.get("payload");
        assertEquals(6, envelope.size()); assertEquals(24, body.size());
        assertEquals(ApprovalWorkflowQuorumSlaEvent.CONTRACT, body.get("eventContract").asText());
        assertEquals(f.request.toString(), envelope.get("requestId").asText());
        assertEquals(f.pins.workflowVersionId().toString(), body.get("workflowVersionId").asText());
        assertEquals(f.pins.workflowDefinitionSha256(), body.get("workflowDefinitionSha256").asText());
        assertEquals(f.pins.formSchemaSha256(), body.get("formSchemaSha256").asText());
        assertEquals(f.pins.policySha256(), body.get("policySha256").asText());
        assertEquals("a".repeat(64), body.get("payloadSha256").asText());
        assertEquals(1, body.get("payloadRevision").asInt());
        assertEquals(lease.stepId().toString(), body.get("stepId").asText());
        assertEquals(lease.generation(), body.get("generation").asLong());
        assertEquals("RS_APPROVALS", body.get("managementResourceSetKey").asText());
        assertNotNull(java.time.Instant.parse(body.get("occurredAt").asText()));
        for (JsonNode seat : body.get("recipientSeats")) {
            assertEquals(4, seat.size());
            assertEquals(person(seat.get("userId").asLong()).toString(), seat.get("personPublicId").asText());
            assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM apr_tasks WHERE task_id=?::uuid AND version=? "
                    + "AND request_id=? AND step_id=? AND assignee_user_id=?", Integer.class,
                    seat.get("taskId").asText(), seat.get("taskVersion").asLong(), f.request, lease.stepId(), seat.get("userId").asLong()));
        }
        verifyDigest(body); assertFalse(f.sla.finish(lease));
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM apr_integration_outbox WHERE event_type LIKE 'Approval.Quorum.Sla%'", Integer.class));
    }

    @Test void oneThousandRecipientsRemainCompleteSortedAndImmutableWithoutNotificationLimitTruncation() throws Exception {
        f.pool = LongStream.range(1000, 2000).boxed().toList();
        f.start(one(ApprovalWorkflowQuorum.Mode.ALL, null)); f.dueTimers();
        var lease = f.sla.claim("full-audience-worker", 60, 1).getFirst(); assertTrue(f.sla.finish(lease));
        JsonNode body = envelope().get("payload");
        assertEquals(1000, body.get("recipientUserIds").size()); assertEquals(1000, body.get("recipientSeats").size());
        for (int i = 0; i < 1000; i++) assertEquals(1000 + i, body.get("recipientUserIds").get(i).asLong());
        String digest = body.get("recipientSnapshotSha256").asText();
        f.revoked.add(1000L);
        assertEquals(digest, envelope().get("payload").get("recipientSnapshotSha256").asText());
        verifyDigest(body);
    }

    private JsonNode envelope() throws Exception {
        return mapper.readTree(f.jdbc.queryForObject("SELECT payload::text FROM apr_integration_outbox "
                + "WHERE event_type LIKE 'Approval.Quorum.Sla%'", String.class));
    }
    private void verifyDigest(JsonNode body) {
        List<Long> ids = new ArrayList<>(); body.get("recipientUserIds").forEach(id -> ids.add(id.asLong()));
        List<Map<String, Object>> seats = new ArrayList<>(); body.get("recipientSeats").forEach(seat -> seats.add(Map.of(
                "userId", seat.get("userId").asLong(), "personPublicId", seat.get("personPublicId").asText(),
                "taskId", seat.get("taskId").asText(), "taskVersion", seat.get("taskVersion").asLong())));
        assertEquals(ApprovalFormSchemaV2Canonical.sha256(ApprovalFormSchemaV2Canonical.json(
                ApprovalFormSchemaV2Canonical.freeze(Map.of("recipientUserIds", ids, "recipientSeats", seats)))), body.get("recipientSnapshotSha256").asText());
    }
}
