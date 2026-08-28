package com.dwp.services.messaging.collaboration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.collaboration.CollaborationSearchPort.SearchDocument;
import com.dwp.services.messaging.domain.MessagingTenantPolicyGuard;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CollaborationService {

    private static final int MAX_CHANNEL_NAME = 80;
    private static final int MAX_GROUP_NAME = 120;
    private static final int MAX_CONVERSATION_MEMBERS = 500;
    private static final int MIN_SEARCH_QUERY = 2;
    private static final int MAX_SEARCH_QUERY = 100;
    private static final int MAX_SEARCH_LIMIT = 50;

    private final ConversationCreationRepository creationRepository;
    private final CollaborationSearchPort searchPort;
    private final MessagingTenantPolicyGuard policyGuard;

    public CollaborationService(
            ConversationCreationRepository creationRepository,
            CollaborationSearchPort searchPort,
            MessagingTenantPolicyGuard policyGuard) {
        this.creationRepository = creationRepository;
        this.searchPort = searchPort;
        this.policyGuard = policyGuard;
    }

    @Transactional
    public CollaborationDtos.ConversationCreationResponse createConversation(
            CollaborationDtos.CreateConversationRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        if (request.type() == CollaborationDtos.ConversationType.GROUP) {
            policyGuard.requireDirectMessagingEnabled(subject.tenantId());
        }
        String name = normalizedName(request.type(), request.name());
        String topic = normalizeNullable(request.topic());
        String idempotencyKey = normalizedIdempotencyKey(request.idempotencyKey());

        TreeSet<Long> memberIds = new TreeSet<>(request.memberUserIds());
        memberIds.add(subject.userId());
        validateMemberCount(request.type(), memberIds.size());

        Map<Long, ConversationCreationRepository.PersonRecord> activePeople =
                creationRepository.activePeople(subject.tenantId(), memberIds);
        if (!activePeople.containsKey(subject.userId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The requesting user is not active in the messaging directory.");
        }
        if (activePeople.size() != memberIds.size()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Every member must be active in the requesting tenant.");
        }

        String fingerprint = fingerprint(request.type(), name, topic, memberIds);
        creationRepository.lockCreationRequest(lockKey(
                subject.tenantId(), subject.userId(), idempotencyKey));

        var existing = creationRepository.findCreation(
                subject.tenantId(), subject.userId(), idempotencyKey);
        if (existing.isPresent()) {
            if (!MessageDigest.isEqual(
                    existing.get().fingerprint().getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key was already used with a different request.");
            }
            return response(subject.tenantId(), existing.get().conversationId(), true);
        }

        var conversationId = creationRepository.insertConversation(
                subject.tenantId(), subject.userId(), request.type(), name, topic);
        List<ConversationCreationRepository.PersonRecord> orderedPeople = memberIds.stream()
                .map(activePeople::get)
                .toList();
        creationRepository.insertMembers(
                subject.tenantId(), conversationId, subject.userId(), orderedPeople);
        creationRepository.insertCreationRequest(
                subject.tenantId(), subject.userId(), idempotencyKey,
                fingerprint, conversationId);
        creationRepository.recordCreatedAudit(
                subject.tenantId(), subject.userId(), conversationId, request.type());
        return response(subject.tenantId(), conversationId, false);
    }

    public CollaborationDtos.SearchResponse search(String rawQuery, String rawTypes, int limit) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < MIN_SEARCH_QUERY || query.length() > MAX_SEARCH_QUERY) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The search query must contain between 2 and 100 characters.");
        }
        if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The search limit must be between 1 and 50.");
        }
        EnumSet<CollaborationDtos.SearchType> types = parseTypes(rawTypes);
        List<SearchDocument> documents = searchPort.search(
                new CollaborationSearchPort.SearchCriteria(
                        subject.tenantId(), subject.userId(), query, types, limit));

        List<CollaborationDtos.ConversationSearchResult> conversations = new ArrayList<>();
        List<CollaborationDtos.MessageSearchResult> messages = new ArrayList<>();
        List<CollaborationDtos.PersonSearchResult> people = new ArrayList<>();
        for (SearchDocument document : documents) {
            switch (document.type()) {
                case CONVERSATION -> conversations.add(new CollaborationDtos.ConversationSearchResult(
                        "CONVERSATION",
                        document.conversationId(),
                        document.conversationType(),
                        document.title(),
                        CollaborationText.escapedSnippet(
                                document.content() == null ? document.title() : document.content(),
                                query)));
                case MESSAGE -> messages.add(new CollaborationDtos.MessageSearchResult(
                        "MESSAGE",
                        document.messageId(),
                        document.conversationId(),
                        document.subtitle(),
                        document.title(),
                        CollaborationText.escapedSnippet(document.content(), query),
                        document.occurredAt()));
                case PERSON -> people.add(new CollaborationDtos.PersonSearchResult(
                        "PERSON",
                        document.userId(),
                        document.personPublicId(),
                        document.title(),
                        document.emailAddress(),
                        document.jobTitle(),
                        document.organizationName(),
                        document.presenceState()));
            }
        }
        return new CollaborationDtos.SearchResponse(
                CollaborationDtos.SQL_FALLBACK,
                query,
                limit,
                documents.size(),
                new CollaborationDtos.SearchGroups(
                        List.copyOf(conversations),
                        List.copyOf(messages),
                        List.copyOf(people)));
    }

    private CollaborationDtos.ConversationCreationResponse response(
            long tenantId,
            java.util.UUID conversationId,
            boolean replay) {
        var stored = creationRepository.conversation(tenantId, conversationId);
        List<CollaborationDtos.MemberSummary> members = stored.members().stream()
                .map(member -> new CollaborationDtos.MemberSummary(
                        member.userId(),
                        member.personPublicId(),
                        member.displayName(),
                        member.emailAddress(),
                        member.role()))
                .toList();
        return new CollaborationDtos.ConversationCreationResponse(
                new CollaborationDtos.ConversationSummary(
                        stored.conversationId(),
                        stored.type(),
                        stored.name(),
                        stored.topic(),
                        stored.visibility(),
                        stored.lifecycleState(),
                        stored.createdAt(),
                        members),
                replay);
    }

    private String normalizedName(
            CollaborationDtos.ConversationType type,
            String rawName) {
        String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        int max = type == CollaborationDtos.ConversationType.CHANNEL
                ? MAX_CHANNEL_NAME
                : MAX_GROUP_NAME;
        int minimum = type == CollaborationDtos.ConversationType.CHANNEL ? 2 : 1;
        if (name.length() < minimum || name.length() > max) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    type == CollaborationDtos.ConversationType.CHANNEL
                            ? "A channel name must contain between 2 and 80 characters."
                            : "A group name must contain between 1 and 120 characters.");
        }
        return name;
    }

    private String normalizedIdempotencyKey(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (!key.equals(rawKey) || key.length() < 8 || key.length() > 120) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The idempotency key must contain between 8 and 120 non-padded characters.");
        }
        return key;
    }

    private String normalizeNullable(String value) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private void validateMemberCount(
            CollaborationDtos.ConversationType type,
            int memberCount) {
        if (memberCount > MAX_CONVERSATION_MEMBERS) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A private conversation cannot exceed 500 members.");
        }
        if (type == CollaborationDtos.ConversationType.GROUP && memberCount < 2) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A group must include the requesting user and at least one other member.");
        }
    }

    private EnumSet<CollaborationDtos.SearchType> parseTypes(String rawTypes) {
        if (rawTypes == null || rawTypes.isBlank()) {
            return EnumSet.allOf(CollaborationDtos.SearchType.class);
        }
        EnumSet<CollaborationDtos.SearchType> types = EnumSet.noneOf(
                CollaborationDtos.SearchType.class);
        for (String candidate : rawTypes.split(",")) {
            try {
                types.add(CollaborationDtos.SearchType.valueOf(
                        candidate.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Search types must be CONVERSATION, MESSAGE, or PERSON.");
            }
        }
        if (types.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "At least one search type is required.");
        }
        return types;
    }

    private String fingerprint(
            CollaborationDtos.ConversationType type,
            String name,
            String topic,
            TreeSet<Long> memberIds) {
        String canonical = type.name() + '\n'
                + name + '\n'
                + (topic == null ? "" : topic) + '\n'
                + memberIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return HexFormat.of().formatHex(sha256(canonical));
    }

    private long lockKey(long tenantId, long userId, String idempotencyKey) {
        byte[] digest = sha256(tenantId + ":" + userId + ":" + idempotencyKey);
        return ByteBuffer.wrap(digest).getLong();
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
