package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Event;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway.Acceptance;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingInvitationDeliveryWorkerTest {

    @Test
    void storesTheOwnerReceiptAfterOneSuccessfulMaterialization() {
        MeetingInvitationDeliveryTransactions transactions =
                mock(MeetingInvitationDeliveryTransactions.class);
        MeetingInvitationNotificationGateway gateway =
                mock(MeetingInvitationNotificationGateway.class);
        MeetingInvitationDeliveryProperties properties = properties(20);
        MeetingInvitationDeliveryReadiness readiness =
                mock(MeetingInvitationDeliveryReadiness.class);
        Claim claim = claim(1);
        Acceptance receipt = acceptance();
        when(transactions.claim()).thenReturn(claim).thenReturn(null);
        when(gateway.deliver(claim)).thenReturn(receipt);

        int processed = new MeetingInvitationDeliveryWorker(
                transactions, gateway, properties, readiness).dispatch();

        assertThat(processed).isOne();
        verify(transactions).accept(claim, receipt);
        verify(transactions, never()).reject(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(readiness).recordPollStarted();
        verify(readiness, times(2)).recordLocalProgress();
        verify(readiness).recordSuccessfulPoll(1);
    }

    @Test
    void preservesTheAdaptersRetryabilityDecisionWithinTheBatchBound() {
        MeetingInvitationDeliveryTransactions transactions =
                mock(MeetingInvitationDeliveryTransactions.class);
        MeetingInvitationNotificationGateway gateway =
                mock(MeetingInvitationNotificationGateway.class);
        MeetingInvitationDeliveryReadiness readiness =
                mock(MeetingInvitationDeliveryReadiness.class);
        Claim retryable = claim(1);
        Claim terminal = claim(2);
        when(transactions.claim()).thenReturn(retryable, terminal);
        when(gateway.deliver(retryable)).thenThrow(
                new MeetingInvitationDeliveryException(
                        "NOTIFICATION_UNAVAILABLE", true));
        when(gateway.deliver(terminal)).thenThrow(
                new MeetingInvitationDeliveryException(
                        "NOTIFICATION_NOT_MATERIALIZED", false));

        int processed = new MeetingInvitationDeliveryWorker(
                transactions, gateway, properties(2), readiness).dispatch();

        assertThat(processed).isEqualTo(2);
        verify(transactions).reject(
                retryable, "NOTIFICATION_UNAVAILABLE", true);
        verify(transactions).reject(
                terminal, "NOTIFICATION_NOT_MATERIALIZED", false);
        verify(readiness).recordPollStarted();
        verify(readiness).recordSuccessfulPoll(2);
    }

    @Test
    void leavesTheLeaseForDeterministicReplayWhenReceiptPersistenceFails() {
        MeetingInvitationDeliveryTransactions transactions =
                mock(MeetingInvitationDeliveryTransactions.class);
        MeetingInvitationNotificationGateway gateway =
                mock(MeetingInvitationNotificationGateway.class);
        MeetingInvitationDeliveryReadiness readiness =
                mock(MeetingInvitationDeliveryReadiness.class);
        Claim claim = claim(1);
        Acceptance receipt = acceptance();
        when(transactions.claim()).thenReturn(claim);
        when(gateway.deliver(claim)).thenReturn(receipt);
        doThrow(new IllegalStateException("database unavailable"))
                .when(transactions).accept(claim, receipt);

        MeetingInvitationDeliveryWorker worker = new MeetingInvitationDeliveryWorker(
                transactions, gateway, properties(1), readiness);

        assertThatThrownBy(worker::dispatch).isInstanceOf(IllegalStateException.class);
        verify(transactions, never()).reject(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(readiness).recordLocalFailure("ACCEPTANCE_PERSISTENCE_UNAVAILABLE");
    }

    @Test
    void recordsClaimFailureWithoutCallingTheRemoteGateway() {
        MeetingInvitationDeliveryTransactions transactions =
                mock(MeetingInvitationDeliveryTransactions.class);
        MeetingInvitationNotificationGateway gateway =
                mock(MeetingInvitationNotificationGateway.class);
        MeetingInvitationDeliveryReadiness readiness =
                mock(MeetingInvitationDeliveryReadiness.class);
        when(transactions.claim()).thenThrow(new IllegalStateException("database unavailable"));

        int processed = new MeetingInvitationDeliveryWorker(
                transactions, gateway, properties(1), readiness).dispatch();

        assertThat(processed).isZero();
        verify(gateway, never()).deliver(org.mockito.ArgumentMatchers.any());
        verify(readiness).recordPollStarted();
        verify(readiness).recordLocalFailure("CLAIM_UNAVAILABLE");
        verify(readiness, never()).recordSuccessfulPoll(
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void leavesTheLeaseAndMarksLocalHealthWhenRejectionPersistenceFails() {
        MeetingInvitationDeliveryTransactions transactions =
                mock(MeetingInvitationDeliveryTransactions.class);
        MeetingInvitationNotificationGateway gateway =
                mock(MeetingInvitationNotificationGateway.class);
        MeetingInvitationDeliveryReadiness readiness =
                mock(MeetingInvitationDeliveryReadiness.class);
        Claim claim = claim(1);
        when(transactions.claim()).thenReturn(claim);
        when(gateway.deliver(claim)).thenThrow(
                new MeetingInvitationDeliveryException(
                        "NOTIFICATION_UNAVAILABLE", true));
        doThrow(new IllegalStateException("database unavailable"))
                .when(transactions).reject(
                        claim, "NOTIFICATION_UNAVAILABLE", true);

        int processed = new MeetingInvitationDeliveryWorker(
                transactions, gateway, properties(1), readiness).dispatch();

        assertThat(processed).isOne();
        verify(readiness).recordLocalFailure("REJECTION_PERSISTENCE_UNAVAILABLE");
        verify(readiness, never()).recordSuccessfulPoll(
                org.mockito.ArgumentMatchers.anyInt());
    }

    private MeetingInvitationDeliveryProperties properties(int batchSize) {
        MeetingInvitationDeliveryProperties properties =
                new MeetingInvitationDeliveryProperties();
        properties.setEnabled(true);
        properties.setBatchSize(batchSize);
        return properties;
    }

    private Claim claim(int suffix) {
        UUID eventId = UUID.nameUUIDFromBytes(("event-" + suffix).getBytes());
        Event event = new Event(
                eventId, 42, UUID.nameUUIDFromBytes("meeting".getBytes()),
                "MEETING_SCHEDULED", OffsetDateTime.parse("2026-09-08T12:00:00Z"),
                UUID.nameUUIDFromBytes(("event-fence-" + suffix).getBytes()),
                OffsetDateTime.parse("2026-09-08T12:02:00Z"));
        return new Claim(
                event, 70L + suffix,
                UUID.nameUUIDFromBytes(("source-" + suffix).getBytes()), 1,
                UUID.nameUUIDFromBytes(("delivery-fence-" + suffix).getBytes()),
                OffsetDateTime.parse("2026-09-08T12:02:00Z"));
    }

    private Acceptance acceptance() {
        return new Acceptance(
                UUID.nameUUIDFromBytes("intent".getBytes()),
                UUID.nameUUIDFromBytes("notification".getBytes()),
                1, false, "1");
    }
}
