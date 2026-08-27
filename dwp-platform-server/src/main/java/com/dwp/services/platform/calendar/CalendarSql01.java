package com.dwp.services.platform.calendar;

final class CalendarSql01 {

    private CalendarSql01() {
    }

    static final String POLICY_SELECT_CAL_TENANT_POLICIES = """
        SELECT week_start, working_day_start, working_day_end,
               default_event_minutes, minimum_event_minutes,
               maximum_event_minutes, maximum_advance_days,
               default_buffer_minutes, weekly_focus_target_minutes,
               daily_meeting_limit_minutes, enforce_meeting_agenda,
               allow_external_attendees, version
          FROM cal_tenant_policies WHERE tenant_id = ?
        """;

    static final String UPDATE_POLICY_UPDATE_CAL_TENANT_POLICIES = """
        UPDATE cal_tenant_policies
           SET week_start = ?, working_day_start = ?, working_day_end = ?,
               default_event_minutes = ?, minimum_event_minutes = ?,
               maximum_event_minutes = ?, maximum_advance_days = ?,
               default_buffer_minutes = ?, weekly_focus_target_minutes = ?,
               daily_meeting_limit_minutes = ?, enforce_meeting_agenda = ?,
               allow_external_attendees = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND version = ?
        """;

    static final String CALENDARS_SELECT_CAL_CALENDARS = CalendarAccessSql.CALENDARS;

    static final String ENSURE_PERSONAL_CALENDAR_INSERT_CAL_CALENDARS = """
        INSERT INTO cal_calendars (
            calendar_id, tenant_id, calendar_key, owner_user_id,
            owner_person_public_id, name_ko, name_en, color_hex,
            calendar_type, visibility, created_by, updated_by)
        VALUES (gen_random_uuid(), ?, 'personal-person-' || ?, ?, ?,
                '내 캘린더', 'My calendar', '#2563EB',
                'PERSONAL', 'PRIVATE', ?, ?)
        ON CONFLICT (tenant_id, owner_person_public_id)
            WHERE calendar_type = 'PERSONAL'
              AND owner_person_public_id IS NOT NULL
        DO UPDATE SET
            owner_user_id = EXCLUDED.owner_user_id,
            lifecycle_state = 'ACTIVE',
            updated_at = CURRENT_TIMESTAMP,
            updated_by = EXCLUDED.updated_by
        RETURNING calendar_id
        """;

    static final String CONFLICT_INSERT_CAL_CALENDARS = """
        INSERT INTO cal_calendars (
            calendar_id, tenant_id, calendar_key, owner_user_id,
            name_ko, name_en, color_hex, calendar_type, visibility,
            created_by, updated_by)
        VALUES (gen_random_uuid(), ?, 'personal-' || ?, ?,
                '내 캘린더', 'My calendar', '#2563EB', 'PERSONAL', 'PRIVATE', ?, ?)
        ON CONFLICT (tenant_id, calendar_key) DO UPDATE SET
            lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
        RETURNING calendar_id
        """;

    static final String VISIBLE_EVENTS_SELECT_CAL_EVENTS = CalendarAccessSql.VISIBLE_EVENTS;

    static final String EVENT_IDEMPOTENCY_SELECT_CAL_EVENTS = """
        SELECT event_id, request_fingerprint
          FROM cal_events
         WHERE tenant_id = ? AND organizer_user_id = ? AND idempotency_key = ?
        """;

    static final String ATTENDEES_SELECT_CAL_EVENT_ATTENDEES = """
        SELECT attendee_user_id, attendee_person_public_id, attendee_email, attendee_name,
               attendee_type, response_status
          FROM cal_event_attendees
         WHERE tenant_id = ? AND event_id = ?
         ORDER BY attendee_type, attendee_name, attendee_email
        """;

    static final String INSERT_EVENT_INSERT_CAL_EVENTS = """
        INSERT INTO cal_events (
            event_id, tenant_id, calendar_id, organizer_user_id,
            organizer_person_public_id, organizer_name, title, description, event_type,
            starts_at, ends_at, time_zone, all_day, location,
            conference_url, visibility, recurrence_pattern,
            recurrence_interval, recurrence_until, response_required, importance,
            source_type, idempotency_key, request_fingerprint, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                'NATIVE', ?, ?, ?, ?)
        """;

