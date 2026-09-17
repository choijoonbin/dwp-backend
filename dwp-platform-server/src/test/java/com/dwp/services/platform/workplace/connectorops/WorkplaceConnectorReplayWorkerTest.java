package com.dwp.services.platform.workplace.connectorops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ConnectorKind;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsRepository.ReplayJobRow;
import static org.mockito.Mockito.*;

class WorkplaceConnectorReplayWorkerTest {
    @Test
    void dispatchesQueuedJobsThroughTheOptimisticServiceBoundary() {
        WorkplaceConnectorOpsRepository repository = mock(WorkplaceConnectorOpsRepository.class);
        WorkplaceConnectorReplayCoordinator coordinator = mock(WorkplaceConnectorReplayCoordinator.class);
        ReplayJobRow row = mock(ReplayJobRow.class);
        UUID jobId = UUID.randomUUID();
        when(row.tenantId()).thenReturn(77L);
        when(row.kind()).thenReturn(ConnectorKind.CALENDAR);
        when(row.jobId()).thenReturn(jobId);
        when(repository.queuedReplays(20)).thenReturn(List.of(row));

        when(repository.reconciliationCandidates(20)).thenReturn(List.of());
        new WorkplaceConnectorReplayWorker(repository, coordinator, true, 20).dispatchQueued();

        verify(coordinator).dispatch(77L, ConnectorKind.CALENDAR, jobId);
    }

    @Test
    void disabledWorkerDoesNotReadTheGlobalQueue() {
        WorkplaceConnectorOpsRepository repository = mock(WorkplaceConnectorOpsRepository.class);
        WorkplaceConnectorReplayCoordinator coordinator = mock(WorkplaceConnectorReplayCoordinator.class);

        new WorkplaceConnectorReplayWorker(repository, coordinator, false, 20).dispatchQueued();

        verifyNoInteractions(repository, coordinator);
    }

    @Test
    void reconcilesRunningAndUnknownJobsThroughProviderLookup() {
        WorkplaceConnectorOpsRepository repository = mock(WorkplaceConnectorOpsRepository.class);
        WorkplaceConnectorReplayCoordinator coordinator = mock(WorkplaceConnectorReplayCoordinator.class);
        ReplayJobRow row = mock(ReplayJobRow.class);
        UUID jobId = UUID.randomUUID();
        when(row.tenantId()).thenReturn(88L);
        when(row.kind()).thenReturn(ConnectorKind.CALENDAR);
        when(row.jobId()).thenReturn(jobId);
        when(repository.queuedReplays(20)).thenReturn(List.of());
        when(repository.reconciliationCandidates(20)).thenReturn(List.of(row));

        new WorkplaceConnectorReplayWorker(repository, coordinator, true, 20).dispatchQueued();

        verify(coordinator).reconcile(88L, ConnectorKind.CALENDAR, jobId);
        verify(coordinator, never()).dispatch(anyLong(), any(), any());
    }
}
