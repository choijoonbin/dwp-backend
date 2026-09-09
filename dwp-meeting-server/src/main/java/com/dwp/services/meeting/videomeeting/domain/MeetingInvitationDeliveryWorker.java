package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway.Acceptance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "dwp.meeting.invitation-delivery",
        name = "enabled",
        havingValue = "true")
class MeetingInvitationDeliveryWorker {

    private final MeetingInvitationDeliveryTransactions transactions;
    private final MeetingInvitationNotificationGateway gateway;
    private final MeetingInvitationDeliveryProperties properties;

    MeetingInvitationDeliveryWorker(
            MeetingInvitationDeliveryTransactions transactions,
            MeetingInvitationNotificationGateway gateway,
            MeetingInvitationDeliveryProperties properties) {
        this.transactions = transactions;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString =
            "${dwp.meeting.invitation-delivery.poll-delay:PT10S}")
    int dispatch() {
        if (!properties.isEnabled() || !properties.validDispatchConfiguration()) return 0;
        int processed = 0;
        for (int index = 0; index < properties.getBatchSize(); index++) {
            Claim claim;
            try {
                claim = transactions.claim();
            } catch (RuntimeException unavailable) {
                return processed;
            }
            if (claim == null) return processed;
            deliver(claim);
            processed++;
        }
        return processed;
    }

    private void deliver(Claim claim) {
        Acceptance acceptance;
        try {
            acceptance = gateway.deliver(claim);
        } catch (MeetingInvitationDeliveryException rejected) {
            reject(claim, rejected.failureCode(), rejected.retryable());
            return;
        } catch (RuntimeException unavailable) {
            reject(claim, "NOTIFICATION_UNAVAILABLE", true);
            return;
        }
        // A database failure after owner acceptance intentionally leaves the lease in place.
        // The next claim replays the same sourceEventId and immutable request body.
        transactions.accept(claim, acceptance);
    }

    private void reject(Claim claim, String failureCode, boolean retryable) {
        try {
            transactions.reject(claim, failureCode, retryable);
        } catch (RuntimeException ignored) {
            // The still-leased durable row is the recovery record.
        }
    }
}
