package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalFormUserCommandRuntimePostgresTest extends ApprovalFormUserCommandPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    enum Operation { CREATE, UPDATE, SUBMIT, INFORMATION, RECOVER }

    @BeforeEach void setUp() { initializeUserCommands(POSTGRES); }
    @AfterEach void tearDown() { clear(); }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> deniedSources() {
        return java.util.Arrays.stream(Operation.values()).flatMap(operation -> java.util.Arrays.stream(SourceResult.values())
                .filter(source -> source != SourceResult.ACTIVE).map(source -> org.junit.jupiter.params.provider.Arguments.of(operation, source)));
    }

    @ParameterizedTest @MethodSource("deniedSources")
    void actualCommandSourceFailuresLeaveRequestsPayloadHashesTasksAuditsEventsOutboxAndReceiptsUnchanged(Operation operation, SourceResult source) {
        var request = prepare(operation);
        sourceResult = source;
        var before = databaseState();
        assertThatThrownBy(() -> execute(operation, request)).isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void actualCommandNeverInventsMissingIdempotencyKey(Operation operation) {
        var request = prepare(operation);
        http.removeHeader("Idempotency-Key");
        var before = databaseState(); int calls = sourceCalls.get();
        assertThatThrownBy(() -> execute(operation, request)).isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before); assertThat(sourceCalls.get()).isEqualTo(calls);
    }

    @Test void createPersistsServerComputedCanonicalStringsAndSealsActualServerIdVersionZero() throws Exception {
        var request = createUserRequest("create-positive");
        assertThat(jdbc.queryForObject("SELECT payload->>'total' FROM apr_request_payloads WHERE request_id=?", String.class, request.requestId())).isEqualTo("6");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(payload->'total') FROM apr_request_payloads WHERE request_id=?", String.class, request.requestId())).isEqualTo("string");
        assertSealedPayload(request.requestId(), 0);
    }

    @Test void typedDraftUpdateAndRecoverRetainImmutableFormAndClassificationAfterCurrentVersionAdvances() throws Exception {
        var request = createUserRequest("old-version");
        advanceForm();
        action("request-draft-update.action", request.requestId());
        commandKey("update-positive");
        var changed = new LinkedHashMap<>(values()); changed.put("units", "4");
        var updated = tx(() -> drafts.update(request.requestId(), updateUser(request, changed), "update-positive", "corr"));
        assertThat(updated.formVersionId()).isEqualTo(formVersionId);
        assertThat(updated.formSchemaSha256()).isEqualTo(schemaSha);
        assertSealedPayload(request.requestId(), request.version());
        long version = jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, request.requestId());
        action("request-draft-recover.action", request.requestId());
        commandKey("recover-positive");
        var recovered = tx(() -> drafts.recover(request.requestId(), new ApprovalWorkDtos.RecoverDraft(1, version, "recover-positive", "Restore immutable first draft"), "corr"));
        assertThat(recovered.version()).isEqualTo(version + 1);
        assertThat(jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?", UUID.class, request.requestId())).isEqualTo(formVersionId);
        assertThat(jdbc.queryForObject("SELECT data_classification FROM apr_requests WHERE request_id=?", String.class, request.requestId())).isEqualTo(request.dataClassification());
        assertSealedPayload(request.requestId(), version);
    }

    @Test void freshFullSubmitAndInformationAmendmentRevalidateExistingUserAndRecomputePayloadBeforeHash() throws Exception {
        var request = createUserRequest("info-create");
        action("request-submit.action", request.requestId());
        var submitted = tx(() -> approvals.submit(request.requestId(), request.version(), "corr"));
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        informationRequested(request.requestId()); advanceForm();
        action("request-information-response.action", request.requestId());
        var amended = tx(() -> approvals.respondToInformationRequest(request.requestId(), new ApprovalDtos.InformationResponseRequest(
                "Correct units", Map.of("units", "5"), submitted.version()), "corr"));
        assertThat(amended.status()).isEqualTo("IN_REVIEW");
        assertThat(jdbc.queryForObject("SELECT payload->>'total' FROM apr_request_payloads WHERE request_id=?", String.class, request.requestId())).isEqualTo("10");
        assertSealedPayload(request.requestId(), submitted.version());
    }

    @Test void hiddenStaleUserStripsBeforeDraftStoreWithoutSourceLookupAndCannotDowngradeClassification() {
        action("request-create.action", null); http.removeHeader("Idempotency-Key");
        var request = tx(() -> drafts.create(new ApprovalDtos.CreateRequest(workflowId, formId, "Hidden stale", "Summary", "NORMAL",
                Map.of("mode", "OTHER", "units", "3", "reviewer", "not-a-valid-person")), "hidden-create", "corr"));
        assertThat(sourceCalls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload,'reviewer') FROM apr_request_payloads WHERE request_id=?", Boolean.class, request.requestId())).isFalse();
        assertThat(request.dataClassification()).isEqualTo("RESTRICTED");
    }

    @Test void forgedComputedValueAndAlternateFormCannotChangeStoredDraftOrReadSource() {
        var request = createUserRequest("tamper-create"); action("request-draft-update.action", request.requestId());
        var before = databaseState(); int calls = sourceCalls.get();
        var values = new LinkedHashMap<>(values()); values.put("total", "999");
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), updateUser(request, values), "tamper", "corr"))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), new ApprovalDtos.UpdateDraftRequest(workflowId,
                UUID.randomUUID(), "Cross form", "Summary", "NORMAL", values(), request.version()), "cross-form", "corr"))).isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before); assertThat(sourceCalls.get()).isEqualTo(calls);
    }

    @Test void staleExpectedVersionAndCrossOwnerFailBeforeAnySourceReadOrWrite() {
        var request = createUserRequest("stale-create"); action("request-draft-update.action", request.requestId());
        var before = databaseState(); int calls = sourceCalls.get();
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), new ApprovalDtos.UpdateDraftRequest(workflowId, formId,
                "Stale", "Summary", "NORMAL", values(), request.version() + 1), "stale-version", "corr"))).isInstanceOf(BaseException.class);
        context(100, true);
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), updateUser(request, values()), "foreign-owner", "corr"))).isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before); assertThat(sourceCalls.get()).isEqualTo(calls);
    }

    @Test void sourceFailureDoesNotPoisonTheOriginalIdempotencyKeyAndRecoveryCommitsExactlyOnce() {
        var request = createUserRequest("retry-setup"); action("request-draft-update.action", request.requestId()); commandKey("retry-original");
        var before = databaseState();
        sourceResult = SourceResult.UNAVAILABLE;
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), updateUser(request, values()), "retry-original", "corr")))
                .isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before);
        sourceResult = SourceResult.ACTIVE;
        var updated = tx(() -> drafts.update(request.requestId(), updateUser(request, values()), "retry-original", "corr"));
        assertThat(updated.request().version()).isEqualTo(request.version() + 1);
        var committed = databaseState(); int calls = sourceCalls.get();
        assertThat(tx(() -> drafts.update(request.requestId(), updateUser(request, values()), "retry-original", "corr"))).isEqualTo(updated);
        assertThat(databaseState()).isEqualTo(committed); assertThat(sourceCalls.get()).isEqualTo(calls);
    }

    @Test void duplicateKeyAndWrongActionMethodCannotReachThePeopleSourceOrChangeDraft() {
        var request = createUserRequest("metadata-setup"); action("request-draft-update.action", request.requestId());
        var before = databaseState(); int calls = sourceCalls.get();
        http.addHeader("Idempotency-Key", "second-key");
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), updateUser(request, values()), "duplicate", "corr")))
                .isInstanceOf(BaseException.class);
        commandKey("wrong-method"); http.setMethod("POST");
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), updateUser(request, values()), "wrong-method", "corr")))
                .isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before); assertThat(sourceCalls.get()).isEqualTo(calls);
    }

    @Test void fabricatedRecoverAliasCannotBorrowThePublishedActionProfileOrChangeAnyRows() {
        var request = createUserRequest("alias-setup"); action("request-draft-recover.action", request.requestId()); commandKey("alias-denied");
        String alias = "route.approvals.work.drafts.recover.action";
        var evidence = ApprovalDecisionRevisionContext.current().orElseThrow();
        ApprovalDecisionRevisionContext.set(evidence.revision(), evidence.validUntil(), evidence.contextKey(), evidence.contextScopeKey(), alias, evidence.rolloutState());
        ApprovalPilotAuthorizationContext.set(java.util.List.of(new ApprovalPilotPepRegistry.RouteAuthority(alias, "ACTION", "full-work", false,
                java.util.Set.of("predicate.approval.own-request.v1"), null, null, null, false, null, null)));
        var before = databaseState(); int calls = sourceCalls.get();
        assertThatThrownBy(() -> tx(() -> drafts.recover(request.requestId(), new ApprovalWorkDtos.RecoverDraft(1,
                request.version(), "alias-denied", "Fabricated alias"), "corr"))).isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before); assertThat(sourceCalls.get()).isEqualTo(calls);
    }

    @Test void policyExpiryDuringSourceReadCannotUseTheTransactionsOldTimestampToWrite() {
        var request = createUserRequest("expiry-setup"); action("request-draft-update.action", request.requestId()); commandKey("expiry-denied");
        var before = databaseState();
        afterSourceRead = () -> {
            jdbc.execute("SELECT pg_sleep(0.02)");
            jdbc.update("UPDATE apr_form_workflow_bindings SET effective_to=clock_timestamp()-INTERVAL '1 millisecond' WHERE form_id=?", formId);
        };
        assertThatThrownBy(() -> tx(() -> drafts.update(request.requestId(), updateUser(request, values()), "expiry-denied", "corr")))
                .isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before);
    }

    private ApprovalDtos.RequestSummary prepare(Operation operation) {
        var request = operation == Operation.CREATE ? null : createUserRequest("setup-" + operation);
        if (operation == Operation.INFORMATION) {
            action("request-submit.action", request.requestId());
            var draft = request;
            request = tx(() -> approvals.submit(draft.requestId(), draft.version(), "corr"));
            informationRequested(request.requestId());
        }
        action(leaf(operation), request == null ? null : request.requestId());
        commandKey("denied-" + operation.name().toLowerCase(java.util.Locale.ROOT));
        return request;
    }

    private Object execute(Operation operation, ApprovalDtos.RequestSummary request) {
        return tx(() -> switch (operation) {
            case CREATE -> drafts.create(new ApprovalDtos.CreateRequest(workflowId, formId, "Denied", "Summary", "NORMAL", values()), "denied-create", "corr");
            case UPDATE -> drafts.update(request.requestId(), updateUser(request, values()), "denied-update", "corr");
            case SUBMIT -> approvals.submit(request.requestId(), request.version(), "corr");
            case INFORMATION -> approvals.respondToInformationRequest(request.requestId(), new ApprovalDtos.InformationResponseRequest("More evidence", Map.of("units", "4"), request.version()), "corr");
            case RECOVER -> drafts.recover(request.requestId(), new ApprovalWorkDtos.RecoverDraft(1, request.version(), "denied-recover", "Restore source-bound revision"), "corr");
        });
    }

    private String leaf(Operation operation) {
        return switch (operation) { case CREATE -> "request-create.action"; case UPDATE -> "request-draft-update.action";
            case SUBMIT -> "request-submit.action"; case INFORMATION -> "request-information-response.action"; case RECOVER -> "request-draft-recover.action"; };
    }

    private void commandKey(String key) { http.removeHeader("Idempotency-Key"); http.addHeader("Idempotency-Key", key); }

    private void informationRequested(UUID requestId) {
        jdbc.update("UPDATE apr_tasks SET status='INFO_REQUESTED',assignee_user_id=100,completed_at=CURRENT_TIMESTAMP,decision_payload_revision=1," +
                "decision_payload_sha256=(SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?) WHERE request_id=?", requestId, requestId);
        jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?", requestId);
    }
}
