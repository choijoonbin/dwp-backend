package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDraftRecoveryPostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @BeforeEach void setUp() { initialize(POSTGRES); }
    @AfterEach void tearDown() { clear(); }

    @Test void createUnknownResponseCanReconcileAndReplayWithoutAnotherDraftOrAudit() {
        var body = body("Unknown response");
        var first = tx(() -> drafts.create(body, "create1", "corr1"));
        var audits = count("sys_audit_outbox");
        var second = tx(() -> drafts.create(body, "create1", "corr2"));
        assertThat(second).isEqualTo(first);
        assertThat(count("apr_requests")).isEqualTo(1);
        assertThat(count("apr_request_payload_versions")).isEqualTo(1);
        assertThat(count("sys_audit_outbox")).isEqualTo(audits);
        var receipt = tx(() -> drafts.reconcile("create1"));
        assertThat(receipt.receipts()).singleElement().satisfies(row -> {
            assertThat(row.commandType()).isEqualTo("CREATE");
            assertThat(row.draft().requestId()).isEqualTo(first.requestId());
            assertThat(row.draft().version()).isEqualTo(first.version());
        });
    }

    @Test void createDifferentFingerprintIs409AndReceiptIsImmutable() {
        tx(() -> drafts.create(body("One"), "same", null));
        assertThatThrownBy(() -> tx(() -> drafts.create(body("Two"), "same", null)))
                .isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
        assertThat(count("apr_requests")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_draft_commands SET fingerprint=?", "a".repeat(64)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM apr_draft_commands")).isInstanceOf(DataAccessException.class);
    }

    @Test void concurrentCreateReplayCommitsOnlyOneRequest() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { context(99, false); start.await(); try { return tx(() -> drafts.create(body("Concurrent"), "parallel", null)); } finally { clear(); } });
            var two = pool.submit(() -> { context(99, false); start.await(); try { return tx(() -> drafts.create(body("Concurrent"), "parallel", null)); } finally { clear(); } });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(two.get(10, TimeUnit.SECONDS));
            assertThat(count("apr_requests")).isEqualTo(1);
            assertThat(count("apr_draft_commands")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void createAndUpdateReceiptsApplyWithoutExactRolloutAuthority() {
        context(99, false);
        var first = tx(() -> drafts.create(body("Legacy rollout"), "create000", null));
        var update = update(first, "Saved", "New system");
        var saved = tx(() -> drafts.update(first.requestId(), update, "update100", null));
        assertThat(tx(() -> drafts.update(first.requestId(), update, "update100", null))).isEqualTo(saved);
        assertThat(saved.request().version()).isEqualTo(first.version() + 1);
        assertThat(count("apr_request_payload_versions")).isEqualTo(2);
        assertThat(tx(() -> drafts.reconcile("update100")).receipts()).hasSize(1);
    }

    @Test void updateUnknownResponseReplayAndChangedBody409PreserveExactSavedRevision() {
        var first = tx(() -> drafts.create(body("Original"), "c", null));
        var update = update(first, "Edited", "Another system");
        var saved = tx(() -> drafts.update(first.requestId(), update, "u", null));
        assertThat(tx(() -> drafts.update(first.requestId(), update, "u", null))).isEqualTo(saved);
        assertThatThrownBy(() -> tx(() -> drafts.update(first.requestId(), update(first, "Wrong", "Another"), "u", null)))
                .isInstanceOf(BaseException.class);
        assertThat(tx(() -> drafts.reconcile("u")).receipts().getFirst().draft().payloadRevision()).isEqualTo(2);
    }

    @Test void otherActorAndTenantCannotReconcileOrRecoverOwnedEvidence() {
        var first = tx(() -> drafts.create(body("Owner"), "private", null));
        context(100, true);
        assertForbidden(() -> tx(() -> drafts.reconcile("private")));
        assertForbidden(() -> tx(() -> drafts.revisions(first.requestId(), 0, 25)));
        assertForbidden(() -> tx(() -> drafts.delete(first.requestId(), command(first.version(), "d"), null)));
        ApprovalRequestContext.set(99L, 84L, null, java.util.Set.of(), PERMISSIONS);
        when(identities.require(84, 99)).thenReturn(new com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject(
                84L, 99L, null, null, "Other tenant", "x@example.test", null, "ACTIVE", List.of(), List.copyOf(PERMISSIONS)));
        assertForbidden(() -> tx(() -> drafts.reconcile("private")));
        assertThat(count("apr_draft_commands")).isEqualTo(1);
    }

    @Test void softDeleteHidesOldApisAndSubmitButRestorePreservesContentAndHistory() {
        var first = tx(() -> drafts.create(body("Trash"), "c", null));
        var deleted = tx(() -> drafts.delete(first.requestId(), command(first.version(), "d"), null));
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(tx(() -> queries.requests(ApprovalRequestContext.require(), "DRAFTS", 50))).isEmpty();
        assertThatThrownBy(() -> tx(() -> approvals.requestDetail(first.requestId()))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> approvals.submit(first.requestId(), deleted.version(), null))).isInstanceOf(BaseException.class);
        assertThat(tx(() -> search.requests(ApprovalWorkDtos.RequestView.DELETED, filter("Trash", 0, 25))).totalElements()).isEqualTo(1);
        assertThat(tx(() -> drafts.revisions(first.requestId(), 0, 25)).items()).hasSize(1);
        assertThatThrownBy(() -> tx(() -> drafts.restore(first.requestId(), command(first.version(), "stale"), null)))
                .isInstanceOf(BaseException.class);
        var restored = tx(() -> drafts.restore(first.requestId(), command(deleted.version(), "r"), null));
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.version()).isEqualTo(first.version() + 2);
        assertThat(restored.payloadRevision()).isEqualTo(1);
        assertThat(tx(() -> drafts.restore(first.requestId(), command(deleted.version(), "r"), null))).isEqualTo(restored);
        assertThat(tx(() -> approvals.requestDetail(first.requestId())).payload()).containsEntry("systemName", "System");
        assertThat(count("sys_audit_outbox")).isEqualTo(3);
    }

    @Test void revisionRecoveryAppendsNewEvidenceInsteadOfMutatingOldRows() {
        var first = tx(() -> drafts.create(body("Original"), "c", null));
        var saved = tx(() -> drafts.update(first.requestId(), update(first, "Edited", "Changed"), "u", null));
        var old = tx(() -> drafts.revision(first.requestId(), 1));
        assertThat(old.draftSnapshot()).containsEntry("title", "Original");
        var body = new ApprovalWorkDtos.RecoverDraft(1, saved.request().version(), "recovery", "Recover original");
        var recovered = tx(() -> drafts.recover(first.requestId(), body, null));
        assertThat(recovered.payloadRevision()).isEqualTo(3);
        assertThat(recovered.version()).isEqualTo(2);
        assertThat(tx(() -> drafts.recover(first.requestId(), body, null))).isEqualTo(recovered);
        assertThat(tx(() -> drafts.revision(first.requestId(), 1))).isEqualTo(old);
        assertThat(tx(() -> approvals.requestDetail(first.requestId())).request().title()).isEqualTo("Original");
        assertThat(count("apr_tasks")).isZero();
        assertThat(count("apr_request_payload_versions")).isEqualTo(3);
    }

    @Test void inactiveBindingCannotBeReintroducedByRecovery() {
        var first = tx(() -> drafts.create(body("Inactive"), "c", null));
        jdbc.update("UPDATE apr_form_workflow_bindings SET lifecycle_state='INACTIVE' WHERE tenant_id=42");
        assertThatThrownBy(() -> tx(() -> drafts.recover(first.requestId(), new ApprovalWorkDtos.RecoverDraft(1, first.version(), "r", "reason"), null)))
                .isInstanceOf(BaseException.class);
        assertThat(count("apr_draft_commands")).isEqualTo(1);
        assertThat(count("apr_request_payload_versions")).isEqualTo(1);
    }

    @Test void competingVersionsPermitOnlyOneMutation() throws Exception {
        var first = tx(() -> drafts.create(body("Version race"), "c", null));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> mutation(start, () -> drafts.update(first.requestId(), update(first, "Update", "S"), "u", null)));
            var two = pool.submit(() -> mutation(start, () -> drafts.delete(first.requestId(), command(first.version(), "d"), null)));
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, first.requestId())).isEqualTo(1);
            assertThat(count("apr_draft_commands")).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void currentPermissionRevocationRollsBackReceiptAndDeletion() {
        var first = tx(() -> drafts.create(body("Revoked"), "c", null));
        var active = subject(99, List.of("APPROVAL_OPERATOR"));
        var revoked = new com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject(42L, 99L, null, null,
                "Owner", "x@example.test", null, "ACTIVE", List.of(), List.of());
        when(identities.require(42, 99)).thenReturn(active, revoked);
        assertForbidden(() -> tx(() -> drafts.delete(first.requestId(), command(first.version(), "d"), null)));
        assertThat(count("apr_draft_commands")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM apr_requests WHERE request_id=?", Boolean.class, first.requestId())).isTrue();
    }

    @Test void authorityUnavailableAndMissingEnforcedOwnerPredicateFailClosed() {
        var first = tx(() -> drafts.create(body("Unavailable"), "c", null));
        when(identities.require(42, 99)).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Auth down"));
        assertThatThrownBy(() -> tx(() -> drafts.revisions(first.requestId(), 0, 25)))
                .isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        doReturn(subject(99, List.of("APPROVAL_OPERATOR"))).when(identities).require(42, 99);
        context(99, true);
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(
                "route.approvals.work.request-draft-delete.action", "ACTION", "owner", false,
                java.util.Set.of(), null, null, null, false, null, null)));
        assertThatThrownBy(() -> tx(() -> drafts.delete(first.requestId(), command(first.version(), "d"), null)))
                .isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(count("apr_draft_commands")).isEqualTo(1);
    }

    private boolean mutation(CountDownLatch start, java.util.function.Supplier<?> body) throws Exception {
        context(99, true); start.await();
        try { tx(body::get); return true; } catch (BaseException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT); return false;
        } finally { clear(); }
    }

    private ApprovalWorkDtos.DraftCommand command(long version, String key) {
        return new ApprovalWorkDtos.DraftCommand(version, key, "Owner requested change");
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable body) {
        assertThatThrownBy(body).isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
