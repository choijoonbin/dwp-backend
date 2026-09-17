package com.dwp.services.people.hr;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrMailProposalOutcomeWorkerTest {

    private final HrMailProposalOutcomeOutboxRepository repository =
            mock(HrMailProposalOutcomeOutboxRepository.class);
    private final HrMailProposalOutcomeClient client = mock(HrMailProposalOutcomeClient.class);
    private final HrMailProposalOutcomeWorker worker =
            new HrMailProposalOutcomeWorker(repository, client, 25);

    @Test
    void successfulDeliveryPublishesOnlyAfterTheOwnerAcceptedIt() {
        var outcome = outcome(1);
        when(repository.claim(25)).thenReturn(List.of(outcome));

        worker.publishPending();

        var order = inOrder(client, repository);
        order.verify(repository).claim(25);
        order.verify(client).record(outcome);
        order.verify(repository).markPublished(outcome.outcomeId());
    }

    @Test
    void acceptanceFollowedByLocalMarkFailureRetriesTheExactIdempotentPayload() {
        var outcome = outcome(1);
        when(repository.claim(25)).thenAnswer(ignored -> List.of(outcome));
        doThrow(new DataAccessResourceFailureException("commit unavailable"))
                .doNothing()
                .when(repository).markPublished(outcome.outcomeId());

        worker.publishPending();
        worker.publishPending();

        verify(client, times(2)).record(outcome);
        verify(repository).markFailed(
                outcome.outcomeId(), 1, 10, true, "commit unavailable");
        verify(repository, times(2)).markPublished(outcome.outcomeId());
    }

    @Test
    void permanentPlatformRejectionIsMarkedNonRetryableImmediately() {
        var outcome = outcome(1);
        when(repository.claim(25)).thenReturn(List.of(outcome));
        doThrow(new HrMailProposalOutcomeClient.DeliveryException(
                false, "Platform returned HTTP 409", null))
                .when(client).record(outcome);

        worker.publishPending();

        verify(repository).markFailed(
                outcome.outcomeId(), 1, 10, false, "Platform returned HTTP 409");
    }

    private HrMailProposalOutcomeOutboxRepository.PendingOutcome outcome(int attempt) {
        UUID leaveRequestId = UUID.randomUUID();
        return new HrMailProposalOutcomeOutboxRepository.PendingOutcome(
                UUID.randomUUID(), 3L, 17L, UUID.randomUUID(), UUID.randomUUID(), 5L,
                "hr-leave-request:" + leaveRequestId, "corr-worker", attempt);
    }
}
