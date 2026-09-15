package com.dwp.services.approval.documentretention.management;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalRetentionManagedScheduleTest {
    @Test void drainsOnlyTheConfiguredBoundAndStopsWhenNoLeaseIsAvailable() {
        var worker=mock(ApprovalRetentionManagedWorker.class);
        when(worker.configuredDependencies()).thenReturn(Map.of("authority",true));
        when(worker.runOne()).thenReturn(true,true,false);
        var schedule=new ApprovalRetentionManagedSchedule(worker,10);
        assertThat(schedule.runCycle()).isEqualTo(2);
        verify(worker,times(3)).runOne();
    }

    @Test void runtimeFailureStopsTheCycleWithoutBeingRelabeledAsSuccess() {
        var worker=mock(ApprovalRetentionManagedWorker.class);
        when(worker.configuredDependencies()).thenReturn(Map.of("authority",true));
        when(worker.runOne()).thenReturn(true).thenThrow(new IllegalStateException("durable unknown"));
        var schedule=new ApprovalRetentionManagedSchedule(worker,10);
        assertThat(schedule.runCycle()).isEqualTo(1);
        verify(worker,times(2)).runOne();
    }

    @Test void rejectsUnboundedBatchConfiguration() {
        var worker=mock(ApprovalRetentionManagedWorker.class);
        assertThatThrownBy(()->new ApprovalRetentionManagedSchedule(worker,0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ApprovalRetentionManagedSchedule(worker,101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
