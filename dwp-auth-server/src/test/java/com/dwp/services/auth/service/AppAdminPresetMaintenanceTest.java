package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppAdminPresetMaintenanceTest {

    @Test
    void drainsFullExpiryBatchesUntilTheFirstPartialBatch() {
        AppAdminPresetService service = mock(AppAdminPresetService.class);
        when(service.expireDueAssignments(250)).thenReturn(250, 12);

        new AppAdminPresetMaintenance(service).expireTimeBoundPresets();

        verify(service, times(2)).expireDueAssignments(250);
    }
}
