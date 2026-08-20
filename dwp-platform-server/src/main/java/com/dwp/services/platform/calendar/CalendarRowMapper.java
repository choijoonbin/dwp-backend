package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

final class CalendarRowMapper {

    private final ObjectMapper objectMapper;

    CalendarRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CalendarRepository.EventRow event(ResultSet result) throws SQLException {
        UUID resourceId = result.getObject("resource_id", UUID.class);
        CalendarRepository.ResourceRow resource = resourceId == null ? null
                : new CalendarRepository.ResourceRow(
                        resourceId,
                        result.getString("resource_code"),
                        result.getString("resource_name"),
                        result.getString("resource_name_ko"),
                        result.getString("resource_name_en"),
                        ResourceType.valueOf(result.getString("resource_type")),
                        result.getString("site_name"),
                        result.getString("floor_name"),
                        result.getInt("capacity"),
                        stringList(result.getString("features")),
                        result.getString("time_zone"),
                        result.getBoolean("approval_required"),
                        ResourceState.valueOf(result.getString("resource_state")),
                        true,
                        0);
        String response = result.getString("my_response");
        return new CalendarRepository.EventRow(
                result.getObject("event_id", UUID.class),
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_name"),
                result.getString("color_hex"),
                result.getLong("organizer_user_id"),
                result.getObject("organizer_person_public_id", UUID.class),
                result.getString("organizer_name"),
                result.getString("organizer_email"),
                result.getString("title"),
                result.getString("description"),
                EventType.valueOf(result.getString("event_type")),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getString("time_zone"),
                result.getBoolean("all_day"),
                result.getString("location"),
                result.getString("conference_url"),
                EventStatus.valueOf(result.getString("status")),
                EventVisibility.valueOf(result.getString("visibility")),
                RecurrencePattern.valueOf(result.getString("recurrence_pattern")),
                result.getInt("recurrence_interval"),
                result.getObject("recurrence_until", LocalDate.class),
                result.getBoolean("response_required"),
                response == null ? null : ResponseStatus.valueOf(response),
                resource,
                result.getLong("version"));
    }

    CalendarRepository.ResourceRow resource(ResultSet result) throws SQLException {
        return new CalendarRepository.ResourceRow(
                result.getObject("resource_id", UUID.class),
                result.getString("resource_code"),
                result.getString("name"),
                result.getString("name_ko"),
                result.getString("name_en"),
                ResourceType.valueOf(result.getString("resource_type")),
                result.getString("site_name"),
                result.getString("floor_name"),
                result.getInt("capacity"),
                stringList(result.getString("features")),
                result.getString("time_zone"),
                result.getBoolean("approval_required"),
                ResourceState.valueOf(result.getString("lifecycle_state")),
                result.getBoolean("available"),
                result.getLong("version"));
    }

    CalendarRepository.PolicyRow policy(ResultSet result) throws SQLException {
        return new CalendarRepository.PolicyRow(
                result.getInt("week_start"),
                result.getObject("working_day_start", LocalTime.class),
                result.getObject("working_day_end", LocalTime.class),
                result.getInt("default_event_minutes"),
                result.getInt("minimum_event_minutes"),
                result.getInt("maximum_event_minutes"),
                result.getInt("maximum_advance_days"),
                result.getInt("default_buffer_minutes"),
                result.getInt("weekly_focus_target_minutes"),
                result.getInt("daily_meeting_limit_minutes"),
                result.getBoolean("enforce_meeting_agenda"),
                result.getBoolean("allow_external_attendees"),
                result.getLong("version"));
    }

    static CalendarRepository.PolicyRow defaultPolicy() {
        return new CalendarRepository.PolicyRow(
                1, LocalTime.of(9, 0), LocalTime.of(18, 0), 30, 15, 480,
                365, 10, 600, 300, false, true, 0);
    }

    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Calendar JSON data is invalid.", exception);
        }
    }
}
