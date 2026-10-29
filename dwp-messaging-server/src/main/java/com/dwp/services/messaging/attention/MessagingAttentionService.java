package com.dwp.services.messaging.attention;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class MessagingAttentionService {

    private final MessagingAttentionRepository repository;

    MessagingAttentionService(MessagingAttentionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MessagingAttentionDtos.ActivityResponse activity(int limit) {
        int resolvedLimit = validatedLimit(limit);
        var subject = MessagingRequestContext.get();
        return new MessagingAttentionDtos.ActivityResponse(
                OffsetDateTime.now(),
                repository.activity(subject.tenantId(), subject.userId(), resolvedLimit));
    }

    @Transactional(readOnly = true)
    public MessagingAttentionDtos.ThreadResponse threads(int limit) {
        int resolvedLimit = validatedLimit(limit);
        var subject = MessagingRequestContext.get();
        return new MessagingAttentionDtos.ThreadResponse(
                OffsetDateTime.now(),
                repository.threads(subject.tenantId(), subject.userId(), resolvedLimit));
    }

    private int validatedLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Messaging attention limit must be between 1 and 100.");
        }
        return limit;
    }
}
