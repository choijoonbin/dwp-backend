package com.dwp.services.platform.calendar;

import com.dwp.services.platform.workplace.WorkplaceExperienceCalendarClosureBridge;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Owns room/resource booking persistence behind the aggregate repository facade. */
final class CalendarResourceBookingRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CalendarRowMapper rows;

    CalendarResourceBookingRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CalendarRowMapper rows) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rows = rows;
    }

    List<CalendarRepositoryRows.ResourceRow> resources(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean,
            boolean includeRetired) {
        Set<UUID> closed = WorkplaceExperienceCalendarClosureBridge.closedResources(
                jdbc, tenantId, from, to);
        return jdbc.query(
                CalendarSql01.RESOURCES_SELECT_CAL_RESOURCE_BOOKINGS,
                (result, ignored) -> rows.closureAvailability(
                        rows.resource(result, CalendarRepositoryRows.ResourceRow::new),
                        closed,
                        CalendarRepositoryRows.ResourceRow::new),
                korean, from, to, to, from, tenantId, includeRetired);
    }

    Optional<CalendarRepositoryRows.ResourceRow> resource(
            Long tenantId,
            UUID resourceId,
            boolean korean) {
        OffsetDateTime now = OffsetDateTime.now();
        return resources(tenantId, now, now.plusMinutes(1), korean, true).stream()
                .filter(value -> value.resourceId().equals(resourceId))
                .findFirst();
    }

    boolean resourceConflict(
            Long tenantId,
            UUID resourceId,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID excludingEventId) {
        Long count = jdbc.queryForObject(
                CalendarSql01.RESOURCE_CONFLICT_SELECT_CAL_RESOURCE_BOOKINGS,
                Long.class,
                from, to, tenantId, resourceId,
                excludingEventId, excludingEventId, to, from);
        return count != null && count > 0;
    }

    void lockResource(Long tenantId, UUID resourceId) {
        jdbc.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                Integer.class,
                tenantId + ":" + resourceId);
        WorkplaceExperienceCalendarClosureBridge.lockMappedResource(jdbc, tenantId, resourceId);
    }

    boolean facilityClosureConflict(
            Long tenant,
            UUID resource,
            OffsetDateTime from,
            OffsetDateTime to) {
        return WorkplaceExperienceCalendarClosureBridge.conflict(
                jdbc, tenant, resource, from, to);
    }

    int lockedRoomCapacity(Long tenantId, UUID resourceId) {
        return CalendarRoomCapacity.locked(jdbc, tenantId, resourceId);
    }

    void insertBooking(
            Long tenantId,
            Long userId,
            UUID eventId,
            CalendarRepositoryRows.ResourceRow resource,
            OffsetDateTime from,
            OffsetDateTime to) {
        jdbc.update(
                CalendarSql01.INSERT_BOOKING_INSERT_CAL_RESOURCE_BOOKINGS,
                tenantId,
                eventId,
                resource.resourceId(),
                from,
                to,
                resource.approvalRequired() ? "PENDING" : "CONFIRMED",
                userId,
                userId,
                userId);
    }

    List<CalendarRepositoryRows.BookingRow> pendingBookings(Long tenantId, boolean korean) {
        return bookingRows(CalendarSql01.PENDING_BOOKINGS_SQL_STATEMENT, korean, tenantId);
    }

    Optional<CalendarRepositoryRows.BookingRow> booking(
            Long tenantId,
            UUID bookingId,
            boolean korean) {
        return bookingRows(
                CalendarSql01.BOOKING_SQL_STATEMENT,
                korean,
                tenantId,
                bookingId).stream().findFirst();
    }

    CalendarRepositoryRows.BookingRow decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String status,
            String note,
            long version,
            boolean korean) {
        int updated = jdbc.update(
                CalendarSql01.DECIDE_BOOKING_UPDATE_CAL_RESOURCE_BOOKINGS,
                status,
                blankToNull(note),
                actorId,
                actorId,
                tenantId,
                bookingId,
                version);
        return updated == 0 ? null : booking(tenantId, bookingId, korean).orElse(null);
    }

    void rescheduleBooking(
            Long tenantId,
            Long userId,
            UUID eventId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean approvalRequired) {
        jdbc.update(
                CalendarSql01.RESCHEDULE_BOOKING_UPDATE_CAL_RESOURCE_BOOKINGS,
                from,
                to,
                approvalRequired ? "PENDING" : "CONFIRMED",
                userId,
                userId,
                tenantId,
                eventId);
    }

    void cancelBookings(Long tenantId, Long userId, UUID eventId) {
        jdbc.update(
                CalendarSql01.CANCEL_BOOKINGS_UPDATE_CAL_RESOURCE_BOOKINGS,
                userId,
                tenantId,
                eventId);
    }

    CalendarRepositoryRows.ResourceRow saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            CalendarDtos.ResourceRequest value,
            boolean korean) {
        UUID id = resourceId == null ? UUID.randomUUID() : resourceId;
        if (value.version() == null) {
            jdbc.update(
                    CalendarSql01.SAVE_RESOURCE_INSERT_CAL_RESOURCES,
                    id, tenantId, value.code(), value.nameKo(), value.nameEn(),
                    value.type().name(), value.site(), blankToNull(value.floor()), value.capacity(),
                    json(value.features()), value.timeZone(), value.approvalRequired(),
                    value.state().name(), actorId, actorId);
        } else {
            int updated = jdbc.update(
                    CalendarSql01.CAL_RESOURCES_UPDATE_CAL_RESOURCES,
                    value.code(), value.nameKo(), value.nameEn(), value.type().name(),
                    value.site(), blankToNull(value.floor()), value.capacity(),
                    json(value.features()), value.timeZone(), value.approvalRequired(),
                    value.state().name(), actorId, tenantId, id, value.version());
            if (updated == 0) return null;
        }
        return resource(tenantId, id, korean).orElse(null);
    }

    boolean isWorkplaceManagedResource(Long tenantId, UUID resourceId) {
        Boolean managed = jdbc.queryForObject(
                CalendarSql01.IS_WORKPLACE_MANAGED_RESOURCE_SELECT_WP_RESOURCES,
                Boolean.class,
                tenantId,
                resourceId);
        return Boolean.TRUE.equals(managed);
    }

    boolean isWorkplaceResourceBookable(Long tenantId, UUID resourceId) {
        Boolean bookable = jdbc.queryForObject(
                CalendarSql01.IS_WORKPLACE_RESOURCE_BOOKABLE_SELECT_WP_RESOURCES,
                Boolean.class,
                tenantId,
                resourceId,
                tenantId,
                resourceId);
        return Boolean.TRUE.equals(bookable);
    }

    CalendarRepositoryRows.AdminStats adminStats(
            Long tenantId,
            OffsetDateTime weekStart,
            OffsetDateTime weekEnd) {
        return jdbc.queryForObject(
                CalendarSql01.ADMIN_STATS_WITH_CAL_EVENTS,
                (result, ignored) -> new CalendarRepositoryRows.AdminStats(
                        result.getLong("active_resources"),
                        result.getLong("maintenance_resources"),
                        result.getLong("bookings_this_week"),
                        result.getLong("pending_bookings"),
                        result.getLong("events_this_week"),
                        result.getLong("conflicted_users")),
                weekStart, weekEnd, tenantId, weekEnd, tenantId,
                tenantId, tenantId, weekEnd, weekStart, tenantId,
                weekEnd, weekStart, weekEnd, weekStart);
    }

    private List<CalendarRepositoryRows.BookingRow> bookingRows(
            String predicate,
            boolean korean,
            Object... parameters) {
        Object[] values = new Object[parameters.length + 1];
        values[0] = korean;
        System.arraycopy(parameters, 0, values, 1, parameters.length);
        return jdbc.query(
                CalendarSql01.BOOKING_ROWS_SELECT_CAL_RESOURCE_BOOKINGS + predicate,
                (result, ignored) -> new CalendarRepositoryRows.BookingRow(
                        result.getObject("booking_id", UUID.class),
                        result.getObject("event_id", UUID.class),
                        result.getObject("resource_id", UUID.class),
                        result.getString("resource_name"),
                        result.getString("title"),
                        result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class),
                        result.getString("organizer_name"),
                        result.getString("organizer_email"),
                        result.getString("booking_status"),
                        result.getLong("requested_by"),
                        result.getString("decision_note"),
                        result.getObject("decided_at", OffsetDateTime.class),
                        nullableLong(result, "decided_by"),
                        result.getLong("version")),
                values);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Calendar data could not be serialized.", exception);
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
