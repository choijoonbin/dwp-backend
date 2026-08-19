package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class RoomRepository {

    private final JdbcTemplate jdbc;

    RoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ResourceOccupancyRow> resourceOccupancy(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to) {
        return jdbc.query("""
                SELECT booking.resource_id,
                       occurrence.local_starts_at AT TIME ZONE event.time_zone AS starts_at,
                       (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                           + (booking.ends_at - booking.starts_at) AS ends_at,
                       booking.booking_status
                  FROM cal_resource_bookings booking
                  JOIN cal_events event
                    ON event.tenant_id = booking.tenant_id
                   AND event.event_id = booking.event_id
                  JOIN cal_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                  CROSS JOIN LATERAL generate_series(
                       booking.starts_at AT TIME ZONE event.time_zone,
                       LEAST(
                           ?::timestamptz AT TIME ZONE event.time_zone,
                           COALESCE(
                               event.recurrence_until + TIME '23:59:59',
                               ?::timestamptz AT TIME ZONE event.time_zone)),
                       CASE event.recurrence_pattern
                           WHEN 'DAILY' THEN make_interval(days => event.recurrence_interval)
                           WHEN 'WEEKLY' THEN make_interval(days => 7 * event.recurrence_interval)
                           WHEN 'MONTHLY' THEN make_interval(months => event.recurrence_interval)
                           ELSE INTERVAL '100 years'
                       END
                  ) occurrence(local_starts_at)
                 WHERE booking.tenant_id = ?
                   AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                   AND event.status <> 'CANCELLED'
                   AND resource.lifecycle_state <> 'RETIRED'
                   AND (occurrence.local_starts_at AT TIME ZONE event.time_zone) < ?
                   AND (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                       + (booking.ends_at - booking.starts_at) > ?
                 ORDER BY booking.resource_id, starts_at
                """, (result, ignored) -> new ResourceOccupancyRow(
                        result.getObject("resource_id", UUID.class),
                        result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class),
                        result.getString("booking_status")),
                to, to, tenantId, to, from);
    }

    record ResourceOccupancyRow(
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String bookingStatus) {
    }
}
