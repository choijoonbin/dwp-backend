-- Keep reminder fixtures outside meeting and room-reservation slots so the
-- schedule remains useful for interaction and visual regression checks.
UPDATE cal_events event
   SET starts_at = (
           date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + INTERVAL '8 days 08:00'
       ) AT TIME ZONE 'Asia/Seoul',
       ends_at = (
           date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + INTERVAL '8 days 08:20'
       ) AT TIME ZONE 'Asia/Seoul',
       version = event.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE event.tenant_id IN (
           SELECT tenant_id
             FROM sys_service_tenants
            WHERE tenant_key = 'default'
       )
   AND event.event_type = 'REMINDER'
   AND event.source_ref LIKE 'seed:skax:reminder-v1:%';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM cal_events reminder
          JOIN cal_events other
            ON other.tenant_id = reminder.tenant_id
           AND other.organizer_user_id = reminder.organizer_user_id
           AND other.event_id <> reminder.event_id
           AND other.status <> 'CANCELLED'
           AND tstzrange(other.starts_at, other.ends_at, '[)')
               && tstzrange(reminder.starts_at, reminder.ends_at, '[)')
         WHERE reminder.event_type = 'REMINDER'
           AND reminder.source_ref LIKE 'seed:skax:reminder-v1:%'
           AND reminder.status <> 'CANCELLED'
    ) THEN
        RAISE EXCEPTION 'SKAX reminder seed overlaps another owned base event';
    END IF;
END
$$;
