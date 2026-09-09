package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Event;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryRepository.DeliveryLease;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryRepository.ParentCounts;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway.Acceptance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
class MeetingInvitationDeliveryTransactions {

    private static final int MAXIMUM_EMPTY_EVENTS_PER_CLAIM = 100;

    private final MeetingInvitationDeliveryRepository repository;
    private final MeetingInvitationDeliveryProperties properties;
    private final Clock clock;

    @Autowired
    MeetingInvitationDeliveryTransactions(
            MeetingInvitationDeliveryRepository repository,
            MeetingInvitationDeliveryProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    MeetingInvitationDeliveryTransactions(
            MeetingInvitationDeliveryRepository repository,
            MeetingInvitationDeliveryProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim() {
        requireConfiguration();
        for (int index = 0; index < MAXIMUM_EMPTY_EVENTS_PER_CLAIM; index++) {
            OffsetDateTime now = now();
            UUID eventFence = UUID.randomUUID();
            Event event = repository.claimEvent(
                    now, now.plus(properties.getLeaseDuration()), eventFence).orElse(null);
            if (event == null) return null;
            repository.lockMeeting(event);
            repository.synchronizeCurrentRecipients(event, now);
            repository.exhaustExpiredTargets(
                    event, now, properties.getMaximumAttempts());
            Claim recipient = repository.claimRecipient(
                    event, now, now.plus(properties.getLeaseDuration()), UUID.randomUUID(),
                    properties.getMaximumAttempts()).orElse(null);
            if (recipient != null) {
                UUID expectedSourceEventId = MeetingInvitationDeliveryModels.sourceEventId(
                        event.eventId(), event.tenantId(), recipient.recipientUserId());
                if (!expectedSourceEventId.equals(recipient.sourceEventId())) {
                    throw new IllegalStateException(
                            "Meeting invitation recipient identity changed.");
                }
                return recipient;
            }
            settleParent(event, now, null);
        }
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(Claim claimed, Acceptance acceptance) {
        validateAcceptance(acceptance);
        OffsetDateTime now = now();
        Event current = currentEvent(claimed, now);
        repository.lockMeeting(current);
        currentRecipient(claimed, now);
        if (!repository.isCurrentRecipient(current, claimed.recipientUserId())) {
            repository.supersede(claimed, acceptance, now);
            repository.synchronizeCurrentRecipients(current, now);
            settleParent(current, now, null);
            return;
        }
        repository.accept(claimed, acceptance, now);
        repository.synchronizeCurrentRecipients(current, now);
        settleParent(current, now, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reject(Claim claimed, String failureCode, boolean retryable) {
        validateFailureCode(failureCode);
        OffsetDateTime now = now();
        Event current = currentEvent(claimed, now);
        repository.lockMeeting(current);
        DeliveryLease recipient = currentRecipient(claimed, now);
        if (!repository.isCurrentRecipient(current, claimed.recipientUserId())) {
            repository.cancel(claimed, now);
            repository.synchronizeCurrentRecipients(current, now);
            settleParent(current, now, null);
            return;
        }
        if (retryable && recipient.attemptCount() < properties.getMaximumAttempts()) {
            OffsetDateTime availableAt = now.plus(properties.getRetryDelay());
            repository.retry(claimed, failureCode, now, availableAt);
            repository.releaseParent(current, now, availableAt, failureCode);
            return;
        }
        repository.fail(claimed, failureCode, now);
        repository.synchronizeCurrentRecipients(current, now);
        settleParent(current, now, failureCode);
    }

    private Event currentEvent(Claim claimed, OffsetDateTime now) {
        if (claimed == null || claimed.event() == null) {
            throw new IllegalStateException("Meeting invitation claim is missing.");
        }
        Event event = repository.lockEvent(claimed.event().eventId());
        if (event.tenantId() != claimed.event().tenantId()
                || !event.meetingId().equals(claimed.event().meetingId())
                || !event.eventType().equals(claimed.event().eventType())
                || !event.occurredAt().equals(claimed.event().occurredAt())
                || event.dispatchFence() == null
                || !event.dispatchFence().equals(claimed.event().dispatchFence())
                || event.leaseExpiresAt() == null
                || !event.leaseExpiresAt().isAfter(now)) {
            throw new IllegalStateException(
                    "Meeting invitation event fence changed or expired.");
        }
        return event;
    }

    private DeliveryLease currentRecipient(Claim claimed, OffsetDateTime now) {
        DeliveryLease recipient = repository.lockRecipient(
                claimed.event().eventId(), claimed.recipientUserId());
        if (!"PENDING".equals(recipient.state())
                || !recipient.sourceEventId().equals(claimed.sourceEventId())
                || recipient.attemptCount() != claimed.attemptCount()
                || recipient.fence() == null
                || !recipient.fence().equals(claimed.deliveryFence())
                || recipient.leaseExpiresAt() == null
                || !recipient.leaseExpiresAt().isAfter(now)) {
            throw new IllegalStateException(
                    "Meeting invitation recipient fence changed or expired.");
        }
        return recipient;
    }

    private void settleParent(Event event, OffsetDateTime now, String failureCode) {
        ParentCounts counts = repository.parentCounts(event);
        if (counts.pending() > 0) {
            OffsetDateTime availableAt = counts.nextAvailable();
            if (availableAt == null || availableAt.isBefore(now)) availableAt = now;
            repository.releaseParent(event, now, availableAt, failureCode);
        } else if (counts.failed() > 0) {
            repository.failParent(event,
                    failureCode == null ? "RECIPIENT_DELIVERY_FAILED" : failureCode, now);
        } else {
            repository.deliverParent(event, now);
        }
    }

    private void validateAcceptance(Acceptance acceptance) {
        if (acceptance == null || acceptance.intentId() == null
                || acceptance.recipientCount() != 1
                || acceptance.notificationId() == null
                || acceptance.highestChangeVersion() == null
                || !acceptance.highestChangeVersion().matches("^(0|[1-9][0-9]{0,18})$")) {
            throw new IllegalArgumentException(
                    "Notification acceptance receipt is invalid.");
        }
    }

    private void validateFailureCode(String failureCode) {
        if (failureCode == null || !failureCode.matches("^[A-Z][A-Z0-9_]{2,47}$")) {
            throw new IllegalArgumentException("Invitation failure code is invalid.");
        }
    }

    private void requireConfiguration() {
        if (!properties.isEnabled() || !properties.validDispatchConfiguration()) {
            throw new IllegalStateException(
                    "Meeting invitation delivery is not configured safely.");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
