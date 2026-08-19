package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkplaceMediaCleanupMaintenanceTest {

    @Test
    void enabledMaintenanceReconcilesABoundedBatch() {
        WorkplaceMediaCleanupRepository repository =
                mock(WorkplaceMediaCleanupRepository.class);
        WorkplaceMediaCleanupMaintenance maintenance =
                new WorkplaceMediaCleanupMaintenance(repository, true, 100, 120);

        maintenance.reconcile();

        verify(repository).reconcile(eq(100), any());
    }

    @Test
    void disabledMaintenanceDoesNotTouchTheLedger() {
        WorkplaceMediaCleanupRepository repository =
                mock(WorkplaceMediaCleanupRepository.class);
        WorkplaceMediaCleanupMaintenance maintenance =
                new WorkplaceMediaCleanupMaintenance(repository, false, 100, 120);

        maintenance.reconcile();

        verify(repository, never()).reconcile(eq(100), any());
    }
}
