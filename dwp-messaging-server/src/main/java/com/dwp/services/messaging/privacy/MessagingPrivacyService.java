package com.dwp.services.messaging.privacy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class MessagingPrivacyService {
    private final MessagingPrivacyRepository repository;
    private final MessagingEventRecorder events;

    MessagingPrivacyService(MessagingPrivacyRepository repository, MessagingEventRecorder events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public MessagingPrivacyDtos.PrivacyPreference preference() {
        var subject = MessagingRequestContext.get();
        return repository.preference(subject.tenantId(), subject.userId());
    }

    @Transactional
    public MessagingPrivacyDtos.PrivacyPreference update(
            MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest request) {
        if (request == null || request.readReceiptsEnabled() == null
                || request.version() == null || request.version() < 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A sharing choice and non-negative version are required.");
        }
        var subject = MessagingRequestContext.get();
        if (repository.save(subject.tenantId(), subject.userId(),
                request.readReceiptsEnabled(), request.version()) != 1) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Messaging privacy preferences changed in another session.");
        }
        long version = request.version() + 1;
        repository.audit(subject.tenantId(), subject.userId(), version);
        events.privateEvent(subject, "messaging.privacy-preferences.updated", null, null,
                Map.of("version", version));
        return new MessagingPrivacyDtos.PrivacyPreference(request.readReceiptsEnabled(), version);
    }
}
