package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationOutboxRelayService.RelayResult;
import com.dwp.services.notification.operations.NotificationRetentionRepository.PurgeResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationMaintenanceSchedulersTest {

    @Test
    void retentionProcessesEveryTenantAcrossKeysetPages() {
        NotificationMaintenanceTenantRepository tenants = mock(NotificationMaintenanceTenantRepository.class);
        NotificationRetentionService retention = mock(NotificationRetentionService.class);
        List<Long> firstPage = LongStream.rangeClosed(1, 500).boxed().toList();
        when(tenants.activeTenantIdsAfter(Long.MIN_VALUE, 500)).thenReturn(firstPage);
        when(tenants.activeTenantIdsAfter(500, 500)).thenReturn(List.of(501L));
        when(retention.purgeTenant(anyLong(), any(Instant.class)))
                .thenReturn(new PurgeResult(0, 0, List.of()));

        new NotificationRetentionScheduler(tenants, retention).run();

        verify(retention, times(501)).purgeTenant(anyLong(), any(Instant.class));
        verify(tenants).activeTenantIdsAfter(Long.MIN_VALUE, 500);
        verify(tenants).activeTenantIdsAfter(500, 500);
    }

    @Test
    void outboxProcessesEveryTenantAndIsolatesAnIndividualFailure() {
        NotificationMaintenanceTenantRepository tenants = mock(NotificationMaintenanceTenantRepository.class);
        NotificationOutboxRelayService relay = mock(NotificationOutboxRelayService.class);
        when(tenants.activeTenantIdsAfter(Long.MIN_VALUE, 500)).thenReturn(List.of(11L, 12L));
        when(relay.relayTenant(anyLong(), any(Instant.class)))
                .thenReturn(new RelayResult(0, 0, 0, 0, 0));
        when(relay.relayTenant(org.mockito.ArgumentMatchers.eq(11L), any(Instant.class)))
                .thenThrow(new IllegalStateException("tenant unavailable"));

        new NotificationOutboxRelayScheduler(tenants, relay).run();

        verify(relay).relayTenant(org.mockito.ArgumentMatchers.eq(11L), any(Instant.class));
        verify(relay).relayTenant(org.mockito.ArgumentMatchers.eq(12L), any(Instant.class));
    }

    @Test
    void reconciliationProcessesEveryTenantAndIsolatesAnIndividualFailure() {
        NotificationMaintenanceTenantRepository tenants =
                mock(NotificationMaintenanceTenantRepository.class);
        NotificationCounterReconciliationService reconciliation =
                mock(NotificationCounterReconciliationService.class);
        when(tenants.activeTenantIdsAfter(Long.MIN_VALUE, 500)).thenReturn(List.of(11L, 12L));
        when(reconciliation.reconcileTenant(anyLong(), any(Instant.class)))
                .thenReturn(new NotificationCounterReconciliationService.ReconciliationResult(
                        0, 0, false));
        when(reconciliation.reconcileTenant(
                org.mockito.ArgumentMatchers.eq(11L), any(Instant.class)))
                .thenThrow(new IllegalStateException("tenant unavailable"));

        new NotificationCounterReconciliationScheduler(tenants, reconciliation).run();

        verify(reconciliation).reconcileTenant(
                org.mockito.ArgumentMatchers.eq(11L), any(Instant.class));
        verify(reconciliation).reconcileTenant(
                org.mockito.ArgumentMatchers.eq(12L), any(Instant.class));
    }
}
