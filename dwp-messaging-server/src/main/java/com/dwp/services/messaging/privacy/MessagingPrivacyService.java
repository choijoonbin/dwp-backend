package com.dwp.services.messaging.privacy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.presence.MessagingPresenceLeaseControl;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class MessagingPrivacyService {
    private final MessagingPrivacyRepository repository;
    private final MessagingEventRecorder events;
    private final MessagingPresenceLeaseControl presenceLeases;

    @Autowired
    MessagingPrivacyService(
            MessagingPrivacyRepository repository,
            MessagingEventRecorder events,
            MessagingPresenceLeaseControl presenceLeases) {
        this.repository = repository;
        this.events = events;
        this.presenceLeases = presenceLeases;
    }

    @Transactional(readOnly = true)
    public MessagingPrivacyDtos.PrivacyPreference preference() {
        var subject = MessagingRequestContext.get();
        return repository.preference(subject.tenantId(), subject.userId());
    }

    @Transactional
    public MessagingPrivacyDtos.PrivacyPreference update(
            MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest request) {
        if (request == null || !request.isSharingChoiceProvided()
                || request.version() == null || request.version() < 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "At least one sharing choice and a non-negative version are required.");
        }
        var subject = MessagingRequestContext.get();
        repository.lockPresenceSharing(subject.tenantId(), subject.userId());
        var current = repository.preference(subject.tenantId(), subject.userId());
        boolean readReceiptsEnabled = request.readReceiptsEnabled() == null
                ? current.readReceiptsEnabled() : request.readReceiptsEnabled();
        boolean presenceSharingEnabled = request.presenceSharingEnabled() == null
                ? current.presenceSharingEnabled() : request.presenceSharingEnabled();
        if (repository.save(subject.tenantId(), subject.userId(),
                readReceiptsEnabled, presenceSharingEnabled, request.version()) != 1) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Messaging privacy preferences changed in another session.");
        }
        if (!presenceSharingEnabled
                && !presenceLeases.withdraw(subject.tenantId(), subject.userId())) {
            // The database update is still in this transaction. Rejecting rolls it back, so a
            // successful privacy response can never leave an older public lease behind.
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Presence privacy could not be enforced because the lease authority is unavailable.");
        }
        long version = request.version() + 1;
        repository.audit(subject.tenantId(), subject.userId(), version);
        events.privateEvent(subject, "messaging.privacy-preferences.updated", null, null,
                Map.of("version", version));
        return new MessagingPrivacyDtos.PrivacyPreference(
                readReceiptsEnabled, presenceSharingEnabled, version);
    }
}
