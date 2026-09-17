package com.dwp.services.notification.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DecisionRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DraftRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Revision;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionGovernanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final NotificationRequestContext.Actor AUTHOR = actor(17L);
    private static final NotificationRequestContext.Actor REVIEWER = actor(18L);

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationAttentionGovernanceRepository repository =
            mock(NotificationAttentionGovernanceRepository.class);
    private final NotificationIdempotencyRepository idempotency =
            mock(NotificationIdempotencyRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final NotificationAttentionGovernanceLock governanceLock =
            mock(NotificationAttentionGovernanceLock.class);
    private final NotificationAttentionGovernanceService service =
            new NotificationAttentionGovernanceService(
                    databaseScope, repository, idempotency, audit,
                    governanceLock, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsCanonicalTenantDraftWithOptimisticRevisionAndIdempotency() {
        Settings settings = settings(true, true);
        DraftRequest request = new DraftRequest(
                settings, "Protect tenant attention governance", "2");
        Revision result = revision(UUID.randomUUID(), "DRAFT", settings, 3, "1", 17L);
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt("draft"));
        when(repository.latestRevisionNumber(42L)).thenReturn(2L);
        when(repository.active(42L)).thenReturn(Optional.empty());
        when(repository.createDraft(42L, 17L, settings,
                request.changeReason(), 3L, null)).thenReturn(result);

        assertThat(service.createDraft(AUTHOR, request, "draft-1")).isSameAs(result);

        verify(databaseScope).applyWorker(42L);
        verify(idempotency).complete(AUTHOR, receipt("draft"), result);
        verify(audit).record(any());
    }

    @Test
    void failsClosedForNonCanonicalOrDuplicateTopicTokens() {
        Settings nonCanonical = new Settings(
                20, 10, 15, List.of("#Sec-Alert", "#Sec-Alert"), true, 10, true);
        Settings duplicates = new Settings(
                20, 10, 15, List.of("#sec-alert", "#sec-alert"), true, 10, true);

        assertThatThrownBy(() -> service.createDraft(
                AUTHOR,
                new DraftRequest(nonCanonical, "Protect tenant attention", "0"),
                "draft-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraft(
                AUTHOR,
                new DraftRequest(duplicates, "Protect tenant attention", "0"),
                "draft-2"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).createDraft(anyLong(), anyLong(), any(),
                any(), anyLong(), any());
    }

    @Test
    void preventsTheAuthorFromApprovingOrRejectingTheSameDraft() {
        UUID governanceId = UUID.randomUUID();
        Revision draft = revision(governanceId, "DRAFT", settings(true, true), 1, "1", 17L);
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt("decision"));
        when(repository.findForUpdate(42L, governanceId)).thenReturn(Optional.of(draft));
        DecisionRequest request = new DecisionRequest("1", "Independent review completed");

        assertThatThrownBy(() -> service.publish(AUTHOR, governanceId, request, "publish-1"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.reject(AUTHOR, governanceId, request, "reject-1"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.FORBIDDEN));
        verify(repository, never()).publish(anyLong(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void rejectsWhitespaceVariantReasonsAndIdempotencyKeysBeforePersistence() {
        DraftRequest whitespaceReason = new DraftRequest(
                settings(true, true), " Protect tenant attention ", "0");
        DraftRequest valid = new DraftRequest(
                settings(true, true), "Protect tenant attention", "0");

        assertThatThrownBy(() -> service.createDraft(AUTHOR, whitespaceReason, "draft-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraft(AUTHOR, valid, " draft-1 "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(idempotency, never()).begin(any(), any(), any(), any());
    }

    @Test
    void refusesToPublishWhenMandatoryOrFourEyesGuardrailsAreDisabled() {
        UUID governanceId = UUID.randomUUID();
        Revision draft = revision(governanceId, "DRAFT", settings(false, false), 1, "1", 17L);
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt("decision"));
        when(repository.findForUpdate(42L, governanceId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.publish(
                REVIEWER,
                governanceId,
                new DecisionRequest("1", "Independent review completed"),
                "publish-1"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.FORBIDDEN));
        verify(repository, never()).publish(anyLong(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void publishesOnlyAfterIndependentReviewAndCompletesTheReceipt() {
        UUID governanceId = UUID.randomUUID();
        Settings settings = settings(true, true);
        Revision draft = revision(governanceId, "DRAFT", settings, 1, "1", 17L);
        Revision published = revision(governanceId, "PUBLISHED", settings, 1, "2", 17L);
        Request receipt = receipt("decision");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(repository.findForUpdate(42L, governanceId)).thenReturn(Optional.of(draft));
        when(repository.publish(42L, governanceId, 18L, 1L,
                "Independent review completed")).thenReturn(true);
        when(repository.find(42L, governanceId)).thenReturn(Optional.of(published));

        assertThat(service.publish(
                REVIEWER,
                governanceId,
                new DecisionRequest("1", "Independent review completed"),
                "publish-1")).isSameAs(published);

        verify(idempotency).complete(REVIEWER, receipt, published);
        verify(audit).record(any());
        InOrder serialized = inOrder(governanceLock, repository);
        serialized.verify(governanceLock).lockTenant(42L);
        serialized.verify(repository).findForUpdate(42L, governanceId);
        serialized.verify(repository).publish(
                42L, governanceId, 18L, 1L, "Independent review completed");
    }

    private static NotificationRequestContext.Actor actor(long userId) {
        return new NotificationRequestContext.Actor(
                42L, userId, Set.of("TENANT_ADMIN"),
                Set.of("ADMIN.NOTIFICATION_POLICY:APPROVE"), false, "dwp-gateway");
    }

    private Settings settings(boolean mandatory, boolean independent) {
        return new Settings(
                20, 10, 15, List.of("#sec-soc-alert"), mandatory, 10, independent);
    }

    private Revision revision(
            UUID id, String state, Settings settings, long revision, String version, long creator) {
        return new Revision(
                id, state, settings, revision, version,
                "Protect tenant attention governance", creator, NOW,
                "PUBLISHED".equals(state) ? 18L : null,
                "PUBLISHED".equals(state) ? NOW : null,
                creator, NOW,
                "PUBLISHED".equals(state) ? "Independent review completed" : null,
                null);
    }

    private Request receipt(String operation) {
        return new Request("key", operation, "hash", null);
    }
}
