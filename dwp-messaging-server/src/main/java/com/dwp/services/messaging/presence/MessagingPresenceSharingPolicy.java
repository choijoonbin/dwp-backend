package com.dwp.services.messaging.presence;

import java.util.function.BooleanSupplier;

/** Durable, self-owned consent boundary evaluated before a presence lease is renewed. */
@FunctionalInterface
public interface MessagingPresenceSharingPolicy {
    MessagingPresenceSharingPolicy ALLOW_ALL = (tenantId, userId) -> true;

    boolean sharingEnabled(long tenantId, long userId);

    /**
     * Executes the Redis action while the implementation's durable decision lock is held.
     * Database-backed implementations override this method to close the opt-out/renewal race.
     */
    default boolean enforce(
            long tenantId,
            long userId,
            BooleanSupplier whenSharingEnabled,
            BooleanSupplier whenSharingDisabled) {
        return sharingEnabled(tenantId, userId)
                ? whenSharingEnabled.getAsBoolean()
                : whenSharingDisabled.getAsBoolean();
    }
}
