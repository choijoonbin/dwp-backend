package com.dwp.services.people.hr;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrServiceTest {

    private static final long TENANT_ID = 3L;
    private static final long USER_ID = 17L;
    private static final long WORKER_ID = 41L;
    private static final UUID PERSON_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private final HrRepository repository = mock(HrRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final HrService service = new HrService(repository, audit);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void requesterCanWithdrawSubmittedLeaveAndRestorePendingBalance() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        stubWorker(WORKER_ID);
        HrRepository.LeaveRequestTarget target = target(WORKER_ID, "SUBMITTED", 4L);
        when(repository.leaveRequestTarget(TENANT_ID, REQUEST_ID)).thenReturn(Optional.of(target));
        when(repository.withdrawLeaveRequest(
                TENANT_ID, REQUEST_ID, target, "Plans changed", USER_ID)).thenReturn(true);
        when(repository.leaveBalances(TENANT_ID, WORKER_ID)).thenReturn(List.of());
        when(repository.leaveRequests(TENANT_ID, WORKER_ID)).thenReturn(List.of());

        HrDtos.AbsenceWorkspace workspace = service.withdrawLeaveRequest(
                REQUEST_ID, new HrDtos.WithdrawLeaveRequest("Plans changed", 4L), "corr-1");

        assertThat(workspace.requests()).isEmpty();
        assertThat(workspace.teamCalendar()).isEmpty();
        verify(repository).withdrawLeaveRequest(
                TENANT_ID, REQUEST_ID, target, "Plans changed", USER_ID);
        verify(audit).record(any(AuditEvent.class));
    }

    @Test
    void requesterCannotWithdrawAnotherWorkersLeave() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        stubWorker(WORKER_ID);
        HrRepository.LeaveRequestTarget target = target(99L, "SUBMITTED", 2L);
        when(repository.leaveRequestTarget(TENANT_ID, REQUEST_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.withdrawLeaveRequest(
                REQUEST_ID, new HrDtos.WithdrawLeaveRequest("Wrong request", 2L), "corr-2"))
                .isInstanceOf(BaseException.class);

        verify(repository, never()).withdrawLeaveRequest(
                TENANT_ID, REQUEST_ID, target, "Wrong request", USER_ID);
        verify(audit, never()).record(any(AuditEvent.class));
    }

    private void stubWorker(long workerId) {
        when(repository.worker(TENANT_ID, PERSON_ID)).thenReturn(Optional.of(
                new HrRepository.WorkerIdentity(
                        workerId, PERSON_ID, "Minseo Kim", "ASSIGN-1",
                        "Network Operations Lead", "Network Operations",
                        null, null, 0)));
    }

    private HrRepository.LeaveRequestTarget target(long workerId, String status, long version) {
        return new HrRepository.LeaveRequestTarget(
                71L, workerId, 12L, 480, status, version,
                PERSON_ID, "Minseo Kim", "Network Operations Lead", "Annual leave");
    }
}
