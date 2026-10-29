package com.dwp.services.platform.workplace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkplaceExperienceFacilitiesMaintenanceTest {

    @Test
    void configuredJobDrainsBoundedBatchesAndDisabledJobDoesNotRun() {
        WorkplaceExperienceFacilitiesRetention retention =
                mock(WorkplaceExperienceFacilitiesRetention.class);
        when(retention.purge(500)).thenReturn(
                new WorkplaceExperienceFacilitiesRetention.Purged(500, 500),
                new WorkplaceExperienceFacilitiesRetention.Purged(1, 2));
        when(retention.retentionBacklog()).thenReturn(
                new WorkplaceExperienceFacilitiesRetention.RetentionBacklog(
                        0, null, 0, null));
        WorkplaceMaintenanceMetrics metrics =
                new WorkplaceMaintenanceMetrics(new SimpleMeterRegistry());
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);

        new WorkplaceExperienceFacilitiesMaintenance(
                retention, metrics, clock, true, 500, 10, 30_000)
                .purgeExpiredRecords();

        verify(retention, times(2)).purge(500);
        verify(retention).retentionBacklog();
        clearInvocations(retention);

        new WorkplaceExperienceFacilitiesMaintenance(
                retention, metrics, clock, false, 500, 10, 30_000)
                .purgeExpiredRecords();
        verifyNoInteractions(retention);
    }
}
