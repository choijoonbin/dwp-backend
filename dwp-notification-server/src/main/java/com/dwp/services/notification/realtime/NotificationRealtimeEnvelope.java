package com.dwp.services.notification.realtime;

import com.dwp.services.notification.api.NotificationVersionCodec;

import java.util.List;
import java.util.UUID;

/** Content-free cross-replica hint. Durable notification state always remains in PostgreSQL. */
public record NotificationRealtimeEnvelope(
        long tenantId,
        long userId,
        String changeVersion,
        String counterVersion,
        List<UUID> changedIds) {

    public NotificationRealtimeEnvelope {
        if (tenantId < 1 || userId < 1) {
            throw new IllegalArgumentException("Realtime signal identity must be positive.");
        }
        NotificationVersionCodec.nonNegative(changeVersion, "changeVersion");
        NotificationVersionCodec.nonNegative(counterVersion, "counterVersion");
        changedIds = List.copyOf(changedIds);
        if (changedIds.isEmpty()
                || changedIds.size() > 100
                || changedIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Realtime signal IDs must contain 1 to 100 UUIDs.");
        }
    }
}
