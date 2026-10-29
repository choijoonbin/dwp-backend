package com.dwp.services.messaging.later;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class MessagingLaterService {

    private static final Set<String> FILTERS = Set.of("ALL", "SAVED", "SNOOZED", "DUE", "COMPLETED");
    private final MessagingLaterRepository repository;

    MessagingLaterService(MessagingLaterRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MessagingLaterDtos.LaterPage items(
            String state,
            String query,
            int page,
            int pageSize) {
        var subject = MessagingRequestContext.get();
        String resolvedState = filter(state);
        String resolvedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (resolvedQuery.length() > 200) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Later search is too long.");
        }
        int resolvedPage = Math.max(0, page);
        int resolvedPageSize = Math.max(1, Math.min(100, pageSize));
        repository.promoteDue(subject.tenantId(), subject.userId());
        return new MessagingLaterDtos.LaterPage(
                repository.items(
                        subject.tenantId(), subject.userId(), resolvedState, resolvedQuery,
                        resolvedPage, resolvedPageSize),
                repository.count(subject.tenantId(), subject.userId(), resolvedState, resolvedQuery),
                resolvedPage,
                resolvedPageSize,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public MessagingLaterDtos.LaterItem update(
            UUID messageId,
            MessagingLaterDtos.UpdateLaterRequest request,
            String correlationId) {
        var subject = MessagingRequestContext.get();
        MessagingLaterDtos.LaterItem current = repository.item(
                        subject.tenantId(), subject.userId(), messageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The Later item was not found or is no longer visible."));
        MessagingLaterDtos.LaterState next = requestedState(request.state());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime reminderAt = request.reminderAt();
        OffsetDateTime snoozedUntil = request.snoozedUntil();
        OffsetDateTime completedAt = null;

        if (next == MessagingLaterDtos.LaterState.SNOOZED) {
            if (snoozedUntil == null || !snoozedUntil.isAfter(now)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE, "A snooze time in the future is required.");
            }
            if (reminderAt == null) reminderAt = snoozedUntil;
            if (reminderAt.isAfter(snoozedUntil)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE, "The reminder cannot be after the snooze ends.");
            }
        } else if (next == MessagingLaterDtos.LaterState.COMPLETED) {
            completedAt = now;
            reminderAt = null;
            snoozedUntil = null;
        } else {
            reminderAt = null;
            snoozedUntil = null;
        }

        int updated = repository.update(
                subject.tenantId(), subject.userId(), messageId, next,
                reminderAt, snoozedUntil, completedAt, request.version());
        if (updated != 1) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Later item changed. Refresh it before applying another action.");
        }
        repository.audit(
                subject.tenantId(), subject.userId(), messageId,
                current.state(), next, correlationId);
        return repository.item(subject.tenantId(), subject.userId(), messageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The Later item is no longer visible."));
    }

    private String filter(String value) {
        String resolved = value == null ? "ALL" : value.strip().toUpperCase(Locale.ROOT);
        if (!FILTERS.contains(resolved)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported Later state.");
        }
        return resolved;
    }

    private MessagingLaterDtos.LaterState requestedState(String value) {
        try {
            MessagingLaterDtos.LaterState state = MessagingLaterDtos.LaterState.valueOf(
                    value.strip().toUpperCase(Locale.ROOT));
            if (state == MessagingLaterDtos.LaterState.DUE) throw new IllegalArgumentException();
            return state;
        } catch (IllegalArgumentException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Later state must be SAVED, SNOOZED, or COMPLETED.");
        }
    }
}