    static final String UPSERT_ATTENDEES_INSERT_CAL_EVENT_ATTENDEES = """
        INSERT INTO cal_event_attendees (
            tenant_id, event_id, attendee_user_id, attendee_person_public_id, attendee_email,
            attendee_name, attendee_type, response_status)
        VALUES (?, ?, ?, ?, LOWER(BTRIM(?)), ?, ?, 'NEEDS_ACTION')
        ON CONFLICT (event_id, attendee_email) DO UPDATE SET
            attendee_user_id = EXCLUDED.attendee_user_id,
            attendee_person_public_id = EXCLUDED.attendee_person_public_id,
            attendee_name = EXCLUDED.attendee_name,
            attendee_type = EXCLUDED.attendee_type,
            updated_at = CURRENT_TIMESTAMP
        """;

    static final String CONFLICT_DELETE_CAL_EVENT_ATTENDEES = """
        DELETE FROM cal_event_attendees
         WHERE tenant_id = ? AND event_id = ? AND attendee_email = ?
        """;

    static final String BUSY_SLOTS_WITH_CAL_EVENTS = """
        WITH relevant_events AS (
            SELECT subject.person_public_id,
                   event.starts_at,
                   event.ends_at,
                   event.time_zone,
                   event.recurrence_pattern,
                   event.recurrence_interval,
                   event.recurrence_until
          FROM cal_events event
          LEFT JOIN cal_identity_links organizer_identity
            ON organizer_identity.tenant_id = event.tenant_id
           AND organizer_identity.user_id = event.organizer_user_id
          JOIN LATERAL (
              SELECT COALESCE(
                         event.organizer_person_public_id,
                         organizer_identity.person_public_id) AS person_public_id
              WHERE COALESCE(
                        event.organizer_person_public_id,
                        organizer_identity.person_public_id) = ANY (?::uuid[])
              UNION
              SELECT attendee.attendee_person_public_id
                FROM cal_event_attendees attendee
               WHERE attendee.event_id = event.event_id
                 AND attendee.response_status <> 'DECLINED'
                 AND attendee.attendee_person_public_id = ANY (?::uuid[])
          ) subject ON TRUE
         WHERE event.tenant_id = ? AND event.status <> 'CANCELLED'
           AND event.deleted_at IS NULL
           AND event.starts_at < ?
        ), occurrences AS (
            SELECT event.person_public_id,
                   occurrence.local_starts_at AT TIME ZONE event.time_zone AS starts_at,
                   (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                       + (event.ends_at - event.starts_at) AS ends_at
              FROM relevant_events event
              CROSS JOIN LATERAL generate_series(
                  event.starts_at AT TIME ZONE event.time_zone,
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
        )
        SELECT person_public_id, starts_at, ends_at
          FROM occurrences
         WHERE starts_at < ? AND ends_at > ?
         ORDER BY person_public_id, starts_at
        """;

    static final String KNOWN_PEOPLE_SELECT_CAL_IDENTITY_LINKS = """
        SELECT person_public_id
          FROM cal_identity_links
         WHERE tenant_id = ?
           AND person_public_id = ANY (?::uuid[])
        """;

    static final String LINK_IDENTITY_DELETE_CAL_IDENTITY_LINKS = """
        DELETE FROM cal_identity_links
         WHERE tenant_id = ? AND person_public_id = ? AND user_id <> ?
        """;

    static final String LINK_IDENTITY_INSERT_CAL_IDENTITY_LINKS = """
        INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
        VALUES (?, ?, ?)
        ON CONFLICT (tenant_id, user_id) DO UPDATE SET
            person_public_id = EXCLUDED.person_public_id,
            last_seen_at = CURRENT_TIMESTAMP
        WHERE cal_identity_links.person_public_id IS DISTINCT FROM EXCLUDED.person_public_id
           OR cal_identity_links.last_seen_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
        """;

