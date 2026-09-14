package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class WorkplaceExperienceFacilitiesMaintenanceTest {
    @Test void configuredJobRunsNativeBoundedPurgeAndDisabledJobDoesNotRun() {
        var retention=mock(WorkplaceExperienceFacilitiesRetention.class);
        when(retention.purge(500)).thenReturn(new WorkplaceExperienceFacilitiesRetention.Purged(1,2));
        new WorkplaceExperienceFacilitiesMaintenance(retention,true,500).purgeExpiredRecords();
        verify(retention).purge(500);clearInvocations(retention);
        new WorkplaceExperienceFacilitiesMaintenance(retention,false,500).purgeExpiredRecords();verifyNoInteractions(retention);
    }
}
