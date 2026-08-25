package com.dwp.services.notification.operations;

import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import com.dwp.services.notification.operations.NotificationRetentionRepository.PurgeResult;
import com.dwp.services.notification.realtime.NotificationChangeCause;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRetentionServiceTest {

    @Test
    void leavesExpiryUnsetUntilAReleaseRetentionPeriodIsApproved() {
        NotificationRetentionRepository repository = mock(NotificationRetentionRepository.class);
        NotificationRetentionService service = service(repository, Duration.ZERO);

        service.applyDefaultExpiry(7, UUID.randomUUID(), Instant.now());

        verify(repository, never()).extendExpiry(any(Long.class), any(), any());
    }

    @Test
    void appliesTheConfiguredDefaultExpiryInsideMaterialization() {
        NotificationRetentionRepository repository = mock(NotificationRetentionRepository.class);
        NotificationRetentionService service = service(repository, Duration.ofDays(30));
        UUID notificationId = UUID.randomUUID();
        Instant activityAt = Instant.parse("2026-08-20T09:00:00Z");

        service.applyDefaultExpiry(7, notificationId, activityAt);

        verify(repository).extendExpiry(
                7, notificationId, Instant.parse("2026-09-19T09:00:00Z"));
    }

    @Test
    void publishesAResetHintAfterPurgeRebuildsTheUserWatermark() {
        NotificationDatabaseScope scope = mock(NotificationDatabaseScope.class);
        NotificationRetentionRepository repository = mock(NotificationRetentionRepository.class);
        NotificationChangePublisher publisher = mock(NotificationChangePublisher.class);
        NotificationRetentionService service = new NotificationRetentionService(
                scope, repository, publisher,
                Duration.ZERO, Duration.ofDays(7), 100);
        Instant now = Instant.parse("2026-08-20T09:00:00Z");
        ChangeSignal signal = new ChangeSignal(7, 91, 22, UUID.randomUUID());
        PurgeResult result = new PurgeResult(1, 1, List.of(signal));
        when(repository.purgeExpired(7, now, 100)).thenReturn(result);

        service.purgeTenant(7, now);

        verify(scope).applyWorker(7);
        verify(repository).cleanupAdmissionHistory(
                7, now.minus(Duration.ofDays(7)), now.minus(Duration.ofDays(2)), 100);
        verify(repository).cleanupBulkUndoReceipts(7, now, 100);
        verify(publisher).publishAfterCommit(
                eq(List.of(signal)), eq(NotificationChangeCause.SYSTEM_RECONCILIATION));
    }

    private NotificationRetentionService service(
            NotificationRetentionRepository repository,
            Duration defaultTtl) {
        return new NotificationRetentionService(
                mock(NotificationDatabaseScope.class),
                repository,
                mock(NotificationChangePublisher.class),
                defaultTtl,
                Duration.ofDays(7),
                100);
    }
}
