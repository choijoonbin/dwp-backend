CREATE EXTENSION IF NOT EXISTS btree_gist;

-- The original SKAX fixture put every employee in the same two rooms at the
-- same time. Distribute those deterministic seed series before enforcing the
-- same invariant that runtime bookings use.
WITH seed_assignment AS (
    SELECT event.event_id,
           CASE
               WHEN event.event_id = md5(
                   'calendar:event:' || event.tenant_id || ':'
                       || event.organizer_user_id || ':team-sync')::uuid
                   THEN (ARRAY[
                       'TOWER-A-1201', 'TOWER-A-1202', 'TOWER-A-1501',
                       'TOWER-B-0803', 'SEOUL-ROOM-01', 'STUDIO-01'
                   ])[1 + MOD((event.organizer_user_id - 5)::integer, 6)]
               ELSE (ARRAY[
                   'TOWER-A-1201', 'TOWER-A-1202', 'TOWER-B-0803',
                   'SEOUL-ROOM-01', 'STUDIO-01'
               ])[1 + MOD((event.organizer_user_id - 5)::integer, 5)]
           END AS resource_code,
           CASE
               WHEN event.event_id = md5(
                   'calendar:event:' || event.tenant_id || ':'
                       || event.organizer_user_id || ':team-sync')::uuid
                   THEN 9 + 2 * ((event.organizer_user_id - 5) / 6)::integer
               ELSE (ARRAY[9, 10, 13, 15, 16])[
                   1 + ((event.organizer_user_id - 5) / 5)::integer]
           END AS start_hour
      FROM cal_events event
      JOIN sys_service_tenants tenant ON tenant.tenant_id = event.tenant_id
     WHERE tenant.tenant_key = 'default'
       AND event.organizer_user_id BETWEEN 5 AND 25
       AND event.event_id IN (
           md5('calendar:event:' || event.tenant_id || ':'
               || event.organizer_user_id || ':team-sync')::uuid,
           md5('calendar:event:' || event.tenant_id || ':'
               || event.organizer_user_id || ':one-on-one')::uuid)
), resolved AS (
    SELECT event.event_id, resource.resource_id, resource.name_ko,
           date_trunc('day', event.starts_at, event.time_zone)
               + make_interval(hours => assignment.start_hour) AS new_start,
           event.ends_at - event.starts_at AS duration,
           event.time_zone
      FROM seed_assignment assignment
      JOIN cal_events event ON event.event_id = assignment.event_id
      JOIN cal_resources resource
        ON resource.tenant_id = event.tenant_id
       AND resource.resource_code = assignment.resource_code
)
UPDATE cal_events event
   SET starts_at = resolved.new_start,
       ends_at = resolved.new_start + resolved.duration,
       location = resolved.name_ko,
       recurrence_until = (resolved.new_start AT TIME ZONE resolved.time_zone)::date + 90,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM resolved
 WHERE event.event_id = resolved.event_id;

WITH seed_assignment AS (
    SELECT event.event_id,
           CASE
               WHEN event.event_id = md5(
                   'calendar:event:' || event.tenant_id || ':'
                       || event.organizer_user_id || ':team-sync')::uuid
                   THEN (ARRAY[
                       'TOWER-A-1201', 'TOWER-A-1202', 'TOWER-A-1501',
                       'TOWER-B-0803', 'SEOUL-ROOM-01', 'STUDIO-01'
                   ])[1 + MOD((event.organizer_user_id - 5)::integer, 6)]
               ELSE (ARRAY[
                   'TOWER-A-1201', 'TOWER-A-1202', 'TOWER-B-0803',
                   'SEOUL-ROOM-01', 'STUDIO-01'
               ])[1 + MOD((event.organizer_user_id - 5)::integer, 5)]
           END AS resource_code
      FROM cal_events event
      JOIN sys_service_tenants tenant ON tenant.tenant_id = event.tenant_id
     WHERE tenant.tenant_key = 'default'
       AND event.organizer_user_id BETWEEN 5 AND 25
       AND event.event_id IN (
           md5('calendar:event:' || event.tenant_id || ':'
               || event.organizer_user_id || ':team-sync')::uuid,
           md5('calendar:event:' || event.tenant_id || ':'
               || event.organizer_user_id || ':one-on-one')::uuid)
)
UPDATE cal_resource_bookings booking
   SET resource_id = resource.resource_id,
       starts_at = event.starts_at,
       ends_at = event.ends_at,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM seed_assignment assignment
  JOIN cal_events event ON event.event_id = assignment.event_id
  JOIN cal_resources resource
    ON resource.tenant_id = event.tenant_id
   AND resource.resource_code = assignment.resource_code
 WHERE booking.event_id = assignment.event_id
   AND booking.booking_status IN ('PENDING', 'CONFIRMED');

ALTER TABLE cal_resource_bookings
    ADD CONSTRAINT ex_cal_resource_booking_active_period
    EXCLUDE USING gist (
        tenant_id WITH =,
        resource_id WITH =,
        (tstzrange(starts_at, ends_at, '[)')) WITH &&)
    WHERE (booking_status IN ('PENDING', 'CONFIRMED'));

COMMENT ON CONSTRAINT ex_cal_resource_booking_active_period
    ON cal_resource_bookings IS
    'Prevents concurrent active bookings for the same resource and base period.';
