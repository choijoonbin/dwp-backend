package com.dwp.services.platform.calendar;

final class CalendarRestoreSql {

    private CalendarRestoreSql() {
    }

    static final String TRASH_RESOURCE_BOOKINGS = """
            UPDATE cal_resource_bookings
               SET booking_status = 'CANCELLED',
                   cancelled_for_event_trash = TRUE,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = ?
             WHERE tenant_id = ? AND event_id = ?
               AND booking_status IN ('PENDING', 'CONFIRMED')
            """;

    static final String RESTORABLE_RESOURCE_BOOKINGS = """
            SELECT booking.resource_id, booking.booking_status,
                   booking.version AS booking_version,
                   event.starts_at, event.ends_at, event.time_zone,
                   event.event_type, event.all_day,
                   event.recurrence_pattern, event.recurrence_interval,
                   event.recurrence_until,
                   resource.resource_type, resource.time_zone AS resource_time_zone,
                   resource.approval_required, resource.lifecycle_state,
                   resource.capacity,
                   (SELECT COUNT(DISTINCT lower(trim(attendee.attendee_email)))
                      FROM cal_event_attendees attendee
                     WHERE attendee.tenant_id = event.tenant_id
                       AND attendee.event_id = event.event_id) AS attendee_count
              FROM cal_resource_bookings booking
              JOIN cal_events event
                ON event.tenant_id = booking.tenant_id
               AND event.event_id = booking.event_id
              JOIN cal_resources resource
                ON resource.tenant_id = booking.tenant_id
               AND resource.resource_id = booking.resource_id
             WHERE booking.tenant_id = ?
               AND booking.event_id = ?
               AND booking.cancelled_for_event_trash
            """;

    static final String RESTORABLE_RESOURCE_BOOKING_FOR_UPDATE =
            RESTORABLE_RESOURCE_BOOKINGS
                    + " AND booking.resource_id = ? FOR UPDATE OF booking";

    static final String REBOOK_RESOURCE = """
            UPDATE cal_resource_bookings
               SET booking_status = ?,
                   cancelled_for_event_trash = FALSE,
                   requested_by = ?,
                   decision_note = NULL,
                   decided_at = NULL,
                   decided_by = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = ?
             WHERE tenant_id = ?
               AND event_id = ?
               AND resource_id = ?
               AND booking_status = 'CANCELLED'
               AND cancelled_for_event_trash
               AND version = ?
            RETURNING version
            """;

    static final String RESOURCE_REBOOK_COMMAND = """
            SELECT event_id, resource_id, request_fingerprint,
                   outcome, reason_code, event_version, booking_version
              FROM cal_resource_restore_commands
             WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
            """;

    static final String INSERT_RESOURCE_REBOOK_COMMAND = """
            INSERT INTO cal_resource_restore_commands (
                tenant_id, actor_user_id, idempotency_key,
                event_id, resource_id, request_fingerprint,
                outcome, reason_code, event_version, booking_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
}