    static final String UPDATE_EVENT_UPDATE_CAL_EVENTS = """
        UPDATE cal_events
           SET title = ?, description = ?, event_type = ?, starts_at = ?, ends_at = ?,
               time_zone = ?, all_day = ?, location = ?, conference_url = ?,
               visibility = ?, recurrence_pattern = ?, recurrence_interval = ?,
               recurrence_until = ?, response_required = ?, importance = ?,
               version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND event_id = ?
           AND (
               organizer_person_public_id = ?
               OR (organizer_person_public_id IS NULL AND organizer_user_id = ?)
               OR EXISTS (
                   SELECT 1
                     FROM cal_calendar_access_grants grant_row
                    WHERE grant_row.tenant_id = cal_events.tenant_id
                      AND grant_row.calendar_id = cal_events.calendar_id
                      AND grant_row.lifecycle_state = 'ACTIVE'
                      AND (grant_row.valid_until IS NULL
                           OR grant_row.valid_until > CURRENT_TIMESTAMP)
                      AND grant_row.access_level IN ('EDIT', 'MANAGE')
                      AND (
                          grant_row.principal_type = 'TENANT'
                          OR (grant_row.principal_type = 'PERSON'
                              AND grant_row.principal_person_public_id = ?)
                          OR (grant_row.principal_type = 'GROUP'
                              AND grant_row.principal_group_ref = ANY (?::uuid[]))))
           )
           AND status <> 'CANCELLED' AND version = ?
        """;

    static final String CANCEL_EVENT_UPDATE_CAL_EVENTS = """
        UPDATE cal_events SET status = 'CANCELLED', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND event_id = ?
           AND (
               organizer_person_public_id = ?
               OR (organizer_person_public_id IS NULL AND organizer_user_id = ?)
               OR EXISTS (
                   SELECT 1
                     FROM cal_calendar_access_grants grant_row
                    WHERE grant_row.tenant_id = cal_events.tenant_id
                      AND grant_row.calendar_id = cal_events.calendar_id
                      AND grant_row.lifecycle_state = 'ACTIVE'
                      AND (grant_row.valid_until IS NULL
                           OR grant_row.valid_until > CURRENT_TIMESTAMP)
                      AND grant_row.access_level = 'MANAGE'
                      AND (
                          grant_row.principal_type = 'TENANT'
                          OR (grant_row.principal_type = 'PERSON'
                              AND grant_row.principal_person_public_id = ?)
                          OR (grant_row.principal_type = 'GROUP'
                              AND grant_row.principal_group_ref = ANY (?::uuid[]))))
           )
           AND status <> 'CANCELLED' AND version = ?
        """;

    static final String RESPOND_UPDATE_CAL_EVENT_ATTENDEES = """
        UPDATE cal_event_attendees
           SET response_status = ?, responded_at = CURRENT_TIMESTAMP,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = ? AND event_id = ?
           AND (
               attendee_person_public_id = ?
               OR (attendee_person_public_id IS NULL AND attendee_user_id = ?)
           )
        """;

    static final String RESOURCES_SELECT_CAL_RESOURCE_BOOKINGS = """
        SELECT resource.resource_id, resource.resource_code,
               CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS name,
               resource.name_ko, resource.name_en,
               resource.resource_type, resource.site_name, resource.floor_name,
               resource.capacity, resource.features::text,
               resource.time_zone,
               resource.approval_required, resource.lifecycle_state, resource.version,
               NOT EXISTS (
                   SELECT 1
                     FROM cal_resource_bookings booking
                     JOIN cal_events event ON event.event_id = booking.event_id
                     CROSS JOIN LATERAL generate_series(
                         booking.starts_at AT TIME ZONE event.time_zone,
                         LEAST(
                             ?::timestamptz AT TIME ZONE event.time_zone,
                             COALESCE(
                                 event.recurrence_until + TIME '23:59:59',
                                 ?::timestamptz AT TIME ZONE event.time_zone)),
                         CASE event.recurrence_pattern
                             WHEN 'DAILY' THEN make_interval(days => event.recurrence_interval)
                             WHEN 'WEEKLY' THEN make_interval(
                                 days => 7 * event.recurrence_interval)
                             WHEN 'MONTHLY' THEN make_interval(
                                 months => event.recurrence_interval)
                             ELSE INTERVAL '100 years'
                         END
                     ) occurrence(local_starts_at)
                    WHERE booking.tenant_id = resource.tenant_id
                      AND booking.resource_id = resource.resource_id
                      AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                      AND event.status <> 'CANCELLED'
                      AND (occurrence.local_starts_at AT TIME ZONE event.time_zone) < ?
                      AND (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                          + (booking.ends_at - booking.starts_at) > ?
               ) AS available
          FROM cal_resources resource
         WHERE resource.tenant_id = ?
           AND (? OR resource.lifecycle_state <> 'RETIRED')
         ORDER BY resource.site_name, resource.floor_name, resource.capacity, resource.resource_code
        """;

