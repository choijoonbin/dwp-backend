package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class MessagingScheduledDeliveryWorkerTest {

    @Mock
    private MessagingContinuityRepository continuity;
    @Mock
    private MessagingScheduledDeliveryDispatcher dispatcher;

    @Test
    void successfulClaimIsCompletedWithTheIdempotentDeliveryMessage() {
        var scheduled = scheduled(1);
        UUID delivered = UUID.randomUUID();
        when(continuity.claimDue(20)).thenReturn(List.of(scheduled));
        when(dispatcher.dispatch(scheduled)).thenReturn(delivered);

        worker().dispatchDue();

        verify(continuity).markSent(scheduled.response().scheduledMessageId(), delivered);
        verify(continuity).recordWorkerStarted(any(UUID.class));
        verify(continuity).recordWorkerCompleted(
                any(UUID.class), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void revokedMembershipFailureIsPermanentAndIsNotRetried() {
        var scheduled = scheduled(1);
        when(continuity.claimDue(20)).thenReturn(List.of(scheduled));
        when(dispatcher.dispatch(scheduled)).thenThrow(
                new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Conversation membership revoked."));

        worker().dispatchDue();

        verify(continuity).markFailed(
                scheduled.response().scheduledMessageId(),
                ErrorCode.ENTITY_NOT_FOUND.getCode(),
                "Conversation membership revoked.");
        verify(continuity, never()).markRetry(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private MessagingScheduledDeliveryWorker worker() {
        return new MessagingScheduledDeliveryWorker(continuity, dispatcher, 20, 5);
    }

    private MessagingContinuityRepository.ScheduledRecord scheduled(int attemptCount) {
        UUID id = UUID.randomUUID();
        var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                id, UUID.randomUUID(), "scheduled body", null, List.of(),
                OffsetDateTime.now(), "UTC", "PROCESSING", attemptCount,
                null, null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now(),
                "Conversation", null);
        return new MessagingContinuityRepository.ScheduledRecord(
                response, 1, 100, List.of(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64));
    }
}
