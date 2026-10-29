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
    private final MeetingInvitationDeliveryReadiness readiness;

    MeetingInvitationDeliveryWorker(
            MeetingInvitationDeliveryTransactions transactions,
            MeetingInvitationNotificationGateway gateway,
            MeetingInvitationDeliveryProperties properties,
            MeetingInvitationDeliveryReadiness readiness) {
        this.transactions = transactions;
        this.gateway = gateway;
        this.properties = properties;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString =
            "${dwp.meeting.invitation-delivery.poll-delay:PT10S}")
    int dispatch() {
        if (!properties.isEnabled() || !properties.validDispatchConfiguration()) {
            readiness.recordLocalFailure("CONFIGURATION_INVALID");
            return 0;
        }
        readiness.recordPollStarted();
        int processed = 0;
        boolean locallyHealthy = true;
        for (int index = 0; index < properties.getBatchSize(); index++) {
            Claim claim;
            try {
                claim = transactions.claim();
            } catch (RuntimeException unavailable) {
                readiness.recordLocalFailure("CLAIM_UNAVAILABLE");
                return processed;
            }
            if (claim == null) {
                if (locallyHealthy) readiness.recordSuccessfulPoll(processed);
                return processed;
            }
            readiness.recordLocalProgress();
            try {
                if (!deliver(claim)) {
                    locallyHealthy = false;
                    readiness.recordLocalFailure("REJECTION_PERSISTENCE_UNAVAILABLE");
                } else {
                    readiness.recordLocalProgress();
                }
            } catch (RuntimeException unavailable) {
                readiness.recordLocalFailure("ACCEPTANCE_PERSISTENCE_UNAVAILABLE");
                throw unavailable;
            }
            processed++;
        }
        if (locallyHealthy) readiness.recordSuccessfulPoll(processed);
        return processed;
    }

    private boolean deliver(Claim claim) {
        Acceptance acceptance;
        try {
            acceptance = gateway.deliver(claim);
        } catch (MeetingInvitationDeliveryException rejected) {
            return reject(claim, rejected.failureCode(), rejected.retryable());
        } catch (RuntimeException unavailable) {
            return reject(claim, "NOTIFICATION_UNAVAILABLE", true);
        }
        // A database failure after owner acceptance intentionally leaves the lease in place.
        // The next claim replays the same sourceEventId and immutable request body.
        transactions.accept(claim, acceptance);
        return true;
    }

    private boolean reject(Claim claim, String failureCode, boolean retryable) {
        try {
            transactions.reject(claim, failureCode, retryable);
            return true;
        } catch (RuntimeException ignored) {
            // The still-leased durable row is the recovery record.
            return false;
        }
    }
}
