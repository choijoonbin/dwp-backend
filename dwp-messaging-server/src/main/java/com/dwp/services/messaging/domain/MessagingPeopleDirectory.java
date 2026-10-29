package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.presence.MessagingPresenceService;
import com.dwp.services.messaging.security.MessagingRequestContext;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Keeps directory filtering and cursor validation out of the message command service. */
final class MessagingPeopleDirectory {

    private static final Set<String> PRESENCE_FILTERS =
            Set.of("ALL", "AVAILABLE", "OFFLINE", "UNKNOWN");

    private final MessagingQueryRepository queries;
    private final MessagingPresenceService presence;

    MessagingPeopleDirectory(
            MessagingQueryRepository queries,
            MessagingPresenceService presence) {
        this.queries = queries;
        this.presence = presence;
    }

    List<MessagingDtos.PersonSummary> people(String query, int limit) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        return MessagingPresenceProjection.people(
                queries, presence, subject, normalized(query), limit);
    }

    MessagingDtos.PersonPage page(
            String query,
            String organization,
            String jobTitle,
            String presenceFilter,
            String cursor,
            int offset,
            int limit) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        String resolvedPresence = presenceFilter == null
                ? "ALL" : presenceFilter.strip().toUpperCase(Locale.ROOT);
        if (!PRESENCE_FILTERS.contains(resolvedPresence)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Presence filter must be ALL, AVAILABLE, OFFLINE, or UNKNOWN.");
        }
        String resolvedQuery = normalized(query);
        String resolvedOrganization = normalized(organization);
        String resolvedJobTitle = normalized(jobTitle);
        MessagingPeopleCursor.Cursor resolvedCursor = MessagingPeopleCursor.decode(
                cursor, subject.tenantId(), subject.userId(), resolvedQuery,
                resolvedOrganization, resolvedJobTitle, resolvedPresence);
        if (resolvedCursor == null && (offset < 0 || offset > 100_000)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "People directory offset must be between 0 and 100000.");
        }
        return MessagingPresenceProjection.peoplePage(
                queries,
                presence,
                subject,
                resolvedQuery,
                resolvedOrganization,
                resolvedJobTitle,
                resolvedPresence,
                resolvedCursor,
                resolvedCursor == null ? offset : 0,
                Math.min(Math.max(limit, 1), 50));
    }

    private String normalized(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }
}
