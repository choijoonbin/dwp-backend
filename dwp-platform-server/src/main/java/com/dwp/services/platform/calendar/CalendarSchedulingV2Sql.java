package com.dwp.services.platform.calendar;

/** SQL isolated from the v1 Calendar query surface. */
final class CalendarSchedulingV2Sql {

    private CalendarSchedulingV2Sql() {
    }

    static final String IDENTITY_MATCH = """
        SELECT EXISTS (
            SELECT 1 FROM cal_identity_links
             WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?)
        """;

    static final String AUTHORIZED_PEOPLE = """
        SELECT DISTINCT identity.person_public_id
          FROM cal_identity_links identity
          JOIN cal_calendars calendar
            ON calendar.tenant_id = identity.tenant_id
           AND calendar.owner_person_public_id = identity.person_public_id
           AND calendar.calendar_type = 'PERSONAL'
           AND calendar.lifecycle_state = 'ACTIVE'
          JOIN cal_calendar_access_grants grant_row
            ON grant_row.tenant_id = calendar.tenant_id
           AND grant_row.calendar_id = calendar.calendar_id
           AND grant_row.lifecycle_state = 'ACTIVE'
           AND (grant_row.valid_until IS NULL
                OR grant_row.valid_until > CURRENT_TIMESTAMP)
         WHERE identity.tenant_id = ?
           AND identity.person_public_id = ANY (?::uuid[])
           AND (
               (grant_row.principal_type = 'PERSON'
                   AND grant_row.principal_person_public_id = ?)
               OR (grant_row.principal_type = 'GROUP'
                   AND grant_row.principal_group_ref = ANY (?::uuid[])))
        """;

    static final String WORKING_SETTINGS = """
        SELECT identity.user_id,
               COALESCE(settings.working_days_mask, 31) AS working_days_mask,
               COALESCE(settings.working_day_start, policy.working_day_start,
                        TIME '09:00') AS working_day_start,
               COALESCE(settings.working_day_end, policy.working_day_end,
                        TIME '18:00') AS working_day_end,
               COALESCE(settings.time_zone, ?) AS time_zone,
               COALESCE(settings.version, policy.version, 0) AS version,
               CASE WHEN settings.user_id IS NULL
                    THEN 'TENANT_POLICY' ELSE 'USER_SETTINGS' END AS source_type
          FROM cal_identity_links identity
          LEFT JOIN cal_user_settings settings
            ON settings.tenant_id = identity.tenant_id
           AND settings.person_public_id = identity.person_public_id
          LEFT JOIN cal_tenant_policies policy
            ON policy.tenant_id = identity.tenant_id
         WHERE identity.tenant_id = ? AND identity.person_public_id = ?
         ORDER BY CASE WHEN settings.user_id IS NULL THEN 1 ELSE 0 END,
                  identity.last_seen_at DESC
         LIMIT 1
        """;

    static final String POLICY = """
        SELECT COALESCE(policy.working_day_start, TIME '09:00') AS working_day_start,
               COALESCE(policy.working_day_end, TIME '18:00') AS working_day_end,
               COALESCE(policy.minimum_event_minutes, 15) AS minimum_event_minutes,
               COALESCE(policy.maximum_event_minutes, 480) AS maximum_event_minutes,
               COALESCE(policy.default_buffer_minutes, 10) AS default_buffer_minutes,
               COALESCE(policy.version, 0) AS version
          FROM (SELECT ?::bigint AS tenant_id) requested
          LEFT JOIN cal_tenant_policies policy ON policy.tenant_id = requested.tenant_id
        """;

