package com.dwp.services.notification.domain;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationTestDeliveryModels {

    private NotificationTestDeliveryModels() {
    }

    public record TestDeliveryRequest(
            @NotEmpty @Size(max = 6)
            List<@NotNull @Pattern(
                    regexp = "IN_APP|EMAIL|WEB_PUSH|MOBILE_PUSH|TEAMS|SLACK") String> channels) {

        public TestDeliveryRequest {
            channels = channels == null ? List.of() : List.copyOf(channels);
        }
    }

    public record TestDelivery(
            UUID testId,
            String state,
            List<String> requestedChannels,
            List<TestDeliveryStage> stages,
            Instant createdAt,
            Instant expiresAt,
            Integer retryAfterSeconds) {

        public TestDelivery {
            requestedChannels = List.copyOf(requestedChannels);
            stages = List.copyOf(stages);
        }
    }

    public record TestDeliveryStage(
            String stage,
            String state,
            String detail,
            Instant occurredAt) {
    }

    record TestDeliveryRate(long lastMinute, long lastDay) {
    }
}
