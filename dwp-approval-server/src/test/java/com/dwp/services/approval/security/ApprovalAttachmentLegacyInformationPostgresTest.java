package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real legacy service, tasks and binding. Storage/AV are the existing test-only protocol adapters. */
@Testcontainers
class ApprovalAttachmentLegacyInformationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = ApprovalAttachmentLifecycleBindingPostgresTest.PG;
    final ApprovalAttachmentLifecycleBindingPostgresTest fixture = new ApprovalAttachmentLifecycleBindingPostgresTest();
    UUID informationTask;

    @BeforeEach void prepare() throws Exception {
        // The existing NEW fixture owns the exact intake/scan/provider protocol setup.
        fixture.before();
        fixture.tx(() -> fixture.approvals.submit(fixture.request, 0, null));
        ApprovalDocumentPostgresFixture.docContext(100);
        informationTask = fixture.jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE request_id=? AND status='PENDING'", UUID.class, fixture.request);
        fixture.tx(() -> fixture.approvals.claim(informationTask, 0, null));
        fixture.tx(() -> fixture.approvals.decide(informationTask, new ApprovalDtos.DecisionRequest("REQUEST_INFO", "Please supply additional evidence", 1L), null));
        ApprovalDocumentPostgresFixture.docContext(99);
    }
    @AfterEach void cleanup() { fixture.after(); }

    @Test void attachmentOnlyAmendmentAdvancesImmutableRevisionDespiteIdenticalPayloadHashAndRestartsFlow() {
        attach(); String previous = payloadHash(); int previousRevision = revision();
        reply(null);
        assertThat(payloadHash()).isEqualTo(previous); assertThat(revision()).isEqualTo(previousRevision + 1);
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM apr_tasks WHERE task_id=?", String.class, informationTask)).isEqualTo("SUPERSEDED");
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM apr_tasks WHERE request_id=? AND status='PENDING'", Long.class, fixture.request)).isEqualTo(1);
        assertThat(manifestCount()).isEqualTo(2);
        assertThat(event()).contains("\"materialChange\": true", "\"previousPayloadRevision\": 1", "\"payloadRevision\": 2");
    }
    @Test void nonmaterialReplyKeepsOriginalManifestAndPayloadRevisionAndResumesTask() {
        String previous = payloadHash(); reply(null);
        assertThat(payloadHash()).isEqualTo(previous); assertThat(revision()).isEqualTo(1); assertThat(manifestCount()).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM apr_tasks WHERE task_id=?", String.class, informationTask)).isEqualTo("CLAIMED");
        assertThat(event()).contains("\"materialChange\": false");
    }
    @Test void combinedPayloadAndAttachmentAmendmentSealsExactlyOneNewImmutableRevision() {
        attach(); String previous = payloadHash(); reply(Map.of("systemName", "Amended system"));
        assertThat(payloadHash()).isNotEqualTo(previous); assertThat(revision()).isEqualTo(2); assertThat(manifestCount()).isEqualTo(2);
        assertThat(fixture.jdbc.queryForObject("SELECT count(*) FROM apr_request_payload_versions WHERE request_id=?", Long.class, fixture.request)).isEqualTo(2);
    }
    @Test void currentObjectTamperImmediatelyBeforeActualSealRollsBackTaskRestartAndAllDurableEffects() {
        attach(); String before = snapshot();
        doAnswer(call -> { when(fixture.storage.load(any())).thenReturn(new byte[]{1, 2, 3}); return call.callRealMethod(); })
                .when(fixture.binding).seal(any(), any());
        denied(ErrorCode.RESOURCE_CONFLICT, () -> reply(null)); assertThat(snapshot()).isEqualTo(before);
    }
    @Test void permissionRevocationBeforeActualSealRollsBackSupersededDecisionHistoryEventsAuditAndOutbox() {
        attach(); String before = snapshot();
        doAnswer(call -> {
            doReturn(ApprovalDocumentPostgresFixture.documentSubject(99, List.of("APPROVAL_OPERATOR"), java.util.Set.of()))
                    .when(fixture.identities).require(42, 99); return call.callRealMethod();
        }).when(fixture.binding).seal(any(), any());
        denied(ErrorCode.FORBIDDEN, () -> reply(null)); assertThat(snapshot()).isEqualTo(before);
    }
    private void attach() {
        UUID attachment = ReflectionTestUtils.invokeMethod(fixture, "available", "legacy-information");
        ReflectionTestUtils.invokeMethod(fixture, "select", List.of(attachment));
    }
    private void reply(Map<String, Object> patch) { fixture.tx(() -> fixture.approvals.respondToInformationRequest(fixture.request,
            new ApprovalDtos.InformationResponseRequest("Evidence supplied", patch, version()), null)); }
    private long version() { return fixture.jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, fixture.request); }
    private int revision() { return fixture.jdbc.queryForObject("SELECT schema_version FROM apr_request_payloads WHERE request_id=?", Integer.class, fixture.request); }
    private String payloadHash() { return fixture.jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?", String.class, fixture.request); }
    private long manifestCount() { return fixture.jdbc.queryForObject("SELECT count(*) FROM apr_attachment_manifests WHERE request_id=?", Long.class, fixture.request); }
    private String event() { return fixture.jdbc.queryForObject("SELECT event_data::text FROM apr_request_events WHERE request_id=? AND event_type='INFORMATION_RESPONDED'", String.class, fixture.request); }
    private String snapshot() {
        var rows = new java.util.TreeMap<String, Object>();
        for (String table : List.of("apr_requests", "apr_tasks", "apr_steps", "apr_request_payloads", "apr_request_payload_versions", "apr_attachment_manifests",
                "apr_attachment_preparations", "apr_attachment_selections", "apr_request_events", "sys_audit_outbox", "apr_integration_outbox"))
            rows.put(table, fixture.jdbc.queryForObject("SELECT coalesce(jsonb_agg(row_data ORDER BY row_data::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) row_data FROM " + table + " t) rows", String.class));
        return fixture.canonical.json(rows);
    }
    private static void denied(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable body) { assertThatThrownBy(body)
            .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code)); }
}
