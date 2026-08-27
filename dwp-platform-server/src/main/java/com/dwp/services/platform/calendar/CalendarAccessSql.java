package com.dwp.services.platform.calendar;

final class CalendarAccessSql {

    private CalendarAccessSql() {
    }

    static final String CALENDARS = """
        WITH viewer AS (
            SELECT ?::bigint AS user_id, ?::uuid AS person_id, ?::uuid[] AS group_refs
        )
        SELECT calendar.calendar_id, calendar.calendar_key,
               CASE WHEN ? THEN calendar.name_ko ELSE calendar.name_en END AS name,
               calendar.color_hex, calendar.calendar_type, calendar.visibility,
               calendar.owner_user_id, calendar.owner_person_public_id,
               calendar.owner_display_name, calendar.subscription_policy,
               calendar.version AS calendar_version,
               CASE
                   WHEN calendar.owner_person_public_id = viewer.person_id
                       OR (calendar.owner_person_public_id IS NULL
                           AND calendar.owner_user_id = viewer.user_id)
                       THEN 'OWNER'
                   ELSE access.access_level
               END AS access_level,
               CASE
                   WHEN calendar.owner_person_public_id = viewer.person_id
                       OR (calendar.owner_person_public_id IS NULL
                           AND calendar.owner_user_id = viewer.user_id)
                       THEN 'OWNED'
                   WHEN calendar.calendar_type = 'SYSTEM'
                       AND access.principal_type = 'TENANT' THEN 'COMPANY'
                   WHEN calendar.calendar_type = 'TEAM' THEN 'TEAM'
                   ELSE 'SHARED'
               END AS source_kind,
               CASE WHEN calendar.subscription_policy = 'REQUIRED' THEN TRUE
                    ELSE COALESCE(subscription.selected, TRUE) END AS selected,
               COALESCE(subscription.favorite, FALSE) AS favorite,
               COALESCE(subscription.display_order, 0) AS display_order,
               subscription.color_override,
               COALESCE(subscription.version, 0) AS subscription_version,
               COALESCE(access.can_view_private, FALSE) AS can_view_private
          FROM cal_calendars calendar
         CROSS JOIN viewer
          LEFT JOIN LATERAL (
              SELECT grant_row.access_level, grant_row.principal_type,
                     grant_row.can_view_private
                FROM cal_calendar_access_grants grant_row
               WHERE grant_row.tenant_id = calendar.tenant_id
                 AND grant_row.calendar_id = calendar.calendar_id
                 AND grant_row.lifecycle_state = 'ACTIVE'
                 AND (grant_row.valid_until IS NULL
                      OR grant_row.valid_until > CURRENT_TIMESTAMP)
                 AND (
                     grant_row.principal_type = 'TENANT'
                     OR (grant_row.principal_type = 'PERSON'
                         AND grant_row.principal_person_public_id = viewer.person_id)
                     OR (grant_row.principal_type = 'GROUP'
                         AND grant_row.principal_group_ref = ANY (viewer.group_refs))
                 )
               ORDER BY CASE grant_row.access_level
                            WHEN 'MANAGE' THEN 4
                            WHEN 'EDIT' THEN 3
                            WHEN 'VIEW_DETAILS' THEN 2
                            ELSE 1
                        END DESC,
                        grant_row.updated_at DESC
               LIMIT 1
          ) access ON TRUE
          LEFT JOIN cal_calendar_subscriptions subscription
            ON subscription.tenant_id = calendar.tenant_id
           AND subscription.calendar_id = calendar.calendar_id
           AND subscription.person_public_id = viewer.person_id
         WHERE calendar.tenant_id = ?
           AND calendar.lifecycle_state = 'ACTIVE'
           AND (
               calendar.owner_person_public_id = viewer.person_id
               OR (calendar.owner_person_public_id IS NULL
                   AND calendar.owner_user_id = viewer.user_id)
               OR access.access_level IS NOT NULL
           )
         ORDER BY COALESCE(subscription.favorite, FALSE) DESC,
                  CASE
                      WHEN calendar.calendar_type = 'SYSTEM' THEN 0
                      WHEN calendar.owner_person_public_id = viewer.person_id
                          OR (calendar.owner_person_public_id IS NULL
                              AND calendar.owner_user_id = viewer.user_id) THEN 1
                      WHEN calendar.calendar_type = 'TEAM' THEN 3
                      ELSE 2
                  END,
                  COALESCE(subscription.display_order, 0), calendar.calendar_key
        """;