    static final String RESOURCE_CONFLICT_SELECT_CAL_RESOURCE_BOOKINGS = """
        SELECT COUNT(*)
          FROM cal_resource_bookings booking
          JOIN cal_events event ON event.event_id = booking.event_id
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
         WHERE booking.tenant_id = ? AND booking.resource_id = ?
           AND booking.booking_status IN ('PENDING', 'CONFIRMED')
           AND event.status <> 'CANCELLED'
           AND (?::uuid IS NULL OR booking.event_id <> ?::uuid)
           AND (occurrence.local_starts_at AT TIME ZONE event.time_zone) < ?
           AND (occurrence.local_starts_at AT TIME ZONE event.time_zone)
               + (booking.ends_at - booking.starts_at) > ?
        """;

    static final String INSERT_BOOKING_INSERT_CAL_RESOURCE_BOOKINGS = """
        INSERT INTO cal_resource_bookings (
            tenant_id, event_id, resource_id, starts_at, ends_at,
            booking_status, requested_by, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (event_id, resource_id) DO UPDATE SET
            starts_at = EXCLUDED.starts_at, ends_at = EXCLUDED.ends_at,
            booking_status = EXCLUDED.booking_status,
            requested_by = EXCLUDED.requested_by,
            decision_note = NULL, decided_at = NULL, decided_by = NULL,
            version = cal_resource_bookings.version + 1,
            updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by
        """;

    static final String PENDING_BOOKINGS_SQL_STATEMENT = """
        WHERE booking.tenant_id = ? AND booking.booking_status = 'PENDING'
        ORDER BY event.starts_at, booking.created_at
        """;

    static final String BOOKING_SQL_STATEMENT = """
        WHERE booking.tenant_id = ? AND booking.booking_id = ?
        """;

    static final String DECIDE_BOOKING_UPDATE_CAL_RESOURCE_BOOKINGS = """
        UPDATE cal_resource_bookings
           SET booking_status = ?, decision_note = ?, decided_at = CURRENT_TIMESTAMP,
               decided_by = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND booking_id = ?
           AND booking_status = 'PENDING' AND version = ?
        """;

    static final String BOOKING_ROWS_SELECT_CAL_RESOURCE_BOOKINGS = """
        SELECT booking.booking_id, booking.event_id, booking.resource_id,
               CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS resource_name,
               event.title, event.starts_at, event.ends_at,
               event.organizer_name, event.organizer_email,
               booking.booking_status, booking.requested_by,
               booking.decision_note, booking.decided_at, booking.decided_by,
               booking.version
          FROM cal_resource_bookings booking
          JOIN cal_events event ON event.event_id = booking.event_id
          JOIN cal_resources resource ON resource.resource_id = booking.resource_id
        """;

    static final String RESCHEDULE_BOOKING_UPDATE_CAL_RESOURCE_BOOKINGS = """
        UPDATE cal_resource_bookings
           SET starts_at = ?, ends_at = ?, booking_status = ?, requested_by = ?,
               decision_note = NULL, decided_at = NULL, decided_by = NULL,
               version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND event_id = ?
           AND booking_status IN ('PENDING', 'CONFIRMED')
        """;

    static final String CANCEL_BOOKINGS_UPDATE_CAL_RESOURCE_BOOKINGS = """
        UPDATE cal_resource_bookings
           SET booking_status = 'CANCELLED', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND event_id = ?
           AND booking_status IN ('PENDING', 'CONFIRMED')
        """;

    static final String SAVE_RESOURCE_INSERT_CAL_RESOURCES = """
        INSERT INTO cal_resources (
            resource_id, tenant_id, resource_code, name_ko, name_en,
            resource_type, site_name, floor_name, capacity, features,
            time_zone, approval_required, lifecycle_state, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
        """;

