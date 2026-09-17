package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextDiscovery;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextOption;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class NotificationAttentionContextDiscoveryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAttentionContextRepository contextRepository;
    private final NotificationAttentionGovernanceRuntime governanceRuntime;
    private final Clock clock;

    @Autowired
    public NotificationAttentionContextDiscoveryService(
            NotificationDatabaseScope databaseScope,
            NotificationAttentionContextRepository contextRepository,
            NotificationAttentionGovernanceRuntime governanceRuntime) {
        this(databaseScope, contextRepository, governanceRuntime, Clock.systemUTC());
    }

    NotificationAttentionContextDiscoveryService(
            NotificationDatabaseScope databaseScope,
            NotificationAttentionContextRepository contextRepository,
            NotificationAttentionGovernanceRuntime governanceRuntime,
            Clock clock) {
        this.databaseScope = databaseScope;
        this.contextRepository = contextRepository;
        this.governanceRuntime = governanceRuntime;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AttentionContextDiscovery discover(
            NotificationRequestContext.Actor actor,
            String scopeKind,
            String query,
            Integer requestedLimit) {
        databaseScope.applyUser(actor);
        if (!"RESOURCE".equals(scopeKind) && !"TOPIC_TOKEN".equals(scopeKind)) {
            throw new IllegalArgumentException(
                    "Attention context discovery supports RESOURCE or TOPIC_TOKEN.");
        }
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() > 300) {
            throw new IllegalArgumentException(
                    "Attention context discovery search must be 300 characters or fewer.");
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "Attention context discovery limit must be positive.");
        }
        limit = Math.min(limit, MAX_LIMIT);
        Instant generatedAt = Instant.now(clock);
        List<AttentionContextOption> items = "TOPIC_TOKEN".equals(scopeKind)
                ? approvedTopics(actor.tenantId(), normalizedQuery, limit, generatedAt)
                : contextRepository.discover(
                        actor, scopeKind, normalizedQuery, limit);
        return new AttentionContextDiscovery(
                items,
                limit,
                generatedAt);
    }

    private List<AttentionContextOption> approvedTopics(
            long tenantId,
            String query,
            int limit,
            Instant generatedAt) {
        String foldedQuery = query.toLowerCase(Locale.ROOT);
        return governanceRuntime.resolve(tenantId).settings().approvedTopicAllowlist().stream()
                .map(topic -> topic.substring(1))
                .filter(token -> matchesTopic(token, foldedQuery))
                .sorted(Comparator
                        .comparingInt((String token) -> topicRank(token, foldedQuery))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(limit)
                .map(token -> new AttentionContextOption(
                        "TOPIC_TOKEN", "TOPIC", token, "#" + token, generatedAt))
                .toList();
    }

    private boolean matchesTopic(String token, String foldedQuery) {
        return foldedQuery.isEmpty()
                || token.contains(foldedQuery)
                || ("#" + token).contains(foldedQuery);
    }

    private int topicRank(String token, String foldedQuery) {
        return token.equals(foldedQuery) || ("#" + token).equals(foldedQuery) ? 0 : 1;
    }
}
