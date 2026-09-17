package com.dwp.services.people.hr;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrServiceMailProposalOutcomeTest {

    private static final long TENANT_ID = 3L;
    private static final long ACTOR_ID = 17L;
    private static final long WORKER_ID = 41L;
    private static final UUID PERSON_ID = UUID.randomUUID();

    private final HrRepository repository = mock(HrRepository.class);
    private final HcmPopulationRepository populations = mock(HcmPopulationRepository.class);
    private final HcmPopulationScopeService scopes = mock(HcmPopulationScopeService.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final HrMailProposalOutcomeOperations outcomes =
            mock(HrMailProposalOutcomeOperations.class);
    private final HrService service = new HrService(
            repository, populations, scopes, audit, outcomes);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void failedOwnerPreflightPreventsTheLeaveInsert() {
        arrangeActor();
        HrDtos.CreateLeaveRequest request = request();
        HrMailProposalBinding binding = binding();
        org.mockito.Mockito.doThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT, "stale binding"))
                .when(outcomes).preflight(TENANT_ID, ACTOR_ID, binding, request);

        assertThatThrownBy(() -> service.createLeaveRequest(
                request, "corr-preflight", binding))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(repository, never()).createLeaveRequest(
                eq(TENANT_ID), eq(WORKER_ID), any(), eq(ACTOR_ID));
        verify(outcomes, never()).enqueueExecuted(anyLong(), anyLong(),
                any(), any(), any());
        verify(audit, never()).record(any(AuditEvent.class));
    }

    @Test
    void successfulLeaveCreationEnqueuesTheBoundOutcomeBeforeReturning() {
        arrangeActor();
        HrDtos.CreateLeaveRequest request = request();
        HrMailProposalBinding binding = binding();
        UUID requestId = UUID.randomUUID();
        HrDtos.LeaveRequest created = new HrDtos.LeaveRequest(
                requestId,
                request.planId(),
                "Annual leave",
                request.startAt(),
                request.endAt(),
                request.requestedMinutes(),
                "SUBMITTED",
                request.reason(),
                Instant.parse("2026-09-17T01:00:00Z"),
                null,
                null,
                null,
                0L);
        when(repository.createLeaveRequest(TENANT_ID, WORKER_ID, request, ACTOR_ID))
                .thenReturn(Optional.of(created));

        HrDtos.LeaveRequest result = service.createLeaveRequest(
                request, "corr-success", binding);

        assertThat(result).isEqualTo(created);
        var order = inOrder(outcomes, repository, audit);
        order.verify(outcomes).preflight(TENANT_ID, ACTOR_ID, binding, request);
        order.verify(repository).createLeaveRequest(TENANT_ID, WORKER_ID, request, ACTOR_ID);
        order.verify(outcomes).enqueueExecuted(
                TENANT_ID, ACTOR_ID, binding, requestId, "corr-success");
        order.verify(audit).record(any(AuditEvent.class));
    }

    private void arrangeActor() {
        PeopleRequestContext.set(
                ACTOR_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        when(repository.worker(TENANT_ID, PERSON_ID)).thenReturn(Optional.of(
                new HrRepository.WorkerIdentity(
                        WORKER_ID, PERSON_ID, "Minseo Kim", "ASSIGN-1",
                        "Engineer", "Platform", null, null, 0)));
        when(repository.workerSchedule(
                eq(TENANT_ID), eq(WORKER_ID), any(LocalDate.class)))
                .thenReturn(Optional.empty());
    }

    private HrDtos.CreateLeaveRequest request() {
        Instant start = Instant.parse("2026-10-05T00:00:00Z");
        return new HrDtos.CreateLeaveRequest(
                UUID.randomUUID(), start, start.plusSeconds(28_800), 480, "Annual leave");
    }

    private HrMailProposalBinding binding() {
        return new HrMailProposalBinding(UUID.randomUUID(), UUID.randomUUID(), 4L);
    }
}
