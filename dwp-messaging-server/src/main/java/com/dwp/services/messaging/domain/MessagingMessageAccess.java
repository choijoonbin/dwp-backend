package com.dwp.services.messaging.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

record MessagingMessageAccess(
        UUID messageId,
        UUID conversationId,
        long sequence,
        long senderUserId,
        UUID replyToMessageId,
        OffsetDateTime deletedAt,
        long version,
        String memberRole) {

    boolean isAuthor(long userId) {
        return senderUserId == userId;
    }

    boolean canModerate() {
        return "OWNER".equals(memberRole) || "MODERATOR".equals(memberRole);
    }

    boolean isRoot() {
        return replyToMessageId == null;
    }
}
