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
        List<UUID> changedIds,
        List<UUID> arrivalIds) {

    public NotificationRealtimeEnvelope {
        if (tenantId < 1 || userId < 1) {
            throw new IllegalArgumentException("Realtime signal identity must be positive.");
        }
        NotificationVersionCodec.nonNegative(changeVersion, "changeVersion");
        NotificationVersionCodec.nonNegative(counterVersion, "counterVersion");
        changedIds = List.copyOf(changedIds);
        arrivalIds = arrivalIds == null ? List.of() : List.copyOf(arrivalIds);
        if (changedIds.isEmpty()
                || changedIds.size() > 100
                || changedIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Realtime signal IDs must contain 1 to 100 UUIDs.");
        }
        if (arrivalIds.size() > 100
                || arrivalIds.stream().anyMatch(java.util.Objects::isNull)
                || !changedIds.containsAll(arrivalIds)) {
            throw new IllegalArgumentException(
                    "Realtime arrival IDs must be a subset of changed IDs.");
        }
    }
}
