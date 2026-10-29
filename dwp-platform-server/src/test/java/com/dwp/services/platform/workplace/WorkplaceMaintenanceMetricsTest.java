package com.dwp.services.platform.workplace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceMaintenanceMetricsTest {

    @Test
    void exposesBacklogAndOldestEligibleAgeWithoutTenantCardinality() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkplaceMaintenanceMetrics metrics = new WorkplaceMaintenanceMetrics(registry);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-28T00:00:00Z");

        metrics.booking(new WorkplaceBookingRepository.LifecycleBacklog(
                7, now.minusMinutes(5), 2, now.minusSeconds(30)), now);

        assertThat(registry.get(WorkplaceMaintenanceMetrics.BACKLOG)
                .tags("job", "booking-lifecycle", "kind", "no-show")
                .gauge().value()).isEqualTo(7.0);
        assertThat(registry.get(WorkplaceMaintenanceMetrics.OLDEST_EXPIRED_AGE)
                .tags("job", "booking-lifecycle", "kind", "no-show")
                .gauge().value()).isEqualTo(300.0);
        assertThat(registry.get(WorkplaceMaintenanceMetrics.OLDEST_EXPIRED_AGE)
                .tags("job", "booking-lifecycle", "kind", "completion")
                .gauge().value()).isEqualTo(30.0);
    }
}
