package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.CalendarAccessLevel;
import static com.dwp.services.platform.calendar.CalendarTypes.CalendarSubscriptionPolicy;
import static com.dwp.services.platform.calendar.CalendarTypes.CalendarType;

/** Owns identity and authorization queries used by the calendar aggregate. */
final class CalendarAccessRepository {

    private final JdbcTemplate jdbc;

    CalendarAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CalendarRepositoryRows.BusyRow> busySlots(
            Long tenantId,
            List<UUID> personPublicIds,
            OffsetDateTime from,
            OffsetDateTime to) {
        if (personPublicIds.isEmpty()) return List.of();
        UUID[] ids = personPublicIds.toArray(UUID[]::new);
        return jdbc.query(
                CalendarSql01.BUSY_SLOTS_WITH_CAL_EVENTS,
                (result, ignored) -> new CalendarRepositoryRows.BusyRow(
                        result.getObject("person_public_id", UUID.class),
                        result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class)),
                ids, ids, tenantId, to, to, from, from, to, to, from);
    }

    Set<UUID> knownPersonPublicIds(Long tenantId, List<UUID> personPublicIds) {
        if (personPublicIds.isEmpty()) return Set.of();
        UUID[] ids = personPublicIds.toArray(UUID[]::new);
        return Set.copyOf(jdbc.query(
                CalendarSql01.KNOWN_PEOPLE_SELECT_CAL_IDENTITY_LINKS,
                (result, ignored) -> result.getObject("person_public_id", UUID.class),
                tenantId,
                ids));
    }

    Set<UUID> authorizedFreeBusyPersonIds(
            Long tenantId,
            UUID viewerPersonPublicId,
            String verifiedGroupRefs,
            List<UUID> requestedPersonPublicIds) {
        if (requestedPersonPublicIds.isEmpty() || viewerPersonPublicId == null) return Set.of();
        UUID[] requested = requestedPersonPublicIds.toArray(UUID[]::new);
        return Set.copyOf(jdbc.query(
                CalendarAccessSql.AUTHORIZED_FREE_BUSY_PEOPLE,
                (result, ignored) -> result.getObject("owner_person_public_id", UUID.class),
                tenantId,
                requested,
                viewerPersonPublicId,
                CalendarVerifiedGroups.databaseArray(verifiedGroupRefs)));
    }

    Optional<CalendarRepositoryRows.CalendarAccessDecision> calendarAccess(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        return jdbc.query(
                CalendarAccessSql.ACCESS_DECISION,
                (result, ignored) -> new CalendarRepositoryRows.CalendarAccessDecision(
                        result.getObject("calendar_id", UUID.class),
                        CalendarType.valueOf(result.getString("calendar_type")),
                        CalendarSubscriptionPolicy.valueOf(
                                result.getString("subscription_policy")),
                        nullableLong(result, "owner_user_id"),
                        result.getObject("owner_person_public_id", UUID.class),
                        result.getString("access_level") == null
                                ? CalendarAccessLevel.NONE
                                : CalendarAccessLevel.valueOf(result.getString("access_level")),
                        result.getBoolean("can_view_private"),
                        result.getLong("version")),
                userId,
                personPublicId,
                CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                tenantId,
                calendarId).stream().findFirst();
    }

    boolean lockActiveCreateDelegation(
            Long tenantId,
            UUID calendarId,
            UUID delegatePersonPublicId) {
        if (delegatePersonPublicId == null) return false;
        return !jdbc.query("""
                SELECT delegation.delegation_id
                  FROM cal_calendars calendar
                  JOIN cal_calendar_delegations delegation
                    ON delegation.tenant_id = calendar.tenant_id
                   AND delegation.owner_person_public_id = calendar.owner_person_public_id
                 WHERE calendar.tenant_id = ? AND calendar.calendar_id = ?
                   AND calendar.lifecycle_state = 'ACTIVE'
                   AND delegation.delegate_person_public_id = ?
                   AND delegation.can_create
                   AND delegation.status = 'ACTIVE'
                   AND delegation.valid_from <= CURRENT_TIMESTAMP
                   AND delegation.valid_until > CURRENT_TIMESTAMP
                 ORDER BY delegation.updated_at DESC, delegation.delegation_id
                 LIMIT 1
                   FOR UPDATE OF delegation
                """, (row, ignored) -> row.getObject("delegation_id", UUID.class),
                tenantId, calendarId, delegatePersonPublicId).isEmpty();
    }

    boolean hasDirectCreateAccess(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT (
                    calendar.owner_person_public_id = ?
                    OR (calendar.owner_person_public_id IS NULL
                        AND calendar.owner_user_id = ?)
                    OR EXISTS (
                        SELECT 1
                          FROM cal_calendar_access_grants grant_row
                         WHERE grant_row.tenant_id = calendar.tenant_id
                           AND grant_row.calendar_id = calendar.calendar_id
                           AND grant_row.lifecycle_state = 'ACTIVE'
                           AND (grant_row.valid_until IS NULL
                                OR grant_row.valid_until > CURRENT_TIMESTAMP)
                           AND grant_row.access_level IN ('EDIT', 'MANAGE')
                           AND (
                               grant_row.principal_type = 'TENANT'
                               OR (grant_row.principal_type = 'PERSON'
                                   AND grant_row.principal_person_public_id = ?)
                               OR (grant_row.principal_type = 'GROUP'
                                   AND grant_row.principal_group_ref = ANY (?::uuid[])))))
                  FROM cal_calendars calendar
                 WHERE calendar.tenant_id = ? AND calendar.calendar_id = ?
                   AND calendar.lifecycle_state = 'ACTIVE'
                """, Boolean.class,
                personPublicId,
                userId,
                personPublicId,
                CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                tenantId,
                calendarId);
        return Boolean.TRUE.equals(allowed);
    }

    void linkIdentity(Long tenantId, Long userId, UUID personPublicId) {
        if (userId == null || personPublicId == null) return;
        jdbc.update(
                CalendarSql01.LINK_IDENTITY_DELETE_CAL_IDENTITY_LINKS,
                tenantId,
                personPublicId,
                userId);
        jdbc.update(
                CalendarSql01.LINK_IDENTITY_INSERT_CAL_IDENTITY_LINKS,
                tenantId,
                userId,
                personPublicId);
    }

    boolean identityMatches(Long tenantId, Long userId, UUID personPublicId) {
        if (tenantId == null || userId == null || personPublicId == null) return false;
        Boolean matches = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM cal_identity_links
                     WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?)
                """, Boolean.class, tenantId, userId, personPublicId);
        return Boolean.TRUE.equals(matches);
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