    static final String INTERVALS = """
        WITH requested AS (
            SELECT ?::timestamptz AS window_from, ?::timestamptz AS window_to
        ), relevant_events AS (
            SELECT event.tenant_id, event.event_id, subject.person_public_id,
                   event.starts_at, event.ends_at, event.time_zone,
                   event.event_type, event.status,
                   event.recurrence_pattern, event.recurrence_interval,
                   event.recurrence_until, event.recurrence_count,
                   event.recurrence_days, subject.tentative
              FROM cal_events event
              LEFT JOIN cal_identity_links organizer_identity
                ON organizer_identity.tenant_id = event.tenant_id
               AND organizer_identity.user_id = event.organizer_user_id
              JOIN LATERAL (
                  SELECT candidate.person_public_id,
                         BOOL_OR(candidate.tentative) AS tentative
                    FROM (
                        SELECT COALESCE(event.organizer_person_public_id,
                                   organizer_identity.person_public_id) AS person_public_id,
                               FALSE AS tentative
                         WHERE COALESCE(event.organizer_person_public_id,
                                   organizer_identity.person_public_id) = ANY (?::uuid[])
                        UNION ALL
                        SELECT attendee.attendee_person_public_id,
                               attendee.response_status = 'TENTATIVE'
                          FROM cal_event_attendees attendee
                         WHERE attendee.tenant_id = event.tenant_id
                           AND attendee.event_id = event.event_id
                           AND attendee.response_status <> 'DECLINED'
                           AND attendee.attendee_person_public_id = ANY (?::uuid[])
                    ) candidate
                   GROUP BY candidate.person_public_id
              ) subject ON TRUE
              CROSS JOIN requested requested_window
             WHERE event.tenant_id = ?
               AND event.status <> 'CANCELLED'
               AND event.deleted_at IS NULL
               AND (
                   event.starts_at < requested_window.window_to
                   OR EXISTS (
                       SELECT 1 FROM cal_event_occurrence_overrides moved
                        WHERE moved.tenant_id = event.tenant_id
                          AND moved.event_id = event.event_id
                          AND moved.override_kind = 'MODIFIED'
                          AND moved.starts_at < requested_window.window_to
                          AND moved.ends_at > requested_window.window_from))
        ), base_occurrences AS (
            SELECT event.tenant_id, event.event_id, event.person_public_id,
                   occurrence.original_starts_at,
                   occurrence.starts_at, occurrence.ends_at,
                   event.time_zone, event.event_type, event.status, event.tentative
              FROM relevant_events event
              CROSS JOIN requested requested_window
              CROSS JOIN LATERAL cal_project_recurrences(
                  event.starts_at, event.ends_at, event.time_zone,
                  event.recurrence_pattern, event.recurrence_interval,
                  event.recurrence_until, event.recurrence_count,
                  event.recurrence_days, requested_window.window_from,
                  requested_window.window_to, 4000) occurrence
        ), occurrences AS (
            SELECT base.person_public_id, base.starts_at, base.ends_at,
                   CASE
                       WHEN base.event_type = 'OUT_OF_OFFICE' THEN 'OUT_OF_OFFICE'
                       WHEN base.status = 'TENTATIVE' OR base.tentative THEN 'TENTATIVE'
                       ELSE 'BUSY'
                   END AS interval_state
              FROM base_occurrences base
              LEFT JOIN cal_event_occurrence_overrides override
                ON override.tenant_id = base.tenant_id
               AND override.event_id = base.event_id
               AND override.original_starts_at = base.original_starts_at
             WHERE override.event_id IS NULL
            UNION ALL
            SELECT event.person_public_id, override.starts_at, override.ends_at,
                   CASE
                       WHEN COALESCE(override.event_type, event.event_type) = 'OUT_OF_OFFICE'
                           THEN 'OUT_OF_OFFICE'
                       WHEN event.status = 'TENTATIVE' OR event.tentative THEN 'TENTATIVE'
                       ELSE 'BUSY'
                   END
              FROM relevant_events event
              JOIN cal_event_occurrence_overrides override
                ON override.tenant_id = event.tenant_id
               AND override.event_id = event.event_id
               AND override.override_kind = 'MODIFIED'
        )
        SELECT person_public_id, starts_at, ends_at, interval_state,
               'DWP_NATIVE' AS source_type
          FROM occurrences
         WHERE starts_at IS NOT NULL AND ends_at IS NOT NULL
           AND starts_at < (SELECT window_to FROM requested)
           AND ends_at > (SELECT window_from FROM requested)
         ORDER BY person_public_id, starts_at, ends_at
        """;

    static final String SOURCE_VERSION = """
        SELECT CONCAT_WS('|',
                   COALESCE((SELECT MAX(updated_at)::text FROM cal_events
                              WHERE tenant_id = ?), '-'),
                   COALESCE((SELECT MAX(updated_at)::text
                               FROM cal_event_occurrence_overrides
                              WHERE tenant_id = ?), '-'),
                   COALESCE((SELECT MAX(attendee.updated_at)::text
                               FROM cal_event_attendees attendee
                               JOIN cal_events event
                                 ON event.event_id = attendee.event_id
                                AND event.tenant_id = attendee.tenant_id
                              WHERE event.tenant_id = ?), '-'),
                   COALESCE((SELECT MAX(updated_at)::text FROM cal_user_settings
                              WHERE tenant_id = ?), '-'))
        """;

    static final String ROOMS = """
        SELECT resource.resource_id, resource.resource_code,
               resource.name_ko, resource.name_en, resource.resource_type,
               resource.site_name, resource.floor_name, resource.capacity,
               resource.features::text, resource.time_zone,
               resource.approval_required, resource.lifecycle_state,
               resource.version,
               EXISTS (
                   SELECT 1 FROM cal_resource_bookings booking
                    WHERE booking.tenant_id = resource.tenant_id
                      AND booking.resource_id = resource.resource_id
                      AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                      AND booking.starts_at < ? AND booking.ends_at > ?
               ) AS conflicted
          FROM cal_resources resource
         WHERE resource.tenant_id = ? AND resource.resource_type = 'ROOM'
         ORDER BY resource.resource_code
        """;