    static final String CAL_RESOURCES_UPDATE_CAL_RESOURCES = """
        UPDATE cal_resources
           SET resource_code = ?, name_ko = ?, name_en = ?, resource_type = ?,
               site_name = ?, floor_name = ?, capacity = ?, features = CAST(? AS jsonb),
               time_zone = ?, approval_required = ?, lifecycle_state = ?,
               version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND resource_id = ? AND version = ?
        """;

    static final String IS_WORKPLACE_MANAGED_RESOURCE_SELECT_WP_RESOURCES = """
        SELECT EXISTS (
            SELECT 1
              FROM wp_resources
             WHERE tenant_id = ?
               AND calendar_resource_id = ?)
        """;

    static final String IS_WORKPLACE_RESOURCE_BOOKABLE_SELECT_WP_RESOURCES = """
        SELECT NOT EXISTS (
                   SELECT 1
                     FROM wp_resources
                    WHERE tenant_id = ?
                      AND calendar_resource_id = ?)
            OR EXISTS (
                   SELECT 1
                     FROM wp_resources resource
                     JOIN wp_floors floor
                       ON floor.tenant_id = resource.tenant_id
                      AND floor.floor_id = resource.floor_id
                     JOIN wp_sites site
                       ON site.tenant_id = floor.tenant_id
                      AND site.site_id = floor.site_id
                    WHERE resource.tenant_id = ?
                      AND resource.calendar_resource_id = ?
                      AND resource.lifecycle_state = 'AVAILABLE'
                      AND floor.lifecycle_state = 'ACTIVE'
                      AND site.lifecycle_state = 'ACTIVE')
        """;

    static final String ADMIN_STATS_WITH_CAL_EVENTS = """
        WITH event_occurrences AS (
            SELECT event.event_id, event.organizer_user_id,
                   occurrence.local_starts_at AT TIME ZONE event.time_zone AS starts_at,
                   (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                       + (event.ends_at - event.starts_at) AS ends_at
              FROM cal_events event
              CROSS JOIN LATERAL generate_series(
                  event.starts_at AT TIME ZONE event.time_zone,
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
             WHERE event.tenant_id = ? AND event.status <> 'CANCELLED'
               AND event.starts_at < ?
        ), booking_occurrences AS (
            SELECT booking.booking_id, booking.booking_status,
                   occurrence.starts_at,
                   occurrence.starts_at
                       + (booking.ends_at - booking.starts_at) AS ends_at
              FROM cal_resource_bookings booking
              JOIN event_occurrences occurrence
                ON occurrence.event_id = booking.event_id
             WHERE booking.tenant_id = ?
               AND booking.booking_status IN ('PENDING', 'CONFIRMED')
        )
        SELECT
            (SELECT COUNT(*) FROM cal_resources
              WHERE tenant_id = ? AND lifecycle_state = 'AVAILABLE') AS active_resources,
            (SELECT COUNT(*) FROM cal_resources
              WHERE tenant_id = ? AND lifecycle_state = 'MAINTENANCE') AS maintenance_resources,
            (SELECT COUNT(*) FROM booking_occurrences
              WHERE starts_at < ? AND ends_at > ?) AS bookings_this_week,
            (SELECT COUNT(*) FROM cal_resource_bookings
              WHERE tenant_id = ? AND booking_status = 'PENDING') AS pending_bookings,
            (SELECT COUNT(*) FROM event_occurrences
              WHERE starts_at < ? AND ends_at > ?) AS events_this_week,
            (SELECT COUNT(DISTINCT first_event.organizer_user_id)
               FROM event_occurrences first_event
               JOIN event_occurrences second_event
                 ON second_event.event_id > first_event.event_id
                AND second_event.organizer_user_id = first_event.organizer_user_id
                AND second_event.starts_at < first_event.ends_at
                AND second_event.ends_at > first_event.starts_at
              WHERE first_event.starts_at < ?
                AND first_event.ends_at > ?) AS conflicted_users
        """;

    static final String AUDIT_INSERT_CAL_AUDIT_EVENTS = """
        INSERT INTO cal_audit_events (
            tenant_id, event_id, action, actor_user_id, correlation_id,
            before_snapshot, after_snapshot)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
        """;
}
