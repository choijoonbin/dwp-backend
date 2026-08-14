-- Keep persisted HRIS absence fixtures away from their organizers' seeded
-- resource reservations. Recurring meetings may still surface as coaching
-- conflicts, matching the behavior of synchronized enterprise calendars.
UPDATE cal_events event
   SET starts_at = (
           date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + INTERVAL '11 days'
       ) AT TIME ZONE 'Asia/Seoul',
       ends_at = (
           date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + INTERVAL '12 days'
       ) AT TIME ZONE 'Asia/Seoul',
       version = event.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE event.tenant_id IN (
           SELECT tenant_id
             FROM sys_service_tenants
            WHERE tenant_key = 'default'
       )
   AND event.event_type = 'OUT_OF_OFFICE'
   AND event.source_ref LIKE 'seed:skax:absence-v1:%';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM cal_events absence
          JOIN cal_events reserved_event
            ON reserved_event.tenant_id = absence.tenant_id
           AND reserved_event.organizer_user_id = absence.organizer_user_id
           AND reserved_event.status <> 'CANCELLED'
          JOIN cal_resource_bookings booking
            ON booking.event_id = reserved_event.event_id
           AND booking.booking_status IN ('PENDING', 'CONFIRMED')
         WHERE absence.event_type = 'OUT_OF_OFFICE'
           AND absence.source_ref LIKE 'seed:skax:absence-v1:%'
           AND absence.status <> 'CANCELLED'
           AND tstzrange(booking.starts_at, booking.ends_at, '[)')
               && tstzrange(absence.starts_at, absence.ends_at, '[)')
    ) THEN
        RAISE EXCEPTION 'SKAX absence seed overlaps an owned resource booking';
    END IF;
END
$$;
