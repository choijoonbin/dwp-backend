package com.dwp.services.platform.workplace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkplaceExperienceCollaborationMaintenanceTest {

    @Test
    void drainsExpiredPlansUntilTheFirstPartialBatch() {
        WorkplaceExperienceCollaborationRepository repository =
                mock(WorkplaceExperienceCollaborationRepository.class);
        when(repository.deleteExpiredWorkPlans(100)).thenReturn(100, 100, 2);
        when(repository.retentionBacklog()).thenReturn(
                new WorkplaceExperienceCollaborationRepository.RetentionBacklog(0, null));
        WorkplaceExperienceCollaborationMaintenance maintenance =
                new WorkplaceExperienceCollaborationMaintenance(
                        repository,
                        new WorkplaceMaintenanceMetrics(new SimpleMeterRegistry()),
                        Clock.fixed(
                                Instant.parse("2026-10-28T00:00:00Z"),
                                ZoneOffset.UTC),
                        true, 100, 10, 30_000);

        maintenance.purgeExpiredPlans();

        verify(repository, times(3)).deleteExpiredWorkPlans(100);
        verify(repository).retentionBacklog();
    }
}
