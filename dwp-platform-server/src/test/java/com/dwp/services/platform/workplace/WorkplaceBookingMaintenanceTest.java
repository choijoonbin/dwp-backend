package com.dwp.services.platform.workplace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkplaceBookingMaintenanceTest {

    @Test
    void drainsIndependentLifecycleBatchesAndPublishesBacklog() {
        WorkplaceService service = mock(WorkplaceService.class);
        when(service.maintainBookingLifecycle(100, 10)).thenReturn(
                new WorkplaceService.LifecycleBatch(true, 2, 1),
                new WorkplaceService.LifecycleBatch(true, 0, 0));
        when(service.bookingLifecycleBacklog()).thenReturn(
                new WorkplaceBookingRepository.LifecycleBacklog(0, null, 0, null));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkplaceBookingMaintenance maintenance = new WorkplaceBookingMaintenance(
                service, new WorkplaceMaintenanceMetrics(registry), fixedClock(),
                100, 10, 5, 20_000);

        maintenance.maintainBookingLifecycle();

        verify(service, times(2)).maintainBookingLifecycle(100, 10);
        verify(service).bookingLifecycleBacklog();
        assertThat(registry.get(WorkplaceMaintenanceMetrics.PROCESSED)
                .tags("job", "booking-lifecycle", "kind", "no-show")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get(WorkplaceMaintenanceMetrics.RUNS)
                .tags("job", "booking-lifecycle", "outcome", "drained")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void batchLimitStopsAContinuouslyBusyLifecycle() {
        WorkplaceService service = mock(WorkplaceService.class);
        when(service.maintainBookingLifecycle(100, 10))
                .thenReturn(new WorkplaceService.LifecycleBatch(true, 10, 10));
        when(service.bookingLifecycleBacklog()).thenReturn(
                new WorkplaceBookingRepository.LifecycleBacklog(
                        4, java.time.OffsetDateTime.parse("2026-10-27T00:00:00Z"),
                        3, java.time.OffsetDateTime.parse("2026-10-27T01:00:00Z")));
        WorkplaceBookingMaintenance maintenance = new WorkplaceBookingMaintenance(
                service,
                new WorkplaceMaintenanceMetrics(new SimpleMeterRegistry()),
                fixedClock(), 100, 10, 3, 20_000);

        maintenance.maintainBookingLifecycle();

        verify(service, times(3)).maintainBookingLifecycle(100, 10);
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);
    }
}
