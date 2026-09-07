package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class CalendarTeamAvailabilityRepository {
    private final JdbcTemplate jdbc;

    public CalendarTeamAvailabilityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // No tenant-wide grants, directory scan, subscription-based authority, or user-supplied targets.
    // The viewer's People identity link must match both tenant AND authenticated user.
    private static final String AUTHORIZED_MEMBERS = """
            WITH viewer AS (
                SELECT ?::bigint AS tenant_id, ?::bigint AS user_id,
                       ?::uuid AS person_id, ?::uuid[] AS group_refs, ?::timestamptz AS observed_at
            ), eligible AS (
                SELECT calendar.owner_person_public_id AS person_id,
                       NULLIF(BTRIM(calendar.owner_display_name), '') AS display_name,
                       grant_row.valid_until
                  FROM viewer
                  JOIN cal_identity_links viewer_identity
                    ON viewer_identity.tenant_id = viewer.tenant_id
                   AND viewer_identity.user_id = viewer.user_id
                   AND viewer_identity.person_public_id = viewer.person_id
                  JOIN cal_calendars calendar ON calendar.tenant_id = viewer.tenant_id
                  JOIN cal_identity_links owner_identity
                    ON owner_identity.tenant_id = calendar.tenant_id
                   AND owner_identity.person_public_id = calendar.owner_person_public_id
                  JOIN cal_calendar_access_grants grant_row
                    ON grant_row.tenant_id = calendar.tenant_id
                   AND grant_row.calendar_id = calendar.calendar_id
                 WHERE calendar.calendar_type = 'PERSONAL'
                   AND calendar.lifecycle_state = 'ACTIVE'
                   AND calendar.owner_person_public_id <> viewer.person_id
                   AND grant_row.lifecycle_state = 'ACTIVE'
                   AND grant_row.access_level IN ('VIEW_FREE_BUSY', 'VIEW_DETAILS', 'EDIT', 'MANAGE')
                   AND (grant_row.valid_until IS NULL OR grant_row.valid_until > viewer.observed_at)
                   AND ((grant_row.principal_type = 'PERSON'
                         AND grant_row.principal_person_public_id = viewer.person_id)
                     OR (grant_row.principal_type = 'GROUP'
                         AND grant_row.principal_group_ref = ANY(viewer.group_refs)))
            ), members AS (
                SELECT person_id, MAX(display_name) AS display_name, MIN(valid_until) AS valid_until
                  FROM eligible GROUP BY person_id
            )
            """;

    boolean matchesIdentity(CalendarTeamAvailabilityAccess.Actor actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM cal_identity_links
                 WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?)
                """, Boolean.class, actor.tenantId(), actor.userId(), actor.personPublicId()));
    }

    List<MemberRow> members(CalendarTeamAvailabilityAccess.Actor actor, OffsetDateTime now, int limit) {
        return jdbc.query(AUTHORIZED_MEMBERS + """
                SELECT person_id, display_name, valid_until FROM members
                 ORDER BY display_name NULLS LAST, person_id LIMIT ?
                """, (row, index) -> new MemberRow(
                row.getObject("person_id", UUID.class), row.getString("display_name"),
                row.getObject("valid_until", OffsetDateTime.class)),
                actor.tenantId(), actor.userId(), actor.personPublicId(), groups(actor), now, limit);
    }

    List<ScheduleRow> schedules(
            CalendarTeamAvailabilityAccess.Actor actor, OffsetDateTime now,
            List<UUID> memberIds, OffsetDateTime from, OffsetDateTime to, int limit) {
        if (memberIds.isEmpty()) return List.of();
        return jdbc.query(AUTHORIZED_MEMBERS + """
                SELECT member.person_id, event.event_id, event.starts_at, event.ends_at,
                       event.time_zone, event.recurrence_pattern, event.recurrence_interval,
                       event.recurrence_until,
                       CASE WHEN event.event_type IN ('FOCUS', 'OUT_OF_OFFICE') AND (
                           calendar.owner_person_public_id = viewer.person_id
                           OR event.organizer_person_public_id = viewer.person_id
                           OR EXISTS (
                               SELECT 1 FROM cal_event_attendees mine
                                WHERE mine.tenant_id = viewer.tenant_id AND mine.event_id = event.event_id
                                  AND mine.attendee_person_public_id = viewer.person_id)
                           OR EXISTS (
                               SELECT 1 FROM cal_calendar_access_grants detail_grant
                                WHERE detail_grant.tenant_id = viewer.tenant_id
                                  AND detail_grant.calendar_id = event.calendar_id
                                  AND detail_grant.lifecycle_state = 'ACTIVE'
                                  AND detail_grant.access_level IN ('VIEW_DETAILS', 'EDIT', 'MANAGE')
                                  AND (event.visibility NOT IN ('PRIVATE', 'CONFIDENTIAL')
                                       OR detail_grant.can_view_private)
                                  AND (detail_grant.valid_until IS NULL
                                       OR detail_grant.valid_until > viewer.observed_at + INTERVAL '30 seconds')
                                  AND ((detail_grant.principal_type = 'PERSON'
                                        AND detail_grant.principal_person_public_id = viewer.person_id)
                                    OR (detail_grant.principal_type = 'GROUP'
                                        AND detail_grant.principal_group_ref = ANY(viewer.group_refs))))
                       ) THEN event.event_type ELSE 'BUSY' END AS visible_type
                  FROM viewer
                  JOIN members member ON member.person_id = ANY(?::uuid[])
                  JOIN cal_events event ON event.tenant_id = viewer.tenant_id
                  JOIN cal_calendars calendar
                    ON calendar.tenant_id = viewer.tenant_id AND calendar.calendar_id = event.calendar_id
                  LEFT JOIN cal_identity_links organizer_identity
                    ON organizer_identity.tenant_id = viewer.tenant_id
                   AND organizer_identity.user_id = event.organizer_user_id
                 WHERE calendar.lifecycle_state = 'ACTIVE'
                   AND event.status <> 'CANCELLED' AND event.deleted_at IS NULL
                   AND (COALESCE(event.organizer_person_public_id, organizer_identity.person_public_id) = member.person_id
                        OR EXISTS (
                            SELECT 1 FROM cal_event_attendees attendee
                            LEFT JOIN cal_identity_links attendee_identity
                              ON attendee_identity.tenant_id = attendee.tenant_id
                             AND attendee_identity.user_id = attendee.attendee_user_id
                             WHERE attendee.tenant_id = viewer.tenant_id AND attendee.event_id = event.event_id
                               AND attendee.response_status <> 'DECLINED'
                               AND COALESCE(attendee.attendee_person_public_id, attendee_identity.person_public_id) = member.person_id))
                   AND ((event.starts_at < ? AND (event.ends_at > ? OR event.recurrence_pattern <> 'NONE'))
                        OR EXISTS (
                            SELECT 1 FROM cal_event_occurrence_overrides moved
                             WHERE moved.tenant_id = viewer.tenant_id AND moved.event_id = event.event_id
                               AND moved.override_kind = 'MODIFIED' AND moved.starts_at < ? AND moved.ends_at > ?))
                 ORDER BY member.person_id, event.event_id LIMIT ?
                """, (row, index) -> new ScheduleRow(
                row.getObject("person_id", UUID.class), row.getObject("event_id", UUID.class),
                row.getObject("starts_at", OffsetDateTime.class), row.getObject("ends_at", OffsetDateTime.class),
                row.getString("time_zone"), row.getString("recurrence_pattern"),
                row.getInt("recurrence_interval"), row.getObject("recurrence_until", LocalDate.class),
                CalendarTeamAvailabilityDtos.Status.valueOf(row.getString("visible_type"))),
                actor.tenantId(), actor.userId(), actor.personPublicId(), groups(actor), now,
                memberIds.toArray(UUID[]::new), to, from, to, from, limit);
    }

    List<OverrideRow> overrides(long tenantId, List<UUID> eventIds, int limit) {
        if (eventIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT event_id, original_starts_at, override_kind, starts_at, ends_at
                  FROM cal_event_occurrence_overrides
                 WHERE tenant_id = ? AND event_id = ANY(?::uuid[])
                 ORDER BY event_id, original_starts_at LIMIT ?
                """, (row, index) -> new OverrideRow(
                row.getObject("event_id", UUID.class), row.getObject("original_starts_at", OffsetDateTime.class),
                row.getString("override_kind"), row.getObject("starts_at", OffsetDateTime.class),
                row.getObject("ends_at", OffsetDateTime.class)), tenantId, eventIds.toArray(UUID[]::new), limit);
    }

    private UUID[] groups(CalendarTeamAvailabilityAccess.Actor actor) {
        return actor.groupRefs().toArray(UUID[]::new);
    }

    record MemberRow(UUID personPublicId, String displayName, OffsetDateTime validUntil) { }
    record ScheduleRow(UUID personPublicId, UUID eventId, OffsetDateTime startsAt, OffsetDateTime endsAt,
                       String timeZone, String recurrence, int recurrenceInterval,
                       LocalDate recurrenceUntil, CalendarTeamAvailabilityDtos.Status visibleType) { }
    record OverrideRow(UUID eventId, OffsetDateTime originalStartsAt, String kind,
                       OffsetDateTime startsAt, OffsetDateTime endsAt) { }
}
