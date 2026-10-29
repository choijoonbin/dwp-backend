package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MessagingContinuityService {

    private static final Set<String> SCHEDULE_STATUSES =
            Set.of("PENDING", "PROCESSING", "SENT", "FAILED", "CANCELLED");

    private final MessagingContinuityRepository continuity;
    private final MessagingService messaging;
    private final MessagingCommandRepository commands;
    private final Clock clock;

    @Autowired
    MessagingContinuityService(
            MessagingContinuityRepository continuity,
            MessagingService messaging,
            MessagingCommandRepository commands) {
        this(continuity, messaging, commands, Clock.systemUTC());
    }

    MessagingContinuityService(
            MessagingContinuityRepository continuity,
            MessagingService messaging,
            MessagingCommandRepository commands,
            Clock clock) {
        this.continuity = continuity;
        this.messaging = messaging;
        this.commands = commands;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MessagingContinuityDtos.DraftResponse draft(
            UUID conversationId, UUID threadRootMessageId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        messaging.validateDraft(conversationId, threadRootMessageId, "", List.of());
        return continuity.draft(
                        subject.tenantId(), subject.userId(), conversationId, threadRootMessageId)
                .orElseGet(() -> new MessagingContinuityDtos.DraftResponse(
                        conversationId, threadRootMessageId, "", List.of(), 0, null));
    }

    @Transactional
    public MessagingContinuityDtos.DraftResponse saveDraft(
            UUID conversationId,
            UUID threadRootMessageId,
            MessagingContinuityDtos.SaveDraftRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        List<Long> mentionedUserIds = resolveMentionedUserIds(
                subject.tenantId(), request.mentionedPersonPublicIds());
        messaging.validateDraft(
                conversationId, threadRootMessageId, request.body(), mentionedUserIds);
        boolean saved = request.expectedVersion() == 0
                ? continuity.insertDraft(
                        subject.tenantId(), subject.userId(), conversationId,
                        threadRootMessageId, request.body(), mentionedUserIds)
                : continuity.updateDraft(
                        subject.tenantId(), subject.userId(), conversationId,
                        threadRootMessageId, request.body(), mentionedUserIds,
                        request.expectedVersion()) == 1;
        if (!saved) throw draftConflict();
        return continuity.draft(
                        subject.tenantId(), subject.userId(), conversationId, threadRootMessageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "The draft could not be read after it was saved."));
    }

    @Transactional
    public void deleteDraft(
            UUID conversationId, UUID threadRootMessageId, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Draft version must be zero or greater.");
        }
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        messaging.validateDraft(conversationId, threadRootMessageId, "", List.of());
        if (expectedVersion == 0) {
            if (continuity.draft(subject.tenantId(), subject.userId(), conversationId,
                    threadRootMessageId).isEmpty()) return;
            throw draftConflict();
        }
        if (continuity.deleteDraft(
                subject.tenantId(), subject.userId(), conversationId,
                threadRootMessageId, expectedVersion) != 1) {
            throw draftConflict();
        }
    }

    @Transactional
    public MessagingContinuityDtos.ScheduledMessageResponse schedule(
            UUID conversationId,
            MessagingContinuityDtos.ScheduleMessageRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        if (!request.attachmentIds().isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Attachments cannot be scheduled until governed attachment reservation is available.");
        }
        String displayTimeZone = validZone(request.displayTimeZone());
        OffsetDateTime scheduledFor = request.scheduledFor().withOffsetSameInstant(ZoneOffset.UTC);
        String body = MessagingCommandRepository.normalizeBody(request.body());
        String requestHash = scheduleHash(
                conversationId, body, request.replyToMessageId(),
                request.mentionedPersonPublicIds(), scheduledFor, displayTimeZone);
        var existing = continuity.scheduledByRequestKey(
                subject.tenantId(), subject.userId(), request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(requestHash, existing.get());
        }

        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (!scheduledFor.isAfter(now)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Scheduled delivery time must be in the future.");
        }
        if (scheduledFor.isAfter(now.plusYears(1))) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Scheduled delivery time cannot be more than one year in the future.");
        }
        List<Long> mentionedUserIds = resolveMentionedUserIds(
                subject.tenantId(), request.mentionedPersonPublicIds());
        messaging.validateScheduledMessage(
                conversationId, body, request.replyToMessageId(), mentionedUserIds);

        var command = new MessagingContinuityRepository.ScheduledCommand(
                UUID.randomUUID(), subject.tenantId(), subject.userId(), conversationId,
                body, request.replyToMessageId(), mentionedUserIds,
                request.idempotencyKey(), UUID.randomUUID(), requestHash,
                scheduledFor, displayTimeZone);
        boolean created = continuity.insertScheduled(command);
        var stored = continuity.scheduledByRequestKey(
                        subject.tenantId(), subject.userId(), request.idempotencyKey())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "The scheduled delivery command could not be read after creation."));
        MessagingContinuityDtos.ScheduledMessageResponse response = replay(requestHash, stored);
        if (created) {
            commands.audit(
                    subject.tenantId(), subject.userId(), "messaging.message.scheduled",
                    "MSG_SCHEDULED_MESSAGE",
                    stored.response().scheduledMessageId().toString(), correlationId,
                    Map.of(
                            "conversationId", conversationId,
                            "scheduledFor", scheduledFor,
                            "displayTimeZone", displayTimeZone));
        }
        return response;
    }

    @Transactional(readOnly = true)
    public MessagingContinuityDtos.ScheduledMessagePage scheduled(
            String status, String cursor, int limit) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : status.trim().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !SCHEDULE_STATUSES.contains(normalizedStatus)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Scheduled message status is not supported.");
        }
        int resolvedLimit = Math.max(1, Math.min(100, limit));
        String statusKey = normalizedStatus == null ? "ALL" : normalizedStatus;
        MessagingScheduledCursor.Cursor resolvedCursor =
                MessagingScheduledCursor.decode(
                        cursor, subject.tenantId(), subject.userId(), statusKey);
        List<MessagingContinuityDtos.ScheduledMessageResponse> rows = continuity.scheduled(
                subject.tenantId(), subject.userId(), normalizedStatus,
                resolvedCursor, resolvedLimit + 1);
        boolean hasMore = rows.size() > resolvedLimit;
        List<MessagingContinuityDtos.ScheduledMessageResponse> items = hasMore
                ? rows.subList(0, resolvedLimit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
                ? MessagingScheduledCursor.encode(
                        items.getLast(), subject.tenantId(), subject.userId(), statusKey)
                : null;
        return new MessagingContinuityDtos.ScheduledMessagePage(
                List.copyOf(items), resolvedLimit, hasMore, nextCursor);
    }

    @Transactional(readOnly = true)
    public MessagingContinuityDtos.ScheduledMessageResponse scheduled(UUID scheduledMessageId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        return continuity.scheduled(subject.tenantId(), subject.userId(), scheduledMessageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The scheduled message was not found."));
    }

    @Transactional
    public MessagingContinuityDtos.ScheduledMessageResponse cancel(
            UUID scheduledMessageId, long expectedVersion, String correlationId) {
        if (expectedVersion < 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Scheduled message version must be zero or greater.");
        }
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        continuity.scheduled(subject.tenantId(), subject.userId(), scheduledMessageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The scheduled message was not found."));
        var cancelled = continuity.cancel(
                        subject.tenantId(), subject.userId(), scheduledMessageId, expectedVersion)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The scheduled message changed or delivery has already started."));
        commands.audit(
                subject.tenantId(), subject.userId(), "messaging.message.schedule-cancelled",
                "MSG_SCHEDULED_MESSAGE", scheduledMessageId.toString(), correlationId,
                Map.of("version", cancelled.version()));
        return cancelled;
    }

    private String validZone(String value) {
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "displayTimeZone must be a valid IANA time zone.");
        }
    }

    private List<Long> resolveMentionedUserIds(
            long tenantId, List<UUID> mentionedPersonPublicIds) {
        if (mentionedPersonPublicIds.isEmpty()) return List.of();
        Map<UUID, MessagingContinuityRepository.MentionIdentity> identitiesByPublicId =
                continuity.activeMentionIdentities(tenantId, mentionedPersonPublicIds).stream()
                        .collect(Collectors.toUnmodifiableMap(
                                MessagingContinuityRepository.MentionIdentity::personPublicId,
                                Function.identity()));
        if (identitiesByPublicId.size() != mentionedPersonPublicIds.size()
                || !identitiesByPublicId.keySet().containsAll(mentionedPersonPublicIds)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "One or more mentioned people are unavailable.");
        }
        return mentionedPersonPublicIds.stream()
                .map(identitiesByPublicId::get)
                .map(MessagingContinuityRepository.MentionIdentity::userId)
                .distinct()
                .sorted()
                .toList();
    }

    private MessagingContinuityDtos.ScheduledMessageResponse replay(
            String requestHash,
            MessagingContinuityRepository.ScheduledRecord stored) {
        if (!requestHash.equals(stored.requestHash())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The schedule idempotency key was already used with different content or timing.");
        }
        return stored.response();
    }

    static String scheduleHash(
            UUID conversationId,
            String body,
            UUID replyToMessageId,
            List<UUID> mentionedPersonPublicIds,
            OffsetDateTime scheduledFor,
            String displayTimeZone) {
        List<UUID> canonicalMentionIds = mentionedPersonPublicIds.stream()
                .distinct()
                .sorted()
                .toList();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String canonical = "scheduled-message-request-v2"
                + "\nconversation=" + conversationId
                + "\nreply-to=" + (replyToMessageId == null ? "" : replyToMessageId)
                + "\nmentions=" + canonicalMentionIds.stream()
                        .map(UUID::toString)
                        .collect(Collectors.joining(","))
                + "\nbody-bytes=" + bodyBytes.length
                + "\nbody=" + body
                + "\nscheduled-for=" + scheduledFor.withOffsetSameInstant(ZoneOffset.UTC)
                + "\ndisplay-zone=" + displayTimeZone;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private BaseException draftConflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The server draft changed in another session. Reload it before saving again.");
    }
}
