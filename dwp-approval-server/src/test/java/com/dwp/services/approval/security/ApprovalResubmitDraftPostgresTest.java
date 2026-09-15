package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDraftService;
import com.dwp.services.approval.domain.ApprovalResubmitDraftDtos;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalResubmitDraftPostgresTest extends ApprovalDraftPostgresFixture {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void setUp() {
        initialize(POSTGRES);
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void copiesOnlySafeRequestContentIntoCurrentPublishedAssetsAndSealsLineage() {
        Source source = terminal("APPROVED", "source-copy");
        UUID sourceWorkflowVersion = workflowVersion(source.id());
        UUID sourceFormVersion = formVersion(source.id());
        addTerminalTaskAndAttachmentSelection(source.id());
        long sourceEvents = requestCount("apr_request_events", source.id());
        long sourceTasks = requestCount("apr_tasks", source.id());
        String sourcePayload = payload(source.id());
        CurrentAssets current = advanceCurrentAssets();
        long audits = count("sys_audit_outbox");

        var result = tx(() -> drafts.resubmit(source.id(), request(source.version()),
                "resubmit-copy", "corr-resubmit"));
        UUID draftId = result.draft().requestId();

        assertThat(draftId).isNotEqualTo(source.id());
        assertThat(result.sourceRequestId()).isEqualTo(source.id());
        assertThat(result.sourceVersion()).isEqualTo(source.version());
        assertThat(result.draft().status()).isEqualTo("DRAFT");
        assertThat(result.draft().title()).isEqualTo("Terminal source");
        assertThat(result.draft().summary()).isEqualTo("Reason");
        assertThat(result.draft().priority()).isEqualTo("NORMAL");
        assertThat(workflowVersion(draftId)).isEqualTo(current.workflowVersion());
        assertThat(formVersion(draftId)).isEqualTo(current.formVersion());
        assertThat(workflowVersion(source.id())).isEqualTo(sourceWorkflowVersion);
        assertThat(formVersion(source.id())).isEqualTo(sourceFormVersion);
        assertThat(payload(draftId)).isEqualTo(sourcePayload);
        assertThat(jdbc.queryForObject(
                "SELECT source_system FROM apr_requests WHERE request_id=?", String.class, draftId))
                .isEqualTo("DWP_APPROVAL_RESUBMIT");
        assertThat(jdbc.queryForObject(
                "SELECT source_reference FROM apr_requests WHERE request_id=?", String.class, draftId))
                .isEqualTo(source.id().toString());

        assertThat(requestCount("apr_request_events", source.id())).isEqualTo(sourceEvents);
        assertThat(requestCount("apr_tasks", source.id())).isEqualTo(sourceTasks);
        assertThat(requestCount("apr_tasks", draftId)).isZero();
        assertThat(requestCount("apr_steps", draftId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT attachment_ids::text FROM apr_attachment_selections WHERE tenant_id=42 AND request_id=?",
                String.class, draftId)).isEqualTo("[]");
        assertThat(jdbc.queryForObject(
                "SELECT event_data->>'sourceRequestId' FROM apr_request_events "
                        + "WHERE tenant_id=42 AND request_id=? AND event_type='REQUEST_RESUBMIT_DRAFTED'",
                String.class, draftId)).isEqualTo(source.id().toString());
        assertThat(count("sys_audit_outbox")).isEqualTo(audits + 1);
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'action' FROM sys_audit_outbox ORDER BY created_at DESC LIMIT 1", String.class))
                .isEqualTo("approval.request.resubmit-draft.created");
        assertThat(jdbc.queryForObject(
                "SELECT payload->'afterState'->>'sourceRequestId' FROM sys_audit_outbox "
                        + "ORDER BY created_at DESC LIMIT 1", String.class))
                .isEqualTo(source.id().toString());
        assertThat(jdbc.queryForObject(
                "SELECT request_id FROM apr_draft_commands WHERE command_route=? AND idempotency_key='resubmit-copy'",
                UUID.class, route(source.id()))).isEqualTo(draftId);
    }

    @Test
    void concurrentReplayWithOneKeyCreatesExactlyOneDraftAndOneAudit() throws Exception {
        Source source = terminal("REJECTED", "source-concurrent");
        long audits = count("sys_audit_outbox");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> invokeConcurrent(source, start));
            var two = pool.submit(() -> invokeConcurrent(source, start));
            start.countDown();
            var first = one.get(20, TimeUnit.SECONDS);
            var second = two.get(20, TimeUnit.SECONDS);
            assertThat(first).isEqualTo(second);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM apr_requests WHERE tenant_id=42 AND source_system='DWP_APPROVAL_RESUBMIT' "
                            + "AND source_reference=?", Long.class, source.id().toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM apr_request_events WHERE event_type='REQUEST_RESUBMIT_DRAFTED' "
                            + "AND event_data->>'sourceRequestId'=?", Long.class, source.id().toString())).isEqualTo(1);
            assertThat(count("sys_audit_outbox")).isEqualTo(audits + 1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM apr_draft_commands WHERE command_route=? AND idempotency_key='same-key'",
                    Long.class, route(source.id()))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nonTerminalWrongVersionAndWrongOwnerStayClosedWithoutSideEffects() {
        var draft = tx(() -> drafts.create(body("Not terminal"), "not-terminal-source", null));
        long requests = count("apr_requests");
        long receipts = count("apr_draft_commands");
        long audits = count("sys_audit_outbox");

        assertCode(ErrorCode.RESOURCE_CONFLICT, () -> tx(() -> drafts.resubmit(
                draft.requestId(), request(draft.version()), "blocked-state", null)));
        terminal(draft.requestId(), "APPROVED", 7);
        assertCode(ErrorCode.OBJECT_VERSION_CONFLICT, () -> tx(() -> drafts.resubmit(
                draft.requestId(), request(6), "blocked-version", null)));
        context(100, false);
        assertCode(ErrorCode.RESOURCE_NOT_AVAILABLE, () -> tx(() -> drafts.resubmit(
                draft.requestId(), request(7), "blocked-owner", null)));

        assertThat(count("apr_requests")).isEqualTo(requests);
        assertThat(count("apr_draft_commands")).isEqualTo(receipts);
        assertThat(count("sys_audit_outbox")).isEqualTo(audits);
    }

    @Test
    void currentPermissionRevocationAndAuthorityFailureRollBackEverything() {
        Source source = terminal("APPROVED", "source-authority");
        long requests = count("apr_requests");
        long receipts = count("apr_draft_commands");
        long audits = count("sys_audit_outbox");
        var viewOnly = new ApprovalIdentityDirectory.Subject(
                42L, 99L, null, null, "Owner", "owner@example.test", null, "ACTIVE",
                List.of("APPROVAL_OPERATOR"),
                List.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:VIEW"));
        when(identities.require(42, 99)).thenReturn(viewOnly);
        assertCode(ErrorCode.FORBIDDEN, () -> tx(() -> drafts.resubmit(
                source.id(), request(source.version()), "revoked", null)));
        when(identities.require(42, 99)).thenThrow(
                new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Auth unavailable"));
        assertCode(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> tx(() -> drafts.resubmit(
                source.id(), request(source.version()), "unavailable", null)));
        assertThat(count("apr_requests")).isEqualTo(requests);
        assertThat(count("apr_draft_commands")).isEqualTo(receipts);
        assertThat(count("sys_audit_outbox")).isEqualTo(audits);
    }

    @Test
    void payloadIncompatibleWithLatestPublishedSchemaIsUnprocessableAndWritesNothing() {
        Source source = terminal("REJECTED", "source-schema");
        publishIncompatibleCurrentForm();
        long requests = count("apr_requests");
        long receipts = count("apr_draft_commands");
        long audits = count("sys_audit_outbox");
        long events = count("apr_request_events");

        assertThatThrownBy(() -> tx(() -> drafts.resubmit(
                source.id(), request(source.version()), "schema-mismatch", null)))
                .isInstanceOf(ApprovalDraftService.IncompatibleSourcePayload.class);
        assertThat(count("apr_requests")).isEqualTo(requests);
        assertThat(count("apr_draft_commands")).isEqualTo(receipts);
        assertThat(count("sys_audit_outbox")).isEqualTo(audits);
        assertThat(count("apr_request_events")).isEqualTo(events);
    }

    @Test
    void payloadThatNoLongerMatchesCurrentConditionalRouteIsUnprocessableAndWritesNothing() {
        Source source = terminal("APPROVED", "source-route");
        assertThat(jdbc.update(
                "UPDATE apr_form_workflow_bindings SET binding_type='CONDITIONAL',condition_payload=?::jsonb "
                        + "WHERE tenant_id=42 AND form_id=? AND workflow_id=? AND lifecycle_state='ACTIVE'",
                "{\"all\":[{\"field\":\"systemName\",\"operator\":\"EQ\",\"value\":\"Different\"}]}",
                formId, workflowId)).isEqualTo(1);
        long requests = count("apr_requests");
        long receipts = count("apr_draft_commands");
        long audits = count("sys_audit_outbox");
        long events = count("apr_request_events");

        assertThatThrownBy(() -> tx(() -> drafts.resubmit(
                source.id(), request(source.version()), "route-mismatch", null)))
                .isInstanceOf(ApprovalDraftService.IncompatibleSourcePayload.class);
        assertThat(count("apr_requests")).isEqualTo(requests);
        assertThat(count("apr_draft_commands")).isEqualTo(receipts);
        assertThat(count("sys_audit_outbox")).isEqualTo(audits);
        assertThat(count("apr_request_events")).isEqualTo(events);
    }

    private ApprovalResubmitDraftDtos.Response invokeConcurrent(Source source, CountDownLatch start) throws Exception {
        context(99, false);
        try {
            start.await();
            return tx(() -> drafts.resubmit(source.id(), request(source.version()), "same-key", "concurrent"));
        } finally {
            clear();
        }
    }

    private Source terminal(String status, String key) {
        var created = tx(() -> drafts.create(body("Terminal source"), key, "source"));
        return terminal(created.requestId(), status, 7);
    }

    private Source terminal(UUID requestId, String status, long version) {
        assertThat(jdbc.update(
                "UPDATE apr_requests SET status=?,version=?,submitted_at=clock_timestamp()-interval '1 hour',"
                        + "completed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE tenant_id=42 AND request_id=?",
                status, version, requestId)).isEqualTo(1);
        return new Source(requestId, version);
    }

    private void addTerminalTaskAndAttachmentSelection(UUID requestId) {
        UUID step = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID selectedAttachment = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_steps(step_id,tenant_id,request_id,step_key,step_name,sequence_number,"
                + "approval_mode,status,started_at,completed_at) VALUES(?,42,?,'FINAL','Final',1,'ANY','APPROVED',"
                + "clock_timestamp()-interval '1 hour',clock_timestamp())", step, requestId);
        jdbc.update("INSERT INTO apr_tasks(task_id,tenant_id,request_id,step_id,assignee_user_id,candidate_role,"
                + "status,risk_score,completed_at,decision_actor_user_id,decision_payload_revision,decision_payload_sha256) "
                + "SELECT ?,42,?,?,99,'APPROVAL_OPERATOR','APPROVED',20,clock_timestamp(),99,schema_version,payload_sha256 "
                + "FROM apr_request_payloads WHERE tenant_id=42 AND request_id=?", task, requestId, step, requestId);
        jdbc.update("UPDATE apr_attachment_selections SET attachment_ids=?::jsonb WHERE tenant_id=42 AND request_id=?",
                "[\"" + selectedAttachment + "\"]", requestId);
    }

    private CurrentAssets advanceCurrentAssets() {
        UUID oldWorkflowVersion = jdbc.queryForObject(
                "SELECT workflow_version_id FROM apr_workflow_versions WHERE tenant_id=42 AND workflow_id=? "
                        + "AND version_number=(SELECT current_version FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_id=?)",
                UUID.class, workflowId, workflowId);
        UUID nextWorkflowVersion = UUID.randomUUID();
        int nextWorkflowNumber = jdbc.queryForObject(
                "SELECT MAX(version_number)+1 FROM apr_workflow_versions WHERE tenant_id=42 AND workflow_id=?",
                Integer.class, workflowId);
        jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,"
                        + "definition,definition_sha256,lifecycle_state,published_by) "
                        + "SELECT ?,tenant_id,workflow_id,?,definition,definition_sha256,'PUBLISHED',99 "
                        + "FROM apr_workflow_versions WHERE tenant_id=42 AND workflow_version_id=?",
                nextWorkflowVersion, nextWorkflowNumber, oldWorkflowVersion);
        jdbc.update("UPDATE apr_workflow_definitions SET current_version=? WHERE tenant_id=42 AND workflow_id=?",
                nextWorkflowNumber, workflowId);

        UUID oldFormVersion = jdbc.queryForObject(
                "SELECT form_version_id FROM apr_form_versions WHERE tenant_id=42 AND form_id=? "
                        + "AND version_number=(SELECT current_version FROM apr_forms WHERE tenant_id=42 AND form_id=?)",
                UUID.class, formId, formId);
        UUID nextFormVersion = UUID.randomUUID();
        int nextFormNumber = jdbc.queryForObject(
                "SELECT MAX(version_number)+1 FROM apr_form_versions WHERE tenant_id=42 AND form_id=?",
                Integer.class, formId);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,"
                        + "schema_sha256,lifecycle_state,published_by) "
                        + "SELECT ?,tenant_id,form_id,?,schema_payload,schema_sha256,'PUBLISHED',99 "
                        + "FROM apr_form_versions WHERE tenant_id=42 AND form_version_id=?",
                nextFormVersion, nextFormNumber, oldFormVersion);
        jdbc.update("UPDATE apr_forms SET current_version=? WHERE tenant_id=42 AND form_id=?",
                nextFormNumber, formId);
        return new CurrentAssets(nextWorkflowVersion, nextFormVersion);
    }

    private void publishIncompatibleCurrentForm() {
        String schema = """
                {"schemaVersion":1,"fields":[
                  {"key":"summary","type":"TEXT","required":true},
                  {"key":"newRequired","type":"TEXT","required":true}
                ]}
                """;
        UUID version = UUID.randomUUID();
        int number = jdbc.queryForObject(
                "SELECT MAX(version_number)+1 FROM apr_form_versions WHERE tenant_id=42 AND form_id=?",
                Integer.class, formId);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,"
                        + "schema_sha256,lifecycle_state,published_by) VALUES(?,42,?,?,?::jsonb,?,'PUBLISHED',99)",
                version, formId, number, schema, sha(schema));
        jdbc.update("UPDATE apr_forms SET current_version=? WHERE tenant_id=42 AND form_id=?", number, formId);
    }

    private ApprovalResubmitDraftDtos.Request request(long version) {
        return new ApprovalResubmitDraftDtos.Request(version);
    }

    private UUID workflowVersion(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT workflow_version_id FROM apr_requests WHERE tenant_id=42 AND request_id=?",
                UUID.class, requestId);
    }

    private UUID formVersion(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT form_version_id FROM apr_requests WHERE tenant_id=42 AND request_id=?",
                UUID.class, requestId);
    }

    private String payload(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT payload::text FROM apr_request_payloads WHERE tenant_id=42 AND request_id=?",
                String.class, requestId);
    }

    private long requestCount(String table, UUID requestId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=42 AND request_id=?",
                Long.class, requestId);
    }

    private String route(UUID sourceRequestId) {
        return "POST /v1/requests/" + sourceRequestId + "/resubmit-draft";
    }

    private void assertCode(ErrorCode code, Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Source(UUID id, long version) {
    }

    private record CurrentAssets(UUID workflowVersion, UUID formVersion) {
    }
}
