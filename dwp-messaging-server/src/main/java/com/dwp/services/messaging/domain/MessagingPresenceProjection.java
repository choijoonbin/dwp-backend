package com.dwp.services.messaging.domain;

import com.dwp.services.messaging.presence.MessagingPresenceService;
import com.dwp.services.messaging.security.MessagingRequestContext;

import java.time.OffsetDateTime;
import java.util.List;

/** Joins durable messaging projections with the intentionally ephemeral presence view. */
final class MessagingPresenceProjection {

    private static final int HOME_PRESENCE_CANDIDATE_LIMIT = 32;

    private MessagingPresenceProjection() {
    }

    static MessagingDtos.HomeResponse home(
            MessagingQueryRepository queries,
            MessagingPresenceService presence,
            MessagingRequestContext.Subject subject) {
        long tenantId = subject.tenantId();
        long userId = subject.userId();
        MessagingPresenceService.PresenceSnapshot snapshot =
                presence.availableCandidates(tenantId, HOME_PRESENCE_CANDIDATE_LIMIT);
        List<MessagingDtos.PersonSummary> availablePeople = snapshot.authoritative()
                ? presence.resolvePeople(
                        snapshot,
                        queries.peopleByUserIds(
                                tenantId, userId, snapshot.availableUserIds(), 8))
                : List.of();
        return new MessagingDtos.HomeResponse(
                OffsetDateTime.now(),
                queries.metrics(tenantId, userId),
                queries.conversations(tenantId, userId, "ALL", "", 0, 8),
                queries.conversations(tenantId, userId, "SPACES", "", 0, 6),
                presence.metadata(snapshot),
                availablePeople);
    }

    static List<MessagingDtos.PersonSummary> people(
            MessagingQueryRepository queries,
            MessagingPresenceService presence,
            MessagingRequestContext.Subject subject,
            String query,
            int limit) {
        List<MessagingDtos.PersonSummary> directory = queries.people(
                subject.tenantId(),
                subject.userId(),
                query,
                Math.min(Math.max(limit, 1), 50));
        return presence.resolvePeople(subject.tenantId(), directory);
    }

    static MessagingDtos.ConversationDetail detail(
            MessagingQueryRepository queries,
            MessagingPresenceService presence,
            long tenantId,
            long userId,
            MessagingDtos.ConversationSummary conversation) {
        List<MessagingDtos.MemberSummary> members =
                queries.members(tenantId, conversation.conversationId(), userId);
        return new MessagingDtos.ConversationDetail(
                conversation,
                presence.resolveMembers(tenantId, members),
                queries.messages(tenantId, conversation.conversationId(), userId, 80),
                new MessagingDtos.RealtimeStatus(
                        "SSE",
                        "/api/messaging/v1/stream",
                        "ACTIVE",
                        "Durable event replay supports Last-Event-ID reconnect recovery."));
    }
}
