package com.dwp.services.messaging.privacy;

import com.dwp.services.messaging.presence.MessagingPresenceSharingPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.BooleanSupplier;

/**
 * Reads the durable consent row on every heartbeat.
 *
 * <p>A node-local cache is intentionally avoided: a privacy opt-out must take effect across all
 * application instances immediately, rather than after an unsafe cache TTL.
 */
@Component
public class MessagingDatabasePresenceSharingPolicy implements MessagingPresenceSharingPolicy {
    private final MessagingPrivacyRepository repository;

    public MessagingDatabasePresenceSharingPolicy(MessagingPrivacyRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean sharingEnabled(long tenantId, long userId) {
        return repository.presenceSharingEnabled(tenantId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean enforce(
            long tenantId,
            long userId,
            BooleanSupplier whenSharingEnabled,
            BooleanSupplier whenSharingDisabled) {
        repository.lockPresenceSharing(tenantId, userId);
        return repository.presenceSharingEnabled(tenantId, userId)
                ? whenSharingEnabled.getAsBoolean()
                : whenSharingDisabled.getAsBoolean();
    }
}
