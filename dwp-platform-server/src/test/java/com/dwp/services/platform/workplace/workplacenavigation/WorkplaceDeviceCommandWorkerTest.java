package com.dwp.services.platform.workplace.workplacenavigation;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.Mockito.*;

class WorkplaceDeviceCommandWorkerTest {
    @Test
    void scheduledBatchAlternatesNewDispatchAndReconciliationWithoutStarvation() {
        WorkplaceDeviceService service = mock(WorkplaceDeviceService.class);
        WorkplaceDeviceCommandWorker worker = spy(new WorkplaceDeviceCommandWorker(
                service, Optional.empty(), true, 4, Duration.ofMinutes(2)));
        doReturn(true).when(worker).dispatchNext();
        doReturn(true).when(worker).reconcileNext();

        worker.scheduledDispatch();

        InOrder order = inOrder(worker);
        order.verify(worker).dispatchNext();
        order.verify(worker).reconcileNext();
        order.verify(worker).dispatchNext();
        order.verify(worker).reconcileNext();
        verify(worker, times(2)).dispatchNext();
        verify(worker, times(2)).reconcileNext();
        verify(service).recoverStaleDispatches(Duration.ofMinutes(2));
    }
}
