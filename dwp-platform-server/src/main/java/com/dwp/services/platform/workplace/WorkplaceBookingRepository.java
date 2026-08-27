package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

@Repository
class WorkplaceBookingRepository {

    static final String OCCUPANCY_SQL = """
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
                   'RESERVED' AS status,
                   occurrence.local_starts_at AT TIME ZONE event.time_zone AS starts_at,
                   (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                       + (booking.ends_at - booking.starts_at) AS ends_at,
                   CASE WHEN event.organizer_user_id = ?
                        THEN event.organizer_name END AS booked_by_display_name,
                   event.organizer_user_id = ? AS current_user
              FROM wp_resources resource
              JOIN cal_resource_bookings booking
                ON booking.tenant_id = resource.tenant_id
               AND booking.resource_id = resource.calendar_resource_id
              JOIN cal_events event
                ON event.tenant_id = booking.tenant_id
               AND event.event_id = booking.event_id
             CROSS JOIN LATERAL (
                   SELECT booking.starts_at AT TIME ZONE event.time_zone AS anchor_local,
                          LEAST(
                              ?::timestamptz AT TIME ZONE event.time_zone,
                              COALESCE(
                                  event.recurrence_until + TIME '23:59:59.999999',
                                  ?::timestamptz AT TIME ZONE event.time_zone)) AS limit_local
              ) recurrence_bounds
             CROSS JOIN LATERAL generate_series(
                  0,
                  CASE event.recurrence_pattern
                      WHEN 'DAILY' THEN GREATEST(0,
                          ((recurrence_bounds.limit_local::date
                              - recurrence_bounds.anchor_local::date)
                              / event.recurrence_interval) + 1)
                      WHEN 'WEEKLY' THEN GREATEST(0,
                          ((recurrence_bounds.limit_local::date
                              - recurrence_bounds.anchor_local::date)
                              / (7 * event.recurrence_interval)) + 1)
                      WHEN 'MONTHLY' THEN GREATEST(0,
                          (((EXTRACT(YEAR FROM recurrence_bounds.limit_local)::integer
                              - EXTRACT(YEAR FROM recurrence_bounds.anchor_local)::integer) * 12
                            + EXTRACT(MONTH FROM recurrence_bounds.limit_local)::integer
                              - EXTRACT(MONTH FROM recurrence_bounds.anchor_local)::integer)
                              / event.recurrence_interval) + 1)
                      ELSE 0
                  END
              ) occurrence_sequence(occurrence_index)
             CROSS JOIN LATERAL (
                   SELECT CASE event.recurrence_pattern
                              WHEN 'DAILY' THEN recurrence_bounds.anchor_local
                                  + make_interval(days =>
                                      occurrence_index * event.recurrence_interval)
                              WHEN 'WEEKLY' THEN recurrence_bounds.anchor_local
                                  + make_interval(days =>
                                      occurrence_index * 7 * event.recurrence_interval)
                              WHEN 'MONTHLY' THEN recurrence_bounds.anchor_local
                                  + make_interval(months =>
                                      occurrence_index * event.recurrence_interval)
                              ELSE recurrence_bounds.anchor_local
                          END AS local_starts_at
              ) occurrence
             WHERE resource.tenant_id = ? AND resource.floor_id = ?
               AND booking.booking_status IN ('PENDING', 'CONFIRMED')
               AND event.status <> 'CANCELLED'
               AND occurrence.local_starts_at <= recurrence_bounds.limit_local
               AND (occurrence.local_starts_at AT TIME ZONE event.time_zone) < ?
               AND (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                   + (booking.ends_at - booking.starts_at) > ?
             ORDER BY starts_at, resource_id
            """;

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
        return jdbc.query(OCCUPANCY_SQL, (result, ignored) -> occupancy(result),
                userId, userId, tenantId, floorId, to, from,
                userId, userId, to, to, tenantId, floorId, to, from);
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
            WorkplaceCatalogRepository.PolicyRow policy,
            UUID releaseWindowId,
            boolean korean) {
        UUID bookingId = UUID.randomUUID();
        String policyJson = json(policySnapshot(policy));
        jdbc.update("""
                INSERT INTO wp_bookings (
                    booking_id, tenant_id, resource_id, user_id, person_public_id,
                    booked_for_display_name, purpose, starts_at, ends_at,
                    visible_to_colleagues, release_window_id,
                    policy_snapshot, policy_snapshot_hash,
                    require_check_in_snapshot, check_in_lead_minutes_snapshot,
                    auto_release_minutes_snapshot, booking_retention_days_snapshot,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                        encode(digest(CAST(? AS jsonb)::TEXT, 'sha256'), 'hex'),
                        ?, ?, ?, ?, ?, ?)
                """, bookingId, tenantId, value.resourceId(), userId, personPublicId,
                displayName, blank(value.purpose()), value.startsAt(), value.endsAt(),
                value.visibleToColleagues(), releaseWindowId, policyJson, policyJson,
                policy.requireCheckIn(), policy.checkInLeadMinutes(),
                policy.autoReleaseMinutes(), policy.bookingRetentionDays(), userId, userId);
        return booking(tenantId, userId, bookingId, korean).orElseThrow();
    }

    void lockUserBookingScope(Long tenantId, Long userId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, "workplace:" + tenantId + ":" + userId),
                result -> null);
    }

    void lockResourceBookingScope(Long tenantId, UUID resourceId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(
                        1, "workplace-resource:" + tenantId + ":" + resourceId),
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

    List<LifecycleBookingRow> releaseNoShows(Long tenantId, OffsetDateTime now) {
        return releaseNoShows(now, "AND booking.tenant_id = ?", tenantId);
    }

    List<LifecycleBookingRow> releaseNoShows(OffsetDateTime now) {
        return releaseNoShows(now, "", null);
    }

    private List<LifecycleBookingRow> releaseNoShows(
            OffsetDateTime now, String tenantPredicate, Long tenantId) {
        String sql = """
                WITH released AS (
                    UPDATE wp_bookings booking
                       SET booking_status = 'NO_SHOW', released_at = ?,
                           version = booking.version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = 0
                     WHERE 1 = 1
                       %s
                       AND booking.require_check_in_snapshot = TRUE
                       AND booking.booking_status = 'RESERVED'
                       AND booking.starts_at
                           + make_interval(mins => booking.auto_release_minutes_snapshot) < ?
                    RETURNING booking.tenant_id, booking.booking_id, booking.resource_id,
                              booking.user_id, booking.starts_at, booking.ends_at,
                              booking.booking_status, booking.version, booking.released_at
                ), audited AS (
                    INSERT INTO wp_audit_events (
                        tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                        correlation_id, snapshot)
                    SELECT tenant_id, 'workplace.booking.no_show', 'BOOKING', booking_id,
                           0, 'workplace:no-show-sweep',
                           jsonb_build_object('userId', user_id, 'releasedAt', released_at)
                      FROM released
                    RETURNING aggregate_id
                )
                SELECT released.tenant_id, released.booking_id, released.resource_id,
                       floor.site_id, resource.floor_id, released.booking_status,
                       released.starts_at, released.ends_at, released.version
                  FROM released
                  JOIN audited ON audited.aggregate_id = released.booking_id
                  JOIN wp_resources resource
                    ON resource.tenant_id = released.tenant_id
                   AND resource.resource_id = released.resource_id
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                """.formatted(tenantPredicate);
        return tenantId == null
                ? jdbc.query(sql, this::lifecycleBooking, now, now)
                : jdbc.query(sql, this::lifecycleBooking, now, tenantId, now);
    }

    List<LifecycleBookingRow> completeEndedBookings(Long tenantId, OffsetDateTime now) {
        return completeEndedBookings(now, "AND booking.tenant_id = ?", tenantId);
    }

    List<LifecycleBookingRow> completeEndedBookings(OffsetDateTime now) {
        return completeEndedBookings(now, "", null);
    }

    private List<LifecycleBookingRow> completeEndedBookings(
            OffsetDateTime now, String tenantPredicate, Long tenantId) {
        String sql = """
                WITH completed AS (
                    UPDATE wp_bookings booking
                       SET booking_status = 'COMPLETED', version = booking.version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = 0
                     WHERE 1 = 1
                       %s
                       AND booking.ends_at <= ?
                       AND (booking.booking_status = 'CHECKED_IN'
                            OR (booking.booking_status = 'RESERVED'
                                AND booking.require_check_in_snapshot = FALSE))
                    RETURNING booking.tenant_id, booking.booking_id, booking.resource_id,
                              booking.user_id, booking.starts_at, booking.ends_at,
                              booking.booking_status, booking.version
                ), audited AS (
                    INSERT INTO wp_audit_events (
                        tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                        correlation_id, snapshot)
                    SELECT tenant_id, 'workplace.booking.completed', 'BOOKING', booking_id,
                           0, 'workplace:lifecycle-sweep',
                           jsonb_build_object('userId', user_id, 'endedAt', ends_at)
                      FROM completed
                    RETURNING aggregate_id
                )
                SELECT completed.tenant_id, completed.booking_id, completed.resource_id,
                       floor.site_id, resource.floor_id, completed.booking_status,
                       completed.starts_at, completed.ends_at, completed.version
                  FROM completed
                  JOIN audited ON audited.aggregate_id = completed.booking_id
                  JOIN wp_resources resource
                    ON resource.tenant_id = completed.tenant_id
                   AND resource.resource_id = completed.resource_id
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                """.formatted(tenantPredicate);
        return tenantId == null
                ? jdbc.query(sql, this::lifecycleBooking, now)
                : jdbc.query(sql, this::lifecycleBooking, tenantId, now);
    }

    private LifecycleBookingRow lifecycleBooking(ResultSet result, int ignored)
            throws SQLException {
        return new LifecycleBookingRow(
                result.getLong("tenant_id"),
                result.getObject("booking_id", UUID.class),
                result.getObject("resource_id", UUID.class),
                result.getObject("site_id", UUID.class),
                result.getObject("floor_id", UUID.class),
                BookingStatus.valueOf(result.getString("booking_status")),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getLong("version"));
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
                result.getObject("released_at", OffsetDateTime.class),
                result.getBoolean("require_check_in_snapshot"),
                result.getInt("check_in_lead_minutes_snapshot"),
                result.getInt("auto_release_minutes_snapshot"),
                result.getInt("booking_retention_days_snapshot"),
                result.getString("policy_snapshot_hash"),
                result.getObject("release_window_id", UUID.class), result.getLong("version"));
    }

    private String json(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Workplace audit snapshot", exception);
        }
    }

    private Map<String, Object> policySnapshot(WorkplaceCatalogRepository.PolicyRow policy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bookingWindowDays", policy.bookingWindowDays());
        snapshot.put("maximumActiveBookings", policy.maximumActiveBookings());
        snapshot.put("minimumBookingMinutes", policy.minimumBookingMinutes());
        snapshot.put("maximumBookingMinutes", policy.maximumBookingMinutes());
        snapshot.put("maximumConsecutiveDays", policy.maximumConsecutiveDays());
        snapshot.put("workingDayStart", policy.workingDayStart().toString());
        snapshot.put("workingDayEnd", policy.workingDayEnd().toString());
        snapshot.put("allowRecurring", policy.allowRecurring());
        snapshot.put("requireCheckIn", policy.requireCheckIn());
        snapshot.put("checkInLeadMinutes", policy.checkInLeadMinutes());
        snapshot.put("autoReleaseMinutes", policy.autoReleaseMinutes());
        snapshot.put("allowAssignedDeskLending", policy.allowAssignedDeskLending());
        snapshot.put("showColleagueNames", policy.showColleagueNames());
        snapshot.put("bookingRetentionDays", policy.bookingRetentionDays());
        return snapshot;
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record OccupancyRow(
            UUID resourceId, UUID bookingId, BookingStatus status,
            OffsetDateTime startsAt, OffsetDateTime endsAt,
            String bookedByDisplayName, boolean currentUser) {
    }

    record LifecycleBookingRow(
            Long tenantId,
            UUID bookingId,
            UUID resourceId,
            UUID siteId,
            UUID floorId,
            BookingStatus status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long version) {
    }

    record BookingRow(
            UUID bookingId, UUID resourceId, String resourceName, ResourceType resourceType,
            String siteName, String floorName, String purpose, OffsetDateTime startsAt,
            OffsetDateTime endsAt, BookingStatus status, boolean visibleToColleagues,
            OffsetDateTime checkedInAt, OffsetDateTime releasedAt,
            boolean requireCheckIn, int checkInLeadMinutes, int autoReleaseMinutes,
            int bookingRetentionDays, String policySnapshotHash,
            UUID releaseWindowId, long version) {
        BookingRow(
                UUID bookingId,
                UUID resourceId,
                String resourceName,
                ResourceType resourceType,
                String siteName,
                String floorName,
                String purpose,
                OffsetDateTime startsAt,
                OffsetDateTime endsAt,
                BookingStatus status,
                boolean visibleToColleagues,
                OffsetDateTime checkedInAt,
                OffsetDateTime releasedAt,
                long version) {
            this(bookingId, resourceId, resourceName, resourceType, siteName, floorName,
                    purpose, startsAt, endsAt, status, visibleToColleagues,
                    checkedInAt, releasedAt, true, 30, 30, 365,
                    "0".repeat(64), null, version);
        }
    }
}
