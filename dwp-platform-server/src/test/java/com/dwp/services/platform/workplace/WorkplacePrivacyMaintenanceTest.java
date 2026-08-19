package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class WorkplacePrivacyMaintenanceTest {

    @Test
    void enabledMaintenanceAnonymizesOneBoundedBatch() {
        WorkplacePrivacyRepository repository = mock(WorkplacePrivacyRepository.class);
        WorkplacePrivacyMaintenance maintenance =
                new WorkplacePrivacyMaintenance(repository, true, 250, 2555);

        maintenance.anonymizeExpiredBookings();

        verify(repository).anonymizeExpired(250);
        verify(repository).anonymizeExpiredReleaseWindows(250);
        verify(repository).purgeExpiredAuditReplicas(eq(250), any());
    }

    @Test
    void disabledMaintenanceLeavesBookingsUntouched() {
        WorkplacePrivacyRepository repository = mock(WorkplacePrivacyRepository.class);
        WorkplacePrivacyMaintenance maintenance =
                new WorkplacePrivacyMaintenance(repository, false, 250, 2555);

        maintenance.anonymizeExpiredBookings();

        verify(repository, never()).anonymizeExpired(250);
        verify(repository, never()).anonymizeExpiredReleaseWindows(250);
        verify(repository, never()).purgeExpiredAuditReplicas(eq(250), any());
    }
}
