package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
class MessagingScheduledDeliveryDispatcher {

    private final MessagingContinuityRepository continuity;
    private final MessagingService messaging;

    MessagingScheduledDeliveryDispatcher(
            MessagingContinuityRepository continuity,
            MessagingService messaging) {
        this.continuity = continuity;
        this.messaging = messaging;
    }

    UUID dispatch(MessagingContinuityRepository.ScheduledRecord scheduled) {
        var replay = continuity.deliveredMessage(
                scheduled.tenantId(),
                scheduled.senderUserId(),
                scheduled.response().conversationId(),
                scheduled.sendIdempotencyKey());
        if (replay.isPresent()) return replay.orElseThrow();
        var identity = continuity.activePerson(scheduled.tenantId(), scheduled.senderUserId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The scheduled message author is no longer active."));
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                scheduled.senderUserId(),
                scheduled.tenantId(),
                identity.personPublicId(),
                identity.displayName(),
                Set.of(),
                Set.of("APP.MESSAGING:VIEW", "APP.MESSAGING:CREATE"),
                Set.of()));
        try {
            var item = scheduled.response();
            return messaging.sendMessage(
                    item.conversationId(),
                    new MessagingDtos.SendMessageRequest(
                            item.body(),
                            scheduled.sendIdempotencyKey(),
                            item.replyToMessageId(),
                            List.of(),
                            scheduled.mentionedUserIds()),
                    "scheduled:" + item.scheduledMessageId()).messageId();
        } finally {
            MessagingRequestContext.clear();
        }
    }
}