    static final String VISIBLE_EVENTS = """
        WITH viewer AS (
            SELECT ?::bigint AS user_id, ?::uuid AS person_id, ?::uuid[] AS group_refs
        )
        SELECT event.event_id, event.calendar_id,
               CASE WHEN ? THEN calendar.name_ko ELSE calendar.name_en END AS calendar_name,
               COALESCE(subscription.color_override, calendar.color_hex) AS color_hex,
               calendar.calendar_type, calendar.subscription_policy,
               calendar.owner_user_id AS calendar_owner_user_id,
               calendar.owner_person_public_id AS calendar_owner_person_public_id,
               event.organizer_user_id, event.organizer_person_public_id,
               event.organizer_name, event.organizer_email, event.title, event.description,
               event.event_type, event.starts_at, event.ends_at, event.time_zone,
               event.all_day, event.location, event.conference_url, event.status,
               event.visibility, event.importance,
               event.recurrence_pattern, event.recurrence_interval,
               event.recurrence_until, event.response_required,
               mine.response_status AS my_response, event.version,
               COALESCE(preference.starred, FALSE) AS starred,
               COALESCE(preference.version, 0) AS preference_version,
               CASE
                   WHEN (calendar.calendar_type <> 'SYSTEM' AND (
                           event.organizer_person_public_id = viewer.person_id
                           OR (event.organizer_person_public_id IS NULL
                               AND event.organizer_user_id = viewer.user_id)))
                       OR calendar.owner_person_public_id = viewer.person_id
                       OR (calendar.owner_person_public_id IS NULL
                           AND calendar.owner_user_id = viewer.user_id)
                       THEN 'OWNER'
                   WHEN access.access_level IN ('MANAGE', 'EDIT') THEN access.access_level
                   WHEN mine.event_id IS NOT NULL THEN 'EVENT_ATTENDEE'
                   ELSE access.access_level
               END AS access_level,
               CASE
                   WHEN (calendar.calendar_type <> 'SYSTEM' AND (
                           event.organizer_person_public_id = viewer.person_id
                           OR (event.organizer_person_public_id IS NULL
                               AND event.organizer_user_id = viewer.user_id)))
                       OR calendar.owner_person_public_id = viewer.person_id
                       OR (calendar.owner_person_public_id IS NULL
                           AND calendar.owner_user_id = viewer.user_id)
                       OR mine.event_id IS NOT NULL
                       THEN 'FULL'
                   WHEN access.access_level IN ('MANAGE', 'EDIT', 'VIEW_DETAILS')
                       AND (event.visibility NOT IN ('PRIVATE', 'CONFIDENTIAL')
                            OR access.can_view_private)
                       THEN 'FULL'
                   ELSE 'FREE_BUSY'
               END AS detail_level,
               COALESCE(access.can_view_private, FALSE) AS can_view_private,
               resource.resource_id, resource.resource_code,
               CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS resource_name,
               resource.name_ko AS resource_name_ko,
               resource.name_en AS resource_name_en,
               resource.resource_type, resource.site_name, resource.floor_name,
               resource.capacity, resource.features::text, resource.time_zone,
               resource.approval_required,
               resource.lifecycle_state AS resource_state
          FROM cal_events event
          JOIN cal_calendars calendar
            ON calendar.tenant_id = event.tenant_id
           AND calendar.calendar_id = event.calendar_id
         CROSS JOIN viewer
          LEFT JOIN LATERAL (
              SELECT attendee.event_id, attendee.response_status
                FROM cal_event_attendees attendee
               WHERE attendee.event_id = event.event_id
                 AND attendee.tenant_id = event.tenant_id
                 AND (
                     attendee.attendee_person_public_id = viewer.person_id
                     OR (attendee.attendee_person_public_id IS NULL
                         AND attendee.attendee_user_id = viewer.user_id)
                 )
               ORDER BY
                   CASE WHEN attendee.attendee_person_public_id = viewer.person_id
                       THEN 0 ELSE 1 END,
                   attendee.updated_at DESC
               LIMIT 1
          ) mine ON TRUE
          LEFT JOIN LATERAL (
              SELECT grant_row.access_level, grant_row.can_view_private
                FROM cal_calendar_access_grants grant_row
               WHERE grant_row.tenant_id = calendar.tenant_id
                 AND grant_row.calendar_id = calendar.calendar_id
                 AND grant_row.lifecycle_state = 'ACTIVE'
                 AND (grant_row.valid_until IS NULL
                      OR grant_row.valid_until > CURRENT_TIMESTAMP)
                 AND (
                     grant_row.principal_type = 'TENANT'
                     OR (grant_row.principal_type = 'PERSON'
                         AND grant_row.principal_person_public_id = viewer.person_id)
                     OR (grant_row.principal_type = 'GROUP'
                         AND grant_row.principal_group_ref = ANY (viewer.group_refs))
                 )
               ORDER BY CASE grant_row.access_level
                            WHEN 'MANAGE' THEN 4
                            WHEN 'EDIT' THEN 3
                            WHEN 'VIEW_DETAILS' THEN 2
                            ELSE 1
                        END DESC,
                        grant_row.updated_at DESC
               LIMIT 1
          ) access ON TRUE
          LEFT JOIN cal_calendar_subscriptions subscription
            ON subscription.tenant_id = calendar.tenant_id
           AND subscription.calendar_id = calendar.calendar_id
           AND subscription.person_public_id = viewer.person_id
          LEFT JOIN cal_event_user_preferences preference
            ON preference.tenant_id = event.tenant_id
           AND preference.event_id = event.event_id
           AND preference.person_public_id = viewer.person_id
          LEFT JOIN LATERAL (
              SELECT booking.resource_id
                FROM cal_resource_bookings booking
               WHERE booking.event_id = event.event_id
                 AND booking.tenant_id = event.tenant_id
                 AND booking.booking_status IN ('PENDING', 'CONFIRMED')
               ORDER BY booking.created_at LIMIT 1
          ) booking ON TRUE
          LEFT JOIN cal_resources resource
            ON resource.tenant_id = event.tenant_id
           AND resource.resource_id = booking.resource_id
         WHERE event.tenant_id = ?
           AND event.status <> 'CANCELLED'
           AND event.deleted_at IS NULL
           AND calendar.lifecycle_state = 'ACTIVE'
           AND NOT COALESCE(preference.hidden, FALSE)
           AND (
               (event.starts_at < ? AND event.ends_at > ?)
               OR (event.recurrence_pattern <> 'NONE'
                   AND event.starts_at < ?
                   AND (event.recurrence_until IS NULL
                        OR event.recurrence_until >= CAST(? AS date)))
           )
           AND (
               (calendar.calendar_type <> 'SYSTEM' AND (
                   event.organizer_person_public_id = viewer.person_id
                   OR (event.organizer_person_public_id IS NULL
                       AND event.organizer_user_id = viewer.user_id)))
               OR calendar.owner_person_public_id = viewer.person_id
               OR (calendar.owner_person_public_id IS NULL
                   AND calendar.owner_user_id = viewer.user_id)
               OR mine.event_id IS NOT NULL
               OR access.access_level IS NOT NULL
           )
         ORDER BY event.starts_at, event.event_id
        """;

