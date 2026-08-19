package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

@Repository
class WorkplaceBookingRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    WorkplaceBookingRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    List<OccupancyRow> occupancy(
            Long tenantId,
            Long userId,
            UUID floorId,
            OffsetDateTime from,
            OffsetDateTime to) {
        return jdbc.query("""
                SELECT resource.resource_id, booking.booking_id,
                       booking.booking_status AS status, booking.starts_at, booking.ends_at,
                       CASE WHEN booking.visible_to_colleagues OR booking.user_id = ?
                            THEN booking.booked_for_display_name END AS booked_by_display_name,
                       booking.user_id = ? AS current_user
                  FROM wp_bookings booking
                  JOIN wp_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                 WHERE booking.tenant_id = ? AND resource.floor_id = ?
                   AND booking.booking_status IN ('RESERVED', 'CHECKED_IN')
                   AND booking.starts_at < ? AND booking.ends_at > ?
                UNION ALL
                SELECT resource.resource_id, booking.booking_id,
                       'RESERVED' AS status, booking.starts_at, booking.ends_at,
                       CASE WHEN event.visibility = 'PUBLIC' OR event.organizer_user_id = ?
                            THEN event.organizer_name END AS booked_by_display_name,
                       event.organizer_user_id = ? AS current_user
                  FROM wp_resources resource
                  JOIN cal_resource_bookings booking
                    ON booking.tenant_id = resource.tenant_id
                   AND booking.resource_id = resource.calendar_resource_id
                  JOIN cal_events event
                    ON event.tenant_id = booking.tenant_id
                   AND event.event_id = booking.event_id
                 WHERE resource.tenant_id = ? AND resource.floor_id = ?
                   AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                   AND event.status <> 'CANCELLED'
                   AND booking.starts_at < ? AND booking.ends_at > ?
                 ORDER BY starts_at, resource_id
                """, (result, ignored) -> occupancy(result),
                userId, userId, tenantId, floorId, to, from,
                userId, userId, tenantId, floorId, to, from);
    }

    List<BookingRow> bookings(
            Long tenantId,
            Long userId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean) {
        return jdbc.query("""
                SELECT booking.*, resource.resource_type,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS resource_name,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS site_name,
                       CASE WHEN ? THEN floor.name_ko ELSE floor.name_en END AS floor_name
                  FROM wp_bookings booking
                  JOIN wp_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                  JOIN wp_floors floor ON floor.floor_id = resource.floor_id
                  JOIN wp_sites site ON site.site_id = floor.site_id
                 WHERE booking.tenant_id = ? AND booking.user_id = ?
                   AND booking.starts_at < ? AND booking.ends_at > ?
                 ORDER BY booking.starts_at, resource_name
                """, (result, ignored) -> booking(result),
                korean, korean, korean, tenantId, userId, to, from);
    }

    Optional<BookingRow> booking(
            Long tenantId,
            Long userId,
            UUID bookingId,
            boolean korean) {
        return jdbc.query("""
                SELECT booking.*, resource.resource_type,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS resource_name,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS site_name,
                       CASE WHEN ? THEN floor.name_ko ELSE floor.name_en END AS floor_name
                  FROM wp_bookings booking
                  JOIN wp_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                  JOIN wp_floors floor ON floor.floor_id = resource.floor_id
                  JOIN wp_sites site ON site.site_id = floor.site_id
                 WHERE booking.tenant_id = ? AND booking.user_id = ? AND booking.booking_id = ?
                """, (result, ignored) -> booking(result),
                korean, korean, korean, tenantId, userId, bookingId).stream().findFirst();
    }

    BookingRow createBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String displayName,
            WorkplaceDtos.BookingRequest value,
            boolean korean) {
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_bookings (
                    booking_id, tenant_id, resource_id, user_id, person_public_id,
                    booked_for_display_name, purpose, starts_at, ends_at,
                    visible_to_colleagues, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bookingId, tenantId, value.resourceId(), userId, personPublicId,
                displayName, blank(value.purpose()), value.startsAt(), value.endsAt(),
                value.visibleToColleagues(), userId, userId);
        return booking(tenantId, userId, bookingId, korean).orElseThrow();
    }

    void lockUserBookingScope(Long tenantId, Long userId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, "workplace:" + tenantId + ":" + userId),
                result -> null);
    }

    boolean tryLifecycleLock() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('workplace-booking-lifecycle'))",
                Boolean.class);
        return Boolean.TRUE.equals(acquired);
    }

    int activeBookingCount(Long tenantId, Long userId, OffsetDateTime now) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_bookings
                 WHERE tenant_id = ? AND user_id = ? AND ends_at > ?
                   AND booking_status IN ('RESERVED', 'CHECKED_IN')
                """, Integer.class, tenantId, userId, now);
        return value == null ? 0 : value;
    }

    boolean userHasConflict(
            Long tenantId,
            Long userId,
            OffsetDateTime from,
            OffsetDateTime to) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_bookings
                     WHERE tenant_id = ? AND user_id = ?
                       AND booking_status IN ('RESERVED', 'CHECKED_IN')
                       AND starts_at < ? AND ends_at > ?)
                """, Boolean.class, tenantId, userId, to, from);
        return Boolean.TRUE.equals(value);
    }

    int checkIn(
            Long tenantId,
            Long userId,
            UUID bookingId,
            Long version,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_bookings
                   SET booking_status = 'CHECKED_IN', checked_in_at = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND booking_id = ? AND version = ?
                   AND booking_status = 'RESERVED'
                """, now, userId, tenantId, userId, bookingId, version);
    }

    int cancel(
            Long tenantId,
            Long userId,
            UUID bookingId,
            Long version,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_bookings
                   SET booking_status = 'CANCELLED', cancelled_at = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND booking_id = ? AND version = ?
                   AND booking_status = 'RESERVED'
                """, now, userId, tenantId, userId, bookingId, version);
    }

    int release(
            Long tenantId,
            Long userId,
            UUID bookingId,
            Long version,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_bookings
                   SET booking_status = 'RELEASED', released_at = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND booking_id = ? AND version = ?
                   AND booking_status IN ('RESERVED', 'CHECKED_IN')
                """, now, userId, tenantId, userId, bookingId, version);
    }

    int releaseNoShows(Long tenantId, OffsetDateTime now) {
        return releaseNoShows(now, "AND booking.tenant_id = ?", tenantId);
    }

    int releaseNoShows(OffsetDateTime now) {
        return releaseNoShows(now, "", null);
    }

    private int releaseNoShows(OffsetDateTime now, String tenantPredicate, Long tenantId) {
        String sql = """
                WITH released AS (
                    UPDATE wp_bookings booking
                       SET booking_status = 'NO_SHOW', released_at = ?,
                           version = booking.version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = 0
                      FROM wp_tenant_policies policy
                     WHERE policy.tenant_id = booking.tenant_id
                       %s
                       AND policy.require_check_in = TRUE
                       AND booking.booking_status = 'RESERVED'
                       AND booking.starts_at + make_interval(mins => policy.auto_release_minutes) < ?
                    RETURNING booking.tenant_id, booking.booking_id, booking.user_id,
                              booking.released_at
                ), audited AS (
                    INSERT INTO wp_audit_events (
                        tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                        correlation_id, snapshot)
                    SELECT tenant_id, 'workplace.booking.no_show', 'BOOKING', booking_id,
                           0, 'workplace:no-show-sweep',
                           jsonb_build_object('userId', user_id, 'releasedAt', released_at)
                      FROM released
                    RETURNING audit_event_id
                )
                SELECT COUNT(*) FROM audited
                """.formatted(tenantPredicate);
        Integer released = tenantId == null
                ? jdbc.queryForObject(sql, Integer.class, now, now)
                : jdbc.queryForObject(sql, Integer.class, now, tenantId, now);
        return released == null ? 0 : released;
    }

    int completeEndedBookings(Long tenantId, OffsetDateTime now) {
        return completeEndedBookings(now, "AND booking.tenant_id = ?", tenantId);
    }

    int completeEndedBookings(OffsetDateTime now) {
        return completeEndedBookings(now, "", null);
    }

    private int completeEndedBookings(OffsetDateTime now, String tenantPredicate, Long tenantId) {
        String sql = """
                WITH completed AS (
                    UPDATE wp_bookings booking
                       SET booking_status = 'COMPLETED', version = booking.version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = 0
                      FROM wp_tenant_policies policy
                     WHERE policy.tenant_id = booking.tenant_id
                       %s
                       AND booking.ends_at <= ?
                       AND (booking.booking_status = 'CHECKED_IN'
                            OR (booking.booking_status = 'RESERVED'
                                AND policy.require_check_in = FALSE))
                    RETURNING booking.tenant_id, booking.booking_id, booking.user_id,
                              booking.ends_at
                ), audited AS (
                    INSERT INTO wp_audit_events (
                        tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                        correlation_id, snapshot)
                    SELECT tenant_id, 'workplace.booking.completed', 'BOOKING', booking_id,
                           0, 'workplace:lifecycle-sweep',
                           jsonb_build_object('userId', user_id, 'endedAt', ends_at)
                      FROM completed
                    RETURNING audit_event_id
                )
                SELECT COUNT(*) FROM audited
                """.formatted(tenantPredicate);
        Integer completed = tenantId == null
                ? jdbc.queryForObject(sql, Integer.class, now)
                : jdbc.queryForObject(sql, Integer.class, tenantId, now);
        return completed == null ? 0 : completed;
    }

    long occupiedMinutes(Long tenantId, OffsetDateTime from, OffsetDateTime to) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(GREATEST(0, EXTRACT(EPOCH FROM (
                           LEAST(booking.ends_at, COALESCE(booking.released_at, booking.ends_at), ?)
                           - GREATEST(booking.starts_at, ?))) / 60)), 0)
                  FROM wp_bookings booking
                 WHERE booking.tenant_id = ?
                   AND booking.booking_status IN ('RESERVED', 'CHECKED_IN', 'COMPLETED', 'RELEASED')
                   AND booking.starts_at < ? AND booking.ends_at > ?
                """, Long.class, to, from, tenantId, to, from);
        return value == null ? 0L : value;
    }

    void audit(
            Long tenantId,
            Long actorId,
            String action,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            Map<String, ?> snapshot) {
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                    correlation_id, snapshot)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, tenantId, action, aggregateType, aggregateId, actorId,
                blank(correlationId), json(snapshot));
    }

    private OccupancyRow occupancy(ResultSet result) throws SQLException {
        return new OccupancyRow(
                result.getObject("resource_id", UUID.class),
                result.getObject("booking_id", UUID.class),
                BookingStatus.valueOf(result.getString("status")),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getString("booked_by_display_name"), result.getBoolean("current_user"));
    }

    private BookingRow booking(ResultSet result) throws SQLException {
        return new BookingRow(
                result.getObject("booking_id", UUID.class),
                result.getObject("resource_id", UUID.class), result.getString("resource_name"),
                ResourceType.valueOf(result.getString("resource_type")),
                result.getString("site_name"), result.getString("floor_name"),
                result.getString("purpose"), result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                BookingStatus.valueOf(result.getString("booking_status")),
                result.getBoolean("visible_to_colleagues"),
                result.getObject("checked_in_at", OffsetDateTime.class),
                result.getObject("released_at", OffsetDateTime.class), result.getLong("version"));
    }

    private String json(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Workplace audit snapshot", exception);
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record OccupancyRow(
            UUID resourceId, UUID bookingId, BookingStatus status,
            OffsetDateTime startsAt, OffsetDateTime endsAt,
            String bookedByDisplayName, boolean currentUser) {
    }

    record BookingRow(
            UUID bookingId, UUID resourceId, String resourceName, ResourceType resourceType,
            String siteName, String floorName, String purpose, OffsetDateTime startsAt,
            OffsetDateTime endsAt, BookingStatus status, boolean visibleToColleagues,
            OffsetDateTime checkedInAt, OffsetDateTime releasedAt, long version) {
    }
}
