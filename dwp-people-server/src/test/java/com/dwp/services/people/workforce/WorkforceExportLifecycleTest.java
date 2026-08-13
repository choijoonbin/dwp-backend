package com.dwp.services.people.workforce;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkforceExportLifecycleTest {

    @Test
    void cancelsQueuedWorkImmediatelyButCoordinatesARunningAttempt() {
        assertThat(WorkforceExportLifecycle.cancellationTarget("QUEUED"))
                .isEqualTo("CANCELLED");
        assertThat(WorkforceExportLifecycle.cancellationTarget("RUNNING"))
                .isEqualTo("CANCEL_REQUESTED");
        assertThatThrownBy(() -> WorkforceExportLifecycle.cancellationTarget("COMPLETED"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void retriesOnlyWithinTheConfiguredBudget() {
        assertThat(WorkforceExportLifecycle.failureTarget("RUNNING", 2, 5))
                .isEqualTo("RETRY_WAIT");
        assertThat(WorkforceExportLifecycle.failureTarget("RUNNING", 5, 5))
                .isEqualTo("FAILED");
        assertThat(WorkforceExportLifecycle.failureTarget("CANCEL_REQUESTED", 2, 5))
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> WorkforceExportLifecycle.requireRetryable(
                "FAILED", false, true, 0, 1))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkforceExportLifecycle.requireRetryable(
                "FAILED", true, false, 1, 1))
                .isInstanceOf(BaseException.class);
        WorkforceExportLifecycle.requireRetryable("FAILED", true, false, 0, 1);
    }
}