    static final String ACCESS_DECISION = """
        WITH viewer AS (
            SELECT ?::bigint AS user_id, ?::uuid AS person_id, ?::uuid[] AS group_refs
        )
        SELECT calendar.calendar_id, calendar.calendar_type,
               calendar.subscription_policy,
               calendar.owner_user_id, calendar.owner_person_public_id,
               CASE
                   WHEN calendar.owner_person_public_id = viewer.person_id
                       OR (calendar.owner_person_public_id IS NULL
                           AND calendar.owner_user_id = viewer.user_id)
                       THEN 'OWNER'
                   ELSE access.access_level
               END AS access_level,
               COALESCE(access.can_view_private, FALSE) AS can_view_private,
               calendar.version
          FROM cal_calendars calendar
         CROSS JOIN viewer
          LEFT JOIN LATERAL (
              SELECT grant_row.access_level, grant_row.can_view_private
                FROM cal_calendar_access_grants grant_row
               WHERE grant_row.tenant_id = calendar.tenant_id
                 AND grant_row.calendar_id = calendar.calendar_id
                 AND grant_row.lifecycle_state = 'ACTIVE'
                 AND (grant_row.valid_until IS NULL
                      OR grant_row.valid_until > CURRENT_TIMESTAMP)
                 AND (
                     grant_row.principal_type = 'TENANT'
                     OR (grant_row.principal_type = 'PERSON'
                         AND grant_row.principal_person_public_id = viewer.person_id)
                     OR (grant_row.principal_type = 'GROUP'
                         AND grant_row.principal_group_ref = ANY (viewer.group_refs))
                 )
               ORDER BY CASE grant_row.access_level
                            WHEN 'MANAGE' THEN 4
                            WHEN 'EDIT' THEN 3
                            WHEN 'VIEW_DETAILS' THEN 2
                            ELSE 1
                        END DESC,
                        grant_row.updated_at DESC
               LIMIT 1
          ) access ON TRUE
         WHERE calendar.tenant_id = ? AND calendar.calendar_id = ?
           AND calendar.lifecycle_state = 'ACTIVE'
           AND (
               calendar.owner_person_public_id = viewer.person_id
               OR (calendar.owner_person_public_id IS NULL
                   AND calendar.owner_user_id = viewer.user_id)
               OR access.access_level IS NOT NULL
           )
        """;

