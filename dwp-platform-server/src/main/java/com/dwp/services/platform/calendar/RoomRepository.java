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
        return resourceOccupancy(tenantId, from, to, null);
    }

    List<ResourceOccupancyRow> resourceOccupancy(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID excludingEventId) {
        return jdbc.query("""
                SELECT booking.resource_id,
                       occurrence.starts_at, occurrence.ends_at,
                       booking.booking_status
                  FROM cal_resource_bookings booking
                  JOIN cal_events event
                    ON event.tenant_id = booking.tenant_id
                   AND event.event_id = booking.event_id
                  JOIN cal_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                  CROSS JOIN LATERAL cal_project_recurrences(
                       booking.starts_at, booking.ends_at, event.time_zone,
                       event.recurrence_pattern, event.recurrence_interval,
                       event.recurrence_until, event.recurrence_count,
                       event.recurrence_days,
                       ?::timestamptz, ?::timestamptz, 4000) occurrence
                 WHERE booking.tenant_id = ?
                   AND (?::uuid IS NULL OR booking.event_id <> ?::uuid)
                   AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                   AND event.status <> 'CANCELLED'
                   AND resource.lifecycle_state <> 'RETIRED'
                   AND occurrence.starts_at < ?
                   AND occurrence.ends_at > ?
                 ORDER BY booking.resource_id, starts_at
                """, (result, ignored) -> new ResourceOccupancyRow(
                        result.getObject("resource_id", UUID.class),
                        result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class),
                        result.getString("booking_status")),
                from, to, tenantId, excludingEventId, excludingEventId, to, from);
    }

    record ResourceOccupancyRow(
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String bookingStatus) {
    }
}
