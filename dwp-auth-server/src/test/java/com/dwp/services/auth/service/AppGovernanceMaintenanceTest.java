package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppGovernanceMaintenanceTest {

    @Test
    void continuesUntilTheExpiryBatchIsNotFull() {
        AppGovernanceService service = mock(AppGovernanceService.class);
        when(service.expireDueAssignments(250)).thenReturn(250, 4);

        new AppGovernanceMaintenance(service).expireTimeBoundResponsibilities();

        verify(service, times(2)).expireDueAssignments(250);
    }
}
