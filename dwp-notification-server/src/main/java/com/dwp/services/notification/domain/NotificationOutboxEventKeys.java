package com.dwp.services.notification.domain;

import java.util.UUID;

final class NotificationOutboxEventKeys {

    private NotificationOutboxEventKeys() {
    }

    static String materialized(UUID sourceEventId, UUID intentId) {
        return "materialized:" + sourceEventId + ":" + intentId;
    }
}
