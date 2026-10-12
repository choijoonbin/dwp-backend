package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.Decision;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.Mode;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.one;
import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.services.approval.hrisbridge.HrisApprovalDecisionOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HrisApprovalTerminalDecisionOutboxPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ApprovalWorkflowQuorumPostgresFixture fixture;
    private ObjectMapper mapper;
    private NamedParameterJdbcTemplate named;

    @BeforeEach
    void initialize() {
        fixture = new ApprovalWorkflowQuorumPostgresFixture();
        fixture.initialize(POSTGRES);
        mapper = new ObjectMapper().findAndRegisterModules();
        named = new NamedParameterJdbcTemplate(fixture.jdbc);
    }

    @Test
    void terminalAcceptedVoteFreezesOriginalPdxAndRuntimePolicyIntoExactOutboxBody()
            throws Exception {
        fixture.start(one(Mode.ANY, null));
        UUID employeeChange = UUID.fromString(
                "00000000-0000-4000-8000-000000000201");
        UUID correlation = UUID.fromString(
                "00000000-0000-4000-8000-000000000202");
        String payloadDigest = "a".repeat(64);
        byte[] requestBody = "{\"pdx\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] responseBody = "{\"state\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8);
        UUID formVersion = fixture.jdbc.queryForObject(
                "SELECT form_version_id FROM apr_requests WHERE request_id=?",
                UUID.class, fixture.request);
        fixture.jdbc.update("""
                INSERT INTO apr_hris_employee_change_approval_bindings(
                    binding_id,tenant_id,approval_request_id,employee_change_request_public_id,
                    subject_ref,subject_revision,idempotency_key,
                    requester_person_public_id,requester_user_id,
                    workflow_key,workflow_version,workflow_version_id,form_version_id,
                    frozen_policy_version,original_correlation_id,original_payload_digest,
                    exact_request_body,request_body_sha256,command_digest,
                    exact_response_body,response_body_sha256)
                VALUES(?,42,?,?,?,3,'employee-change-idempotency-201',?,99,
                    'hris.employee-change.v1',1,?,?,?, ?,?, ?,?, ?,?,?)
                """, UUID.randomUUID(), fixture.request, employeeChange,
                "employee-change:" + employeeChange,
                ApprovalWorkflowQuorumPostgresFixture.person(99),
                fixture.workflowVersion, formVersion, fixture.pins.policyVersion(),
                correlation, payloadDigest, requestBody,
                HrisApprovalBridgeV2.sha256(requestBody), "b".repeat(64),
                responseBody, HrisApprovalBridgeV2.sha256(responseBody));

        var sink = new HrisApprovalDecisionOutbox(named, mapper);
        var audit = new AuditOutboxRecorder(named, mapper,
                "dwp-approval-server", "test", "test");
        fixture.runtime = new ApprovalWorkflowQuorumRuntime(
                named, mapper, fixture.tx, fixture, audit, sink);

        var vote = fixture.runtime.vote(
                fixture.command("FINANCE", 101, 101, Decision.APPROVE));

        assertThat(vote.requestStatus()).isEqualTo("APPROVED");
        var rows = fixture.jdbc.queryForList("""
                SELECT decision_event_id,callback_path,frozen_policy_version,
                       terminal_vote_actor_public_id,original_correlation_id,
                       original_payload_digest,exact_callback_body,
                       callback_body_sha256,command_digest,delivery_state
                  FROM apr_hris_employee_change_decision_outbox
                """);
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        byte[] exact = (byte[]) row.get("exact_callback_body");
        var callback = mapper.readValue(exact,
                HrisApprovalBridgeV2.ApprovalDecisionCallbackV1.class);
        assertThat(callback.approvalRequestId()).isEqualTo(fixture.request);
        assertThat(callback.decisionEventId()).isEqualTo(row.get("decision_event_id"));
        assertThat(callback.decision()).isEqualTo("APPROVED");
        assertThat(callback.decisionActorPublicId())
                .isEqualTo(ApprovalWorkflowQuorumPostgresFixture.person(101));
        assertThat(callback.approvalPolicyRevision())
                .isEqualTo(fixture.pins.policyVersion());
        assertThat(callback.correlationId()).isEqualTo(correlation);
        assertThat(callback.payloadDigest()).isEqualTo(payloadDigest);
        assertThat(row.get("callback_path")).isEqualTo(
                HrisApprovalBridgeV2.DECISION_CALLBACK.path(employeeChange));
        assertThat(row.get("callback_body_sha256"))
                .isEqualTo(HrisApprovalBridgeV2.sha256(exact));
        assertThat(row.get("command_digest")).isEqualTo(
                HrisApprovalBridgeV2.commandDigest(
                        HrisApprovalBridgeV2.DECISION_CALLBACK,
                        (String) row.get("callback_path"), 42,
                        (String) row.get("callback_body_sha256")));
        assertThat(row.get("delivery_state")).isEqualTo("PENDING");
    }
}
