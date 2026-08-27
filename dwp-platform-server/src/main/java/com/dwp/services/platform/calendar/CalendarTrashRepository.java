package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class CalendarTrashRepository {

    private static final String TRASHED_EVENTS = """
            WITH viewer AS (
                SELECT ?::bigint AS user_id, ?::uuid AS person_id,
                       ?::uuid[] AS group_refs
            )
            SELECT event.event_id, event.calendar_id,
                   CASE WHEN ? THEN calendar.name_ko ELSE calendar.name_en END AS calendar_name,
                   calendar.color_hex, event.title, event.starts_at, event.ends_at,
                   event.deleted_at, event.purge_after, event.legal_hold,
                   event.deletion_reason, event.importance, event.version,
                   (event.legal_hold OR event.purge_after > CURRENT_TIMESTAMP) AS restorable,
                   CASE
                       WHEN event.organizer_person_public_id = viewer.person_id
                           OR (event.organizer_person_public_id IS NULL
                               AND event.organizer_user_id = viewer.user_id)
                           OR calendar.owner_person_public_id = viewer.person_id
                           OR (calendar.owner_person_public_id IS NULL
                               AND calendar.owner_user_id = viewer.user_id)
                           OR mine.event_id IS NOT NULL
                           OR event.visibility NOT IN ('PRIVATE', 'CONFIDENTIAL')
                           OR COALESCE(access.can_view_private, FALSE)
                           THEN TRUE
                       ELSE FALSE
                   END AS can_view_details
              FROM cal_events event
              JOIN cal_calendars calendar
                ON calendar.tenant_id = event.tenant_id
               AND calendar.calendar_id = event.calendar_id
             CROSS JOIN viewer
              LEFT JOIN LATERAL (
                  SELECT grant_row.access_level, grant_row.can_view_private
                    FROM cal_calendar_access_grants grant_row
                   WHERE grant_row.tenant_id = calendar.tenant_id
                     AND grant_row.calendar_id = calendar.calendar_id
                     AND grant_row.lifecycle_state = 'ACTIVE'
                     AND (grant_row.valid_until IS NULL
                          OR grant_row.valid_until > CURRENT_TIMESTAMP)
                     AND grant_row.access_level = 'MANAGE'
                     AND (
                         (grant_row.principal_type = 'PERSON'
                             AND grant_row.principal_person_public_id = viewer.person_id)
                         OR (grant_row.principal_type = 'GROUP'
                             AND grant_row.principal_group_ref = ANY (viewer.group_refs))
                     )
                   ORDER BY grant_row.updated_at DESC
                   LIMIT 1
              ) access ON TRUE
              LEFT JOIN LATERAL (
                  SELECT attendee.event_id
                    FROM cal_event_attendees attendee
                   WHERE attendee.tenant_id = event.tenant_id
                     AND attendee.event_id = event.event_id
                     AND (
                         attendee.attendee_person_public_id = viewer.person_id
                         OR (attendee.attendee_person_public_id IS NULL
                             AND attendee.attendee_user_id = viewer.user_id)
                     )
                   LIMIT 1
              ) mine ON TRUE
             WHERE event.tenant_id = ?
               AND event.deleted_at IS NOT NULL
               AND calendar.lifecycle_state = 'ACTIVE'
               AND (
                   event.organizer_person_public_id = viewer.person_id
                   OR (event.organizer_person_public_id IS NULL
                       AND event.organizer_user_id = viewer.user_id)
                   OR calendar.owner_person_public_id = viewer.person_id
                   OR (calendar.owner_person_public_id IS NULL
                       AND calendar.owner_user_id = viewer.user_id)
                   OR access.access_level = 'MANAGE'
               )
             ORDER BY event.deleted_at DESC, event.event_id
             LIMIT 200
            """;

    private final JdbcTemplate jdbc;

    CalendarTrashRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TrashRow> trashedEvents(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            boolean korean) {
        return jdbc.query(
                TRASHED_EVENTS,
                (result, ignored) -> new TrashRow(
                        result.getObject("event_id", UUID.class),
                        result.getObject("calendar_id", UUID.class),
                        result.getString("calendar_name"),
                        result.getString("color_hex"),
                        result.getString("title"),
                        result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class),
                        result.getObject("deleted_at", OffsetDateTime.class),
                        result.getObject("purge_after", OffsetDateTime.class),
                        result.getBoolean("legal_hold"),
                        result.getString("deletion_reason"),
                        CalendarTypes.EventImportance.valueOf(result.getString("importance")),
                        result.getLong("version"),
                        result.getBoolean("restorable"),
                        result.getBoolean("can_view_details")),
                userId,
                personPublicId,
                CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                korean,
                tenantId);
    }

    record TrashRow(
            UUID eventId,
            UUID calendarId,
            String calendarName,
            String calendarColor,
            String title,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            boolean legalHold,
            String deletionReason,
            CalendarTypes.EventImportance importance,
            long version,
            boolean restorable,
            boolean canViewDetails) {
    }
}
