package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalRecoveryAuditorAssignmentWorkerTest {

    private final ApprovalRecoveryAuditorAssignmentRepository repository =
            mock(ApprovalRecoveryAuditorAssignmentRepository.class);
    private final ApprovalRecoveryAuditorResolver resolver =
            mock(ApprovalRecoveryAuditorResolver.class);
    private final ApprovalRecoveryAuditorAssignmentWorker worker =
            new ApprovalRecoveryAuditorAssignmentWorker(
                    repository, resolver, 25, 30, 3, 3600, 7200);

    @Test
    void persistsCompleteServerResolvedEvidence() {
        var target = target(1);
        var assignment = assignment(300);
        when(repository.claim(eq(25), anyString(), eq(30), eq(3), eq(3600L)))
                .thenReturn(List.of(target));
        when(resolver.resolve(
                42, target.outboxId(), 100, "RS_APPROVALS")).thenReturn(assignment);

        worker.assignPending();

        verify(repository).markAssigned(eq(target), anyString(), eq(assignment));
        verify(repository, never()).markUnavailable(
                eq(target), anyString(), eq(3), eq(3600L), eq(7200L), anyString());
    }

    @Test
    void authOutageKeepsTheAssignmentRetryableAndFailClosed() {
        var target = target(2);
        when(repository.claim(eq(25), anyString(), eq(30), eq(3), eq(3600L)))
                .thenReturn(List.of(target));
        when(resolver.resolve(42, target.outboxId(), 100, "RS_APPROVALS")).thenThrow(
                new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "no candidate"));

        worker.assignPending();

        verify(repository).markUnavailable(
                eq(target), anyString(), eq(3), eq(3600L), eq(7200L),
                eq("no candidate"));
        verify(repository, never()).markAssigned(
                eq(target), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAnOriginatorSelectedByAnyResolverImplementation() {
        var target = target(1);
        when(repository.claim(eq(25), anyString(), eq(30), eq(3), eq(3600L)))
                .thenReturn(List.of(target));
        when(resolver.resolve(
                42, target.outboxId(), 100, "RS_APPROVALS")).thenReturn(assignment(100));

        worker.assignPending();

        verify(repository).markUnavailable(
                eq(target), anyString(), eq(3), eq(3600L), eq(7200L), anyString());
        verify(repository, never()).markAssigned(
                eq(target), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void passesTheTerminalAttemptBudgetToTheRepository() {
        var target = target(3);
        when(repository.claim(eq(25), anyString(), eq(30), eq(3), eq(3600L)))
                .thenReturn(List.of(target));
        when(resolver.resolve(42, target.outboxId(), 100, "RS_APPROVALS")).thenThrow(
                new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "still unavailable"));

        worker.assignPending();

        verify(repository).markUnavailable(
                eq(target), anyString(), eq(3), eq(3600L), eq(7200L),
                eq("still unavailable"));
    }

    @Test
    void clampsAttemptBudgetsAtTheSafeRuntimeBounds() {
        var minimum = new ApprovalRecoveryAuditorAssignmentWorker(
                repository, resolver, 25, 30, 0, 0, 0);
        var maximum = new ApprovalRecoveryAuditorAssignmentWorker(
                repository, resolver, 25, 30, 101, 999999, 99999999);

        minimum.assignPending();
        maximum.assignPending();

        verify(repository).claim(eq(25), anyString(), eq(30), eq(1), eq(3600L));
        verify(repository).claim(eq(25), anyString(), eq(30), eq(100), eq(604800L));
    }

    private ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget target(
            int attemptCount) {
        return new ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                42, 100, attemptCount, 1);
    }

    private ApprovalRecoveryAuditorResolver.Assignment assignment(long selectedUserId) {
        return new ApprovalRecoveryAuditorResolver.Assignment(
                selectedUserId, "RS_APPROVALS", "auth-revision-17");
    }
}
