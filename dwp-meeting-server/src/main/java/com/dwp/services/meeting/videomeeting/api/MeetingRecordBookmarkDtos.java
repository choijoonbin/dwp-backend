package com.dwp.services.meeting.videomeeting.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeetingRecordBookmarkDtos {
    private MeetingRecordBookmarkDtos() { }

    public record BookmarkInput(@NotNull Boolean favorite,
            @NotNull @Min(0) Long expectedVersion) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static BookmarkInput fromJson(JsonNode value) {
            if (value == null || !value.isObject() || value.size() != 2
                    || !value.path("favorite").isBoolean()
                    || !value.path("expectedVersion").isIntegralNumber()
                    || !value.path("expectedVersion").canConvertToLong()
                    || value.path("expectedVersion").longValue() < 0) {
                throw new IllegalArgumentException("A boolean favorite and non-negative integer version are required.");
            }
            return new BookmarkInput(value.get("favorite").booleanValue(), value.get("expectedVersion").longValue());
        }
    }

    public record BookmarkState(UUID meetingId, boolean favorite, long version,
            OffsetDateTime updatedAt) { }

    public record BookmarkPage(List<BookmarkState> items) { }
}
