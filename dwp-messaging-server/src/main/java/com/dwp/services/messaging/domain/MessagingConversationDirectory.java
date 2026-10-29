package com.dwp.services.messaging.domain;

import com.dwp.services.messaging.security.MessagingRequestContext;

import java.util.Locale;
import java.util.Set;

/** Owns cursor-first conversation listing while retaining offset compatibility. */
final class MessagingConversationDirectory {

    private static final Set<String> SCOPES =
            Set.of("ALL", "FAVORITES", "SPACES", "DIRECT", "CHANNELS", "MENTIONS");

    private final MessagingQueryRepository queries;

    MessagingConversationDirectory(MessagingQueryRepository queries) {
        this.queries = queries;
    }

    MessagingDtos.ConversationPage page(
            String scope,
            String query,
            int page,
            int pageSize,
            String cursor) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        String resolvedScope = scope(scope);
        String resolvedQuery = query(query);
        int resolvedPage = Math.max(0, page);
        int resolvedPageSize = Math.max(1, Math.min(100, pageSize));
        MessagingConversationCursor.Cursor resolvedCursor =
                MessagingConversationCursor.decode(
                        cursor, subject.tenantId(), subject.userId(),
                        resolvedScope, resolvedQuery);
        MessagingQueryRepository.ConversationSlice slice = queries.conversationPage(
                subject.tenantId(), subject.userId(), resolvedScope, resolvedQuery,
                resolvedCursor, resolvedCursor == null ? resolvedPage : 0, resolvedPageSize);
        String nextCursor = slice.hasMore() && !slice.items().isEmpty()
                ? MessagingConversationCursor.encode(
                        slice.items().getLast(), subject.tenantId(), subject.userId(),
                        resolvedScope, resolvedQuery)
                : null;
        return new MessagingDtos.ConversationPage(
                slice.items(),
                queries.conversationCount(
                        subject.tenantId(), subject.userId(), resolvedScope, resolvedQuery),
                resolvedPage,
                resolvedPageSize,
                slice.hasMore(),
                nextCursor,
                resolvedCursor == null && slice.hasMore() && resolvedPage < Integer.MAX_VALUE
                        ? resolvedPage + 1
                        : null);
    }

    private String scope(String value) {
        String normalized = value == null || value.isBlank()
                ? "ALL"
                : value.trim().toUpperCase(Locale.ROOT);
        return SCOPES.contains(normalized) ? normalized : "ALL";
    }

    private String query(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }
}
