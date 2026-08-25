package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationCounterReconciliationRepository.CounterRow;
import com.dwp.services.notification.operations.NotificationCounterReconciliationRepository.ProjectionCounterRow;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationCounterReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");

    @Test
    void derivesVisibleCountersWithoutLosingIndependentTriageDimensions() {
        var expectation = NotificationCounterReconciliationService.expectation(List.of(
                row("ACTIVE", null, null, true, "URGENT", 4),
                row("ACTIVE", null, NOW.plusSeconds(60), true, "URGENT", 5),
                row("ACTIVE", NOW.minusSeconds(1), null, true, "NORMAL", 6),
                row("DONE", null, null, true, "URGENT", 7),
                row("ACTIVE", null, NOW.minusSeconds(1), false, "NORMAL", 8)), NOW);

        assertThat(expectation.counter()).isEqualTo(new CounterRow(2, 1, 1, 8));
        assertThat(expectation.maximumProjectionVersion()).isEqualTo(8);
    }

    @Test
    void repairsOnlyAConfirmedDriftAfterLockingTheDurableProjection() {
        NotificationDatabaseScope scope = mock(NotificationDatabaseScope.class);
        NotificationCounterReconciliationRepository repository =
                mock(NotificationCounterReconciliationRepository.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        NotificationCounterReconciliationService service =
                new NotificationCounterReconciliationService(scope, repository, meters, 100);
        ProjectionCounterRow projection = row("ACTIVE", null, null, true, "HIGH", 9);
        when(repository.driftedUserIds(7, NOW, 100)).thenReturn(List.of(91L));
        when(repository.lockProjectionRows(7, 91)).thenReturn(List.of(projection));
        when(repository.lockCounter(7, 91)).thenReturn(new CounterRow(0, 0, 0, 4));

        var result = service.reconcileTenant(7, NOW);

        assertThat(result).isEqualTo(
                new NotificationCounterReconciliationService.ReconciliationResult(1, 1, false));
        verify(scope).applyWorker(7);
        verify(repository).ensureCounter(7, 91);
        verify(repository).repair(7, 91, new CounterRow(1, 1, 0, 9), 9);
        assertThat(meters.get("dwp.notification.counter.drift.detected").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("dwp.notification.counter.drift.repaired").counter().count())
                .isEqualTo(1);
    }

    @Test
    void leavesAConcurrentlyCorrectedCounterUntouched() {
        NotificationCounterReconciliationRepository repository =
                mock(NotificationCounterReconciliationRepository.class);
        NotificationCounterReconciliationService service =
                new NotificationCounterReconciliationService(
                        mock(NotificationDatabaseScope.class),
                        repository,
                        new SimpleMeterRegistry(),
                        10);
        when(repository.driftedUserIds(7, NOW, 10)).thenReturn(List.of(91L));
        when(repository.lockProjectionRows(7, 91)).thenReturn(List.of(
                row("ACTIVE", null, null, false, "NORMAL", 3)));
        when(repository.lockCounter(7, 91)).thenReturn(new CounterRow(1, 0, 0, 3));

        var result = service.reconcileTenant(7, NOW);

        assertThat(result.repaired()).isZero();
        verify(repository, never()).repair(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static ProjectionCounterRow row(
            String state,
            Instant readAt,
            Instant snoozedUntil,
            boolean actionable,
            String priority,
            long version) {
        return new ProjectionCounterRow(
                state, readAt, snoozedUntil, actionable, priority, version);
    }
}
