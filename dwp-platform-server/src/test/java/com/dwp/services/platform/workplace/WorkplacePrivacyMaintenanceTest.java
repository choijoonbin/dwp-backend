package com.dwp.services.platform.workplace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkplacePrivacyMaintenanceTest {

    @Test
    void enabledMaintenanceDrainsMultipleBoundedBatches() {
        WorkplacePrivacyRepository repository = mock(WorkplacePrivacyRepository.class);
        when(repository.anonymizeExpired(250)).thenReturn(250, 10);
        when(repository.anonymizeExpiredReleaseWindows(250)).thenReturn(5);
        when(repository.purgeExpiredAuditReplicas(eq(250), any())).thenReturn(250, 0);
        when(repository.retentionBacklog(any(), eq(2555)))
                .thenReturn(new WorkplacePrivacyRepository.RetentionBacklog(
                        0, null, 0, null, 0, null));
        WorkplaceMaintenanceMetrics metrics =
                new WorkplaceMaintenanceMetrics(new SimpleMeterRegistry());
        WorkplacePrivacyMaintenance maintenance =
                new WorkplacePrivacyMaintenance(
                        repository, metrics, fixedClock(), true, 250, 2555, 10, 30_000);

        maintenance.anonymizeExpiredBookings();

        verify(repository, times(2)).anonymizeExpired(250);
        verify(repository).anonymizeExpiredReleaseWindows(250);
        verify(repository, times(2)).purgeExpiredAuditReplicas(eq(250), any());
    }

    @Test
    void disabledMaintenanceLeavesBookingsUntouched() {
        WorkplacePrivacyRepository repository = mock(WorkplacePrivacyRepository.class);
        WorkplaceMaintenanceMetrics metrics =
                new WorkplaceMaintenanceMetrics(new SimpleMeterRegistry());
        WorkplacePrivacyMaintenance maintenance =
                new WorkplacePrivacyMaintenance(
                        repository, metrics, fixedClock(), false, 250, 2555, 10, 30_000);

        maintenance.anonymizeExpiredBookings();

        verify(repository, never()).anonymizeExpired(250);
        verify(repository, never()).anonymizeExpiredReleaseWindows(250);
        verify(repository, never()).purgeExpiredAuditReplicas(eq(250), any());
    }

    @Test
    void runStopsAtGlobalBatchLimitEvenWhenEveryStoreStaysFull() {
        WorkplacePrivacyRepository repository = mock(WorkplacePrivacyRepository.class);
        when(repository.anonymizeExpired(100)).thenReturn(100);
        when(repository.anonymizeExpiredReleaseWindows(100)).thenReturn(100);
        when(repository.purgeExpiredAuditReplicas(eq(100), any())).thenReturn(100);
        when(repository.retentionBacklog(any(), eq(2555)))
                .thenReturn(new WorkplacePrivacyRepository.RetentionBacklog(
                        12, java.time.OffsetDateTime.now().minusDays(1),
                        3, java.time.OffsetDateTime.now().minusHours(2),
                        4, java.time.OffsetDateTime.now().minusHours(1)));
        WorkplacePrivacyMaintenance maintenance = new WorkplacePrivacyMaintenance(
                repository,
                new WorkplaceMaintenanceMetrics(new SimpleMeterRegistry()),
                fixedClock(), true, 100, 2555, 4, 30_000);

        maintenance.anonymizeExpiredBookings();

        verify(repository, times(2)).anonymizeExpired(100);
        verify(repository).anonymizeExpiredReleaseWindows(100);
        verify(repository).purgeExpiredAuditReplicas(eq(100), any());
    }

    @Test
    void configurationMustBudgetAtLeastOneBatchForEveryPrivacyStore() {
        assertThatThrownBy(() -> new WorkplacePrivacyMaintenance(
                mock(WorkplacePrivacyRepository.class),
                new WorkplaceMaintenanceMetrics(new SimpleMeterRegistry()),
                fixedClock(), true, 100, 2555, 2, 30_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 3");
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);
    }
}