    static final String SHARES = """
        SELECT grant_id, principal_type, principal_person_public_id,
               principal_group_ref, principal_display_name, access_level,
               can_view_private, valid_until,
               CASE WHEN valid_until <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                    ELSE lifecycle_state END AS lifecycle_state,
               version
          FROM cal_calendar_access_grants
         WHERE tenant_id = ? AND calendar_id = ?
           AND principal_type = 'PERSON' AND lifecycle_state = 'ACTIVE'
         ORDER BY principal_display_name, grant_id
        """;

    static final String UPSERT_PERSON_SHARE = """
        INSERT INTO cal_calendar_access_grants (
            grant_id, tenant_id, calendar_id, principal_type,
            principal_person_public_id, principal_display_name,
            access_level, can_view_private, valid_until,
            lifecycle_state, version, created_by, updated_by)
        VALUES (gen_random_uuid(), ?, ?, 'PERSON', ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
        ON CONFLICT (tenant_id, calendar_id, principal_person_public_id)
            WHERE principal_type = 'PERSON' AND lifecycle_state = 'ACTIVE'
        DO UPDATE SET principal_display_name = EXCLUDED.principal_display_name,
            access_level = EXCLUDED.access_level,
            can_view_private = EXCLUDED.can_view_private,
            valid_until = EXCLUDED.valid_until,
            version = cal_calendar_access_grants.version + 1,
            updated_at = CURRENT_TIMESTAMP,
            updated_by = EXCLUDED.updated_by
        WHERE cal_calendar_access_grants.version = ?
        RETURNING grant_id, principal_type, principal_person_public_id,
                  principal_group_ref, principal_display_name, access_level,
                  can_view_private, valid_until, lifecycle_state, version
        """;

    static final String REVOKE_SHARE = """
        UPDATE cal_calendar_access_grants
           SET lifecycle_state = 'REVOKED', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND calendar_id = ? AND grant_id = ?
           AND principal_type = 'PERSON' AND lifecycle_state = 'ACTIVE'
           AND version = ?
        """;

    static final String UPSERT_SUBSCRIPTION = """
        INSERT INTO cal_calendar_subscriptions (
            tenant_id, person_public_id, calendar_id,
            selected, favorite, display_order, color_override, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0)
        ON CONFLICT (tenant_id, person_public_id, calendar_id)
        DO UPDATE SET selected = EXCLUDED.selected,
            favorite = EXCLUDED.favorite,
            display_order = EXCLUDED.display_order,
            color_override = EXCLUDED.color_override,
            version = cal_calendar_subscriptions.version + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE cal_calendar_subscriptions.version = ?
        RETURNING selected, favorite, display_order, color_override, version
        """;

    static final String UPSERT_EVENT_PREFERENCE = """
        INSERT INTO cal_event_user_preferences (
            tenant_id, person_public_id, event_id, starred, hidden, version)
        VALUES (?, ?, ?, ?, ?, 0)
        ON CONFLICT (tenant_id, person_public_id, event_id)
        DO UPDATE SET starred = EXCLUDED.starred,
            hidden = EXCLUDED.hidden,
            version = cal_event_user_preferences.version + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE cal_event_user_preferences.version = ?
        RETURNING starred, hidden, version
        """;

    static final String AUTHORIZED_FREE_BUSY_PEOPLE = """
        SELECT DISTINCT calendar.owner_person_public_id
          FROM cal_calendars calendar
          JOIN cal_calendar_access_grants grant_row
            ON grant_row.tenant_id = calendar.tenant_id
           AND grant_row.calendar_id = calendar.calendar_id
         WHERE calendar.tenant_id = ?
           AND calendar.calendar_type = 'PERSONAL'
           AND calendar.lifecycle_state = 'ACTIVE'
           AND calendar.owner_person_public_id = ANY (?::uuid[])
           AND grant_row.lifecycle_state = 'ACTIVE'
           AND (grant_row.valid_until IS NULL
                OR grant_row.valid_until > CURRENT_TIMESTAMP)
           AND (
               (grant_row.principal_type = 'PERSON'
                   AND grant_row.principal_person_public_id = ?)
               OR (grant_row.principal_type = 'GROUP'
                   AND grant_row.principal_group_ref = ANY (?::uuid[]))
           )
        """;
}
