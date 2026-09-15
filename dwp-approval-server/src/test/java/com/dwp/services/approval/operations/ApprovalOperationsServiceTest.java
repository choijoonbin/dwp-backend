package com.dwp.services.approval.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalOperationsServiceTest {
    private final ApprovalOperationsRepository repository = mock(ApprovalOperationsRepository.class);
    private final ApprovalOperationsAuthority authority = mock(ApprovalOperationsAuthority.class);
    private final ApprovalOperationsAudit audit = mock(ApprovalOperationsAudit.class);
    private final ApprovalOperationsService service = new ApprovalOperationsService(
            repository, authority, audit,
            Clock.fixed(Instant.parse("2026-09-14T01:00:00Z"), ZoneOffset.UTC));

    @Test
    void rejectsMoreThanFiftyDeliveryTargetsBeforeAuthorityOrPersistence() {
        List<ApprovalOperationsDtos.DeliveryTarget> items = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            items.add(new ApprovalOperationsDtos.DeliveryTarget(UUID.randomUUID(), index));
        }

        assertError(
                () -> service.retryBatch(
                        new ApprovalOperationsDtos.DeliveryBatchCommand(
                                UUID.randomUUID(), items, "Recover queue"), headers("large", 0)),
                ErrorCode.INVALID_INPUT_VALUE);

        verify(authority, never()).requireCurrent(any(), any(), anyLong());
        verify(repository, never()).begin(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsDuplicateTargetsBeforeAuthorityOrPersistence() {
        UUID target = UUID.randomUUID();
        var input = new ApprovalOperationsDtos.DeliveryBatchCommand(
                UUID.randomUUID(),
                List.of(
                        new ApprovalOperationsDtos.DeliveryTarget(target, 1),
                        new ApprovalOperationsDtos.DeliveryTarget(target, 1)),
                "Recover queue");

        assertError(
                () -> service.retryBatch(input, headers("duplicate", 0)),
                ErrorCode.INVALID_INPUT_VALUE);

        verify(authority, never()).requireCurrent(any(), any(), anyLong());
        verify(repository, never()).begin(any(), any(), any(), any(), any());
    }

    @Test
    void missingSingleObjectVersionIsAConflictNotAnImplicitCurrentWrite() {
        assertError(
                () -> service.deadLetter(
                        UUID.randomUUID(), null,
                        new ApprovalOperationsDtos.Reason("Isolate failed delivery"),
                        headers("missing-version", 0)),
                ErrorCode.OBJECT_VERSION_CONFLICT);

        verify(authority, never()).requireCurrent(any(), any(), anyLong());
    }

    @Test
    void revokedCurrentAuthorityStopsBeforeAnyTargetRead() {
        var input = new ApprovalOperationsDtos.DeliveryBatchCommand(
                UUID.randomUUID(),
                List.of(new ApprovalOperationsDtos.DeliveryTarget(UUID.randomUUID(), 2)),
                "Recover queue");
        when(authority.requireCurrent(any(), any(), anyLong()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        assertError(
                () -> service.retryBatch(input, headers("revoked", 0)),
                ErrorCode.FORBIDDEN);

        verify(repository, never()).deliveries(any(), any(), any(Boolean.class));
        verify(repository, never()).begin(any(), any(), any(), any(), any());
    }

    private ApprovalStepUpHeaders headers(String key, long version) {
        return ApprovalStepUpHeaders.of("signed", key, "decision-1", version);
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
