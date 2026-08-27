package com.dwp.services.provider.support;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ProviderSupportAuthorityReconciliationWorkerTest {

    private final ProviderSupportAuthorityReconciliationService service =
            mock(ProviderSupportAuthorityReconciliationService.class);

    @Test
    void emitsAPulseWhenEnabled() {
        var worker = new ProviderSupportAuthorityReconciliationWorker(service, true);

        worker.pollSafely();

        verify(service).reconcile();
    }

    @Test
    void emergencyDisableSuppressesThePulse() {
        var worker = new ProviderSupportAuthorityReconciliationWorker(service, false);

        worker.pollSafely();

        verify(service, never()).reconcile();
    }

    @Test
    void retriesOnTheNextScheduleAfterAFailedPulse() {
        doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(service).reconcile();
        var worker = new ProviderSupportAuthorityReconciliationWorker(service, true);

        worker.pollSafely();
        worker.pollSafely();

        verify(service, times(2)).reconcile();
    }

    @Test
    void defaultsToAFiveSecondConfigurableDelay() throws NoSuchMethodException {
        Scheduled schedule = ProviderSupportAuthorityReconciliationWorker.class
                .getMethod("pollSafely")
                .getAnnotation(Scheduled.class);

        assertThat(schedule.fixedDelayString()).isEqualTo(
                "${dwp.provider.support-authority-reconciliation.delay-ms:5000}");
    }
}
