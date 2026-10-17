package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.HandoffState;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffOutcomeV1;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TimeCloseHandoffRecoveryTest {

    @Test
    void pendingHandoffAcceptsOnlyFirstReceiverRevisionWithExactLineage() {
        HandoffRecord pending = TimeCloseTestFixtures.pendingHandoff();

        for (HandoffState outcome : List.of(
                HandoffState.ACKNOWLEDGED,
                HandoffState.REJECTED,
                HandoffState.RESULT_UNKNOWN)) {
            assertThat(TimeCloseHandoffRecovery.accepts(
                    pending, TimeCloseTestFixtures.outcome(
                            outcome, 1L, TimeCloseTestFixtures.CORRELATION)))
                    .isTrue();
        }
        assertThat(TimeCloseHandoffRecovery.accepts(
                pending, TimeCloseTestFixtures.outcome(
                        HandoffState.RESULT_UNKNOWN, 2L,
                        TimeCloseTestFixtures.CORRELATION)))
                .isFalse();
    }

    @Test
    void resultUnknownRecoversOnlyWithSameReceiptAndExactNextRevision() {
        HandoffRecord unknown = TimeCloseTestFixtures.unknownHandoff();
        PayrollTimeHandoffOutcomeV1 recovered = TimeCloseTestFixtures.outcome(
                HandoffState.ACKNOWLEDGED, 2L, TimeCloseTestFixtures.CORRELATION);

        assertThat(TimeCloseHandoffRecovery.accepts(unknown, recovered)).isTrue();
        assertThat(TimeCloseHandoffRecovery.accepts(
                unknown, outcomeWith(
                        HandoffState.ACKNOWLEDGED, UUID.randomUUID(), 2L,
                        TimeCloseTestFixtures.PAYLOAD_DIGEST,
                        TimeCloseTestFixtures.CORRELATION)))
                .isFalse();
        assertThat(TimeCloseHandoffRecovery.accepts(
                unknown, TimeCloseTestFixtures.outcome(
                        HandoffState.ACKNOWLEDGED, 3L,
                        TimeCloseTestFixtures.CORRELATION)))
                .isFalse();
        assertThat(TimeCloseHandoffRecovery.accepts(
                unknown, TimeCloseTestFixtures.outcome(
                        HandoffState.RESULT_UNKNOWN, 2L,
                        TimeCloseTestFixtures.CORRELATION)))
                .isFalse();
    }

    @Test
    void changedResultDigestCorrelationAndTerminalStateAllFailClosed() {
        HandoffRecord pending = TimeCloseTestFixtures.pendingHandoff();

        assertThat(TimeCloseHandoffRecovery.accepts(
                pending, outcomeWith(
                        HandoffState.RESULT_UNKNOWN,
                        TimeCloseTestFixtures.RECEIVER_RECEIPT, 1L,
                        "0".repeat(64), TimeCloseTestFixtures.CORRELATION)))
                .isFalse();
        assertThat(TimeCloseHandoffRecovery.accepts(
                pending, outcomeWith(
                        HandoffState.RESULT_UNKNOWN,
                        TimeCloseTestFixtures.RECEIVER_RECEIPT, 1L,
                        TimeCloseTestFixtures.PAYLOAD_DIGEST, UUID.randomUUID())))
                .isFalse();
        assertThat(TimeCloseHandoffRecovery.accepts(
                TimeCloseTestFixtures.acknowledgedHandoff(),
                TimeCloseTestFixtures.outcome(
                        HandoffState.ACKNOWLEDGED, 3L,
                        TimeCloseTestFixtures.CORRELATION)))
                .isFalse();
    }

    @Test
    void semanticallyIdenticalOutcomeRetriesUseOneTenantAndHandoffBoundDigest() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseService service = new TimeCloseService(
                repository,
                mock(TimeCloseEvidenceSource.class),
                mock(TimeCloseApprovalVerifier.class),
                mock(TimeCloseStepUpVerifier.class),
                tenant -> tenant == TimeCloseTestFixtures.TENANT,
                Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC));
        PayrollTimeHandoffOutcomeV1 outcome = TimeCloseTestFixtures.outcome(
                HandoffState.RESULT_UNKNOWN, 1L, TimeCloseTestFixtures.CORRELATION);
        String firstBody = TimeCloseDigests.canonicalJson(outcome);
        String reorderedBody = """
                {
                  "receiverReceiptRevision":1,
                  "closedTimeResultId":"%s",
                  "correlationId":"%s",
                  "outcomeAt":"%s",
                  "receiverReceiptDigest":"%s",
                  "reasonCode":"PROVIDER_UNCERTAIN",
                  "payloadDigest":"%s",
                  "outcome":"RESULT_UNKNOWN",
                  "closeRevision":3,
                  "receiverReceiptId":"%s"
                }
                """.formatted(
                    TimeCloseTestFixtures.RESULT,
                    TimeCloseTestFixtures.CORRELATION,
                    outcome.outcomeAt(),
                    TimeCloseTestFixtures.RECEIVER_DIGEST,
                    TimeCloseTestFixtures.PAYLOAD_DIGEST,
                    TimeCloseTestFixtures.RECEIVER_RECEIPT);
        when(repository.applyOutcome(
                eq(TimeCloseTestFixtures.TENANT),
                eq(TimeCloseTestFixtures.HANDOFF), anyString(), eq(outcome)))
                .thenReturn(TimeCloseTestFixtures.unknownHandoff().projection());

        service.recordOutcome(
                TimeCloseTestFixtures.TENANT,
                TimeCloseTestFixtures.HANDOFF, firstBody, outcome);
        service.recordOutcome(
                TimeCloseTestFixtures.TENANT,
                TimeCloseTestFixtures.HANDOFF, reorderedBody, outcome);

        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).applyOutcome(
                eq(TimeCloseTestFixtures.TENANT),
                eq(TimeCloseTestFixtures.HANDOFF), digest.capture(), eq(outcome));
        assertThat(digest.getAllValues()).hasSize(2);
        assertThat(digest.getAllValues().getFirst())
                .isEqualTo(digest.getAllValues().getLast())
                .matches("[0-9a-f]{64}");
    }

    private static PayrollTimeHandoffOutcomeV1 outcomeWith(
            HandoffState state,
            UUID receiverReceipt,
            long receiverRevision,
            String payloadDigest,
            UUID correlationId) {
        return new PayrollTimeHandoffOutcomeV1(
                TimeCloseTestFixtures.RESULT, 3L, payloadDigest, state,
                receiverReceipt, receiverRevision, TimeCloseTestFixtures.RECEIVER_DIGEST,
                state == HandoffState.ACKNOWLEDGED ? null : "PROVIDER_UNCERTAIN",
                TimeCloseTestFixtures.NOW.plusSeconds(receiverRevision), correlationId);
    }
}