    static final String ROOM = """
        SELECT resource.resource_id, resource.resource_code,
               resource.name_ko, resource.name_en, resource.resource_type,
               resource.site_name, resource.floor_name, resource.capacity,
               resource.features::text, resource.time_zone,
               resource.approval_required, resource.lifecycle_state,
               resource.version,
               EXISTS (
                   SELECT 1 FROM cal_resource_bookings booking
                    WHERE booking.tenant_id = resource.tenant_id
                      AND booking.resource_id = resource.resource_id
                      AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                      AND booking.starts_at < ? AND booking.ends_at > ?
               ) AS conflicted
          FROM cal_resources resource
         WHERE resource.tenant_id = ? AND resource.resource_id = ?
           AND resource.resource_type = 'ROOM'
        """;

    static final String INSERT_EVALUATION = """
        INSERT INTO cal_scheduling_evaluations_v2 (
            evaluation_id, tenant_id, actor_user_id, actor_person_public_id,
            criteria_hash, participant_hash, criteria_payload, source_evidence,
            room_evidence, source_version, challenge_token_hash, generated_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
        """;

    static final String EVALUATION = """
        SELECT evaluation_id, criteria_hash, participant_hash,
               criteria_payload::text, source_evidence::text, room_evidence::text,
               source_version,
               challenge_token_hash, generated_at, expires_at
          FROM cal_scheduling_evaluations_v2
         WHERE tenant_id = ? AND evaluation_id = ? AND actor_user_id = ?
           AND actor_person_public_id IS NOT DISTINCT FROM ?
        """;

    static final String CONSUME_CHALLENGE = """
        UPDATE cal_scheduling_evaluations_v2
           SET challenge_consumed_at = ?
         WHERE tenant_id = ? AND evaluation_id = ? AND actor_user_id = ?
           AND actor_person_public_id = ?
           AND criteria_hash = ? AND challenge_token_hash = ?
           AND challenge_consumed_at IS NULL AND expires_at > ?
        RETURNING evaluation_id, criteria_hash, participant_hash,
                  criteria_payload::text, source_evidence::text, room_evidence::text,
                  source_version, challenge_token_hash, generated_at, expires_at
        """;

    static final String INSERT_RECHECK = """
        INSERT INTO cal_scheduling_rechecks_v2 (
            recheck_id, tenant_id, evaluation_id, actor_user_id,
            actor_person_public_id, criteria_hash, participant_hash,
            selection_hash, selected_room_id, source_version,
            resource_version, policy_version, result_status,
            hard_recheck_token_hash, evidence_payload, checked_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """;

    static final String CONSUME_RECHECK_TOKEN = """
        UPDATE cal_scheduling_rechecks_v2 recheck
           SET consumed_at = ?, consumed_by_event_id = ?
          FROM cal_scheduling_evaluations_v2 evaluation
         WHERE recheck.tenant_id = ? AND recheck.actor_user_id = ?
           AND recheck.actor_person_public_id = ?
           AND recheck.hard_recheck_token_hash = ?
           AND recheck.participant_hash = ? AND recheck.selection_hash = ?
           AND recheck.source_version = ?
           AND recheck.selected_room_id IS NOT DISTINCT FROM ?
           AND recheck.resource_version IS NOT DISTINCT FROM ?
           AND recheck.policy_version = ?
           AND recheck.result_status = 'PASS' AND recheck.expires_at > ?
           AND recheck.consumed_at IS NULL
           AND evaluation.tenant_id = recheck.tenant_id
           AND evaluation.evaluation_id = recheck.evaluation_id
           AND evaluation.actor_user_id = recheck.actor_user_id
           AND evaluation.actor_person_public_id = recheck.actor_person_public_id
           AND evaluation.criteria_hash = recheck.criteria_hash
        """;

    static final String PURGE_EXPIRED = """
        WITH expired AS (
            SELECT evaluation_id
              FROM cal_scheduling_evaluations_v2
             WHERE tenant_id = ? AND expires_at < ?
             ORDER BY expires_at, evaluation_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
        )
        DELETE FROM cal_scheduling_evaluations_v2 evidence
         USING expired
         WHERE evidence.tenant_id = ?
           AND evidence.evaluation_id = expired.evaluation_id
        """;

    static final String PURGE_EXPIRED_GLOBAL = """
        WITH expired AS (
            SELECT tenant_id, evaluation_id
              FROM cal_scheduling_evaluations_v2
             WHERE expires_at < ?
             ORDER BY expires_at, tenant_id, evaluation_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
        )
        DELETE FROM cal_scheduling_evaluations_v2 evidence
         USING expired
         WHERE evidence.tenant_id = expired.tenant_id
           AND evidence.evaluation_id = expired.evaluation_id
        """;
}
