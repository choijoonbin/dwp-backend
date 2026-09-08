package com.dwp.services.meeting.videomeeting.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeetingRecordRetentionDtos {
    private MeetingRecordRetentionDtos() { }

    public record ControlInput(@NotNull @Min(0) Long expectedMeetingVersion,
            @NotNull @Min(0) Long expectedPolicyVersion, @NotNull @Min(0) Long expectedControlVersion,
            @NotNull Boolean hold, @NotNull Boolean purgeAuthorized) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ControlInput parse(JsonNode node) {
            if (node == null || !node.isObject() || node.size() != 5
                    || !node.path("hold").isBoolean() || !node.path("purgeAuthorized").isBoolean()) {
                throw new IllegalArgumentException("Invalid retention control.");
            }
            for (String field : List.of("expectedMeetingVersion", "expectedPolicyVersion", "expectedControlVersion")) {
                if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToLong()
                        || node.path(field).longValue() < 0) throw new IllegalArgumentException("Invalid version.");
            }
            return new ControlInput(node.get("expectedMeetingVersion").longValue(),
                    node.get("expectedPolicyVersion").longValue(), node.get("expectedControlVersion").longValue(),
                    node.get("hold").booleanValue(), node.get("purgeAuthorized").booleanValue());
        }
    }

    public record ControlState(UUID meetingId, long meetingVersion, long policyVersion,
            long controlVersion, OffsetDateTime retentionUntil, boolean hold, boolean purgeAuthorized,
            String state, List<String> reasons, boolean authorizationAuditPublished,
            boolean workerEnabled, OffsetDateTime purgedAt) { }
}
