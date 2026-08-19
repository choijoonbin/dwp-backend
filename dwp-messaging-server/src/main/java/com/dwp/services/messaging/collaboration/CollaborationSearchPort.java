package com.dwp.services.messaging.collaboration;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public interface CollaborationSearchPort {

    List<SearchDocument> search(SearchCriteria criteria);

    record SearchCriteria(
            long tenantId,
            long userId,
            String query,
            EnumSet<CollaborationDtos.SearchType> types,
            int limit) {
    }

    record SearchDocument(
            CollaborationDtos.SearchType type,
            int score,
            UUID conversationId,
            UUID messageId,
            Long userId,
            UUID personPublicId,
            String conversationType,
            String title,
            String subtitle,
            String content,
            String emailAddress,
            String jobTitle,
            String organizationName,
            String presenceState,
            OffsetDateTime occurredAt) {
    }
}
