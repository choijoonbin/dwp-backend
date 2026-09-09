package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.DecimalVersionStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

public final class NotificationDeliveryEndpointModels {

    private NotificationDeliveryEndpointModels() {
    }

    public record DeliveryEndpoint(
            UUID endpointId,
            String channel,
            String displayName,
            String platform,
            String endpointHint,
            String state,
            Instant lastSeenAt,
            Instant createdAt,
            Instant revokedAt,
            String version) {
    }

    public record DeliveryEndpointRevokeRequest(
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion) {
    }
}
