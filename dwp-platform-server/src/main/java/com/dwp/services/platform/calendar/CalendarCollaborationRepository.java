package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CalendarCollaborationRepository {

    private static final String LOCK_CALENDAR = """
            SELECT calendar_id
              FROM cal_calendars
             WHERE tenant_id = ? AND calendar_id = ?
             FOR UPDATE
            """;

    private static final String VERIFIED_ACTOR = """
            SELECT 1
              FROM cal_identity_links
             WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?
            """;

    private static final String EVENT_CALENDAR = """
            SELECT calendar_id
              FROM cal_events
             WHERE tenant_id = ? AND event_id = ?
            """;

    private static final String EVENT_DECISION = """
            WITH viewer AS (
                SELECT ?::bigint AS user_id, ?::uuid AS person_id,
                       ?::uuid[] AS group_refs
            )
            SELECT event.event_id, event.calendar_id, event.status, event.visibility,
                   event.response_required,
                   event.deleted_at, event.purge_after, event.legal_hold,
                   event.version,
                   (calendar.calendar_type <> 'SYSTEM' AND (
                       event.organizer_person_public_id = viewer.person_id
                       OR (event.organizer_person_public_id IS NULL
                           AND event.organizer_user_id = viewer.user_id))) AS organizer,
                   CASE
                       WHEN (calendar.calendar_type <> 'SYSTEM' AND (
                               event.organizer_person_public_id = viewer.person_id
                               OR (event.organizer_person_public_id IS NULL
                                   AND event.organizer_user_id = viewer.user_id)))
                           OR calendar.owner_person_public_id = viewer.person_id
                           OR (calendar.owner_person_public_id IS NULL
                               AND calendar.owner_user_id = viewer.user_id)
                           THEN 'OWNER'
                       WHEN access.access_level IN ('MANAGE', 'EDIT')
                           THEN access.access_level
                       WHEN attendee.event_id IS NOT NULL THEN 'EVENT_ATTENDEE'
                       ELSE access.access_level
                   END AS access_level,
                   COALESCE(access.can_view_private, FALSE) AS can_view_private
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
              LEFT JOIN LATERAL (
                  SELECT attendee_row.event_id
                    FROM cal_event_attendees attendee_row
                   WHERE attendee_row.tenant_id = event.tenant_id
                     AND attendee_row.event_id = event.event_id
                     AND (
                         attendee_row.attendee_person_public_id = viewer.person_id
                         OR (attendee_row.attendee_person_public_id IS NULL
                             AND attendee_row.attendee_user_id = viewer.user_id)
                     )
                   LIMIT 1
              ) attendee ON TRUE
             WHERE event.tenant_id = ? AND event.event_id = ?
               AND calendar.lifecycle_state = 'ACTIVE'
               AND (
                   (calendar.calendar_type <> 'SYSTEM' AND (
                       event.organizer_person_public_id = viewer.person_id
                       OR (event.organizer_person_public_id IS NULL
                           AND event.organizer_user_id = viewer.user_id)))
                   OR calendar.owner_person_public_id = viewer.person_id
                   OR (calendar.owner_person_public_id IS NULL
                       AND calendar.owner_user_id = viewer.user_id)
                   OR attendee.event_id IS NOT NULL
                   OR access.access_level IS NOT NULL
               )
            """;

    private static final String EVENT_DECISION_FOR_UPDATE =
            EVENT_DECISION + " FOR UPDATE OF event";

    private static final String SUBSCRIPTION_FOR_UPDATE = """
            SELECT selected, favorite, display_order, color_override, version
              FROM cal_calendar_subscriptions
             WHERE tenant_id = ? AND person_public_id = ? AND calendar_id = ?
             FOR UPDATE
            """;

    private static final String EVENT_PREFERENCE_FOR_UPDATE = """
            SELECT starred, hidden, version
              FROM cal_event_user_preferences
             WHERE tenant_id = ? AND person_public_id = ? AND event_id = ?
             FOR UPDATE
            """;

    private static final String TRASH_EVENT = """
            UPDATE cal_events
               SET deleted_at = CURRENT_TIMESTAMP,
                   deleted_by = ?, deletion_reason = ?,
                   purge_after = CASE WHEN legal_hold THEN NULL
                       ELSE CURRENT_TIMESTAMP + INTERVAL '30 days' END,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP, updated_by = ?
             WHERE tenant_id = ? AND event_id = ?
               AND deleted_at IS NULL AND status <> 'CANCELLED' AND version = ?
            RETURNING deleted_at, purge_after, legal_hold, version
            """;

    private static final String RESTORE_EVENT = """
            UPDATE cal_events
               SET deleted_at = NULL, deleted_by = NULL, deletion_reason = NULL,
                   purge_after = NULL, version = version + 1,
                   updated_at = CURRENT_TIMESTAMP, updated_by = ?
             WHERE tenant_id = ? AND event_id = ?
               AND deleted_at IS NOT NULL
               AND (legal_hold OR purge_after > CURRENT_TIMESTAMP)
               AND version = ?
            RETURNING deleted_at, purge_after, legal_hold, version
            """;

    private final JdbcTemplate jdbc;

    public CalendarCollaborationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean verifiedActor(Long tenantId, Long userId, UUID personPublicId) {
        return !jdbc.query(
                VERIFIED_ACTOR,
                (result, ignored) -> result.getInt(1),
                tenantId,
                userId,
                personPublicId).isEmpty();
    }

    boolean lockCalendar(Long tenantId, UUID calendarId) {
        return !jdbc.query(
                LOCK_CALENDAR,
                (result, ignored) -> result.getObject("calendar_id", UUID.class),
                tenantId,
                calendarId).isEmpty();
    }

    Optional<AccessDecision> accessDecision(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID[] groupRefs,
            UUID calendarId) {
        return jdbc.query(
                        CalendarAccessSql.ACCESS_DECISION,
                        (result, ignored) -> accessDecision(result),
                        userId,
                        personPublicId,
                        groupRefs,
                        tenantId,
                        calendarId)
                .stream()
                .findFirst();
    }

    List<ShareRow> shares(Long tenantId, UUID calendarId) {
        return jdbc.query(
                CalendarAccessSql.SHARES,
                (result, ignored) -> share(result),
                tenantId,
                calendarId);
    }

    Optional<ShareRow> upsertPersonShare(
            Long tenantId,
            Long actorId,
            UUID calendarId,
            UUID personPublicId,
            String displayName,
            String accessLevel,
            boolean canViewPrivate,
            OffsetDateTime validUntil,
            long version) {
        return jdbc.query(
                        CalendarAccessSql.UPSERT_PERSON_SHARE,
                        (result, ignored) -> share(result),
                        tenantId,
                        calendarId,
                        personPublicId,
                        displayName,
                        accessLevel,
                        canViewPrivate,
                        validUntil,
                        actorId,
                        actorId,
                        version)
                .stream()
                .findFirst();
    }

    int revokeShare(
            Long tenantId,
            Long actorId,
            UUID calendarId,
            UUID grantId,
            long version) {
        return jdbc.update(
                CalendarAccessSql.REVOKE_SHARE,
                actorId,
                tenantId,
                calendarId,
                grantId,
                version);
    }

    Optional<SubscriptionRow> subscriptionForUpdate(
            Long tenantId, UUID personPublicId, UUID calendarId) {
        return jdbc.query(
                        SUBSCRIPTION_FOR_UPDATE,
                        (result, ignored) -> subscription(result),
                        tenantId,
                        personPublicId,
                        calendarId)
                .stream()
                .findFirst();
    }

    Optional<SubscriptionRow> upsertSubscription(
            Long tenantId,
            UUID personPublicId,
            UUID calendarId,
            boolean selected,
            boolean favorite,
            int displayOrder,
            String colorOverride,
            long version) {
        return jdbc.query(
                        CalendarAccessSql.UPSERT_SUBSCRIPTION,
                        (result, ignored) -> new SubscriptionRow(
                                result.getBoolean("selected"),
                                result.getBoolean("favorite"),
                                result.getInt("display_order"),
                                result.getString("color_override"),
                                result.getLong("version")),
                        tenantId,
                        personPublicId,
                        calendarId,
                        selected,
                        favorite,
                        displayOrder,
                        colorOverride,
                        version)
                .stream()
                .findFirst();
    }

    Optional<UUID> eventCalendarId(Long tenantId, UUID eventId) {
        return jdbc.query(
                        EVENT_CALENDAR,
                        (result, ignored) -> result.getObject("calendar_id", UUID.class),
                        tenantId,
                        eventId)
                .stream()
                .findFirst();
    }

    Optional<EventDecision> eventDecisionForUpdate(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID[] groupRefs,
            UUID eventId) {
        return eventDecision(
                EVENT_DECISION_FOR_UPDATE,
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                eventId);
    }

    Optional<EventDecision> eventDecision(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID[] groupRefs,
            UUID eventId) {
        return eventDecision(
                EVENT_DECISION,
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                eventId);
    }

    private Optional<EventDecision> eventDecision(
            String sql,
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID[] groupRefs,
            UUID eventId) {
        return jdbc.query(
                        sql,
                        (result, ignored) -> new EventDecision(
                                result.getObject("event_id", UUID.class),
                                result.getObject("calendar_id", UUID.class),
                                result.getString("status"),
                                result.getString("visibility"),
                                result.getBoolean("response_required"),
                                result.getObject("deleted_at", OffsetDateTime.class),
                                result.getObject("purge_after", OffsetDateTime.class),
                                result.getBoolean("legal_hold"),
                                result.getLong("version"),
                                result.getBoolean("organizer"),
                                result.getString("access_level"),
                                result.getBoolean("can_view_private")),
                        userId,
                        personPublicId,
                        groupRefs,
                        tenantId,
                        eventId)
                .stream()
                .findFirst();
    }

    Optional<EventPreferenceRow> eventPreferenceForUpdate(
            Long tenantId, UUID personPublicId, UUID eventId) {
        return jdbc.query(
                        EVENT_PREFERENCE_FOR_UPDATE,
                        (result, ignored) -> eventPreference(result),
                        tenantId,
                        personPublicId,
                        eventId)
                .stream()
                .findFirst();
    }

    Optional<EventPreferenceRow> upsertEventPreference(
            Long tenantId,
            UUID personPublicId,
            UUID eventId,
            boolean starred,
            boolean hidden,
            long version) {
        return jdbc.query(
                        CalendarAccessSql.UPSERT_EVENT_PREFERENCE,
                        (result, ignored) -> new EventPreferenceRow(
                                result.getBoolean("starred"),
                                result.getBoolean("hidden"),
                                result.getLong("version")),
                        tenantId,
                        personPublicId,
                        eventId,
                        starred,
                        hidden,
                        version)
                .stream()
                .findFirst();
    }

    Optional<EventMutation> trashEvent(
            Long tenantId,
            Long actorId,
            UUID eventId,
            String reason,
            long version) {
        return jdbc.query(
                        TRASH_EVENT,
                        (result, ignored) -> eventMutation(result),
                        actorId,
                        reason,
                        actorId,
                        tenantId,
                        eventId,
                        version)
                .stream()
                .findFirst();
    }

    Optional<EventMutation> restoreEvent(
            Long tenantId, Long actorId, UUID eventId, long version) {
        return jdbc.query(
                        RESTORE_EVENT,
                        (result, ignored) -> eventMutation(result),
                        actorId,
                        tenantId,
                        eventId,
                        version)
                .stream()
                .findFirst();
    }

    private AccessDecision accessDecision(ResultSet result) throws SQLException {
        return new AccessDecision(
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_type"),
                result.getString("subscription_policy"),
                nullableLong(result, "owner_user_id"),
                result.getObject("owner_person_public_id", UUID.class),
                result.getString("access_level"),
                result.getBoolean("can_view_private"),
                result.getLong("version"));
    }

    private ShareRow share(ResultSet result) throws SQLException {
        return new ShareRow(
                result.getObject("grant_id", UUID.class),
                result.getString("principal_type"),
                result.getObject("principal_person_public_id", UUID.class),
                result.getObject("principal_group_ref", UUID.class),
                result.getString("principal_display_name"),
                result.getString("access_level"),
                result.getBoolean("can_view_private"),
                result.getObject("valid_until", OffsetDateTime.class),
                result.getString("lifecycle_state"),
                result.getLong("version"));
    }

    private EventMutation eventMutation(ResultSet result) throws SQLException {
        return new EventMutation(
                result.getObject("deleted_at", OffsetDateTime.class),
                result.getObject("purge_after", OffsetDateTime.class),
                result.getBoolean("legal_hold"),
                result.getLong("version"));
    }

    private SubscriptionRow subscription(ResultSet result) throws SQLException {
        return new SubscriptionRow(
                result.getBoolean("selected"),
                result.getBoolean("favorite"),
                result.getInt("display_order"),
                result.getString("color_override"),
                result.getLong("version"));
    }

    private EventPreferenceRow eventPreference(ResultSet result) throws SQLException {
        return new EventPreferenceRow(
                result.getBoolean("starred"),
                result.getBoolean("hidden"),
                result.getLong("version"));
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    record AccessDecision(
            UUID calendarId,
            String calendarType,
            String subscriptionPolicy,
            Long ownerUserId,
            UUID ownerPersonPublicId,
            String accessLevel,
            boolean canViewPrivate,
            long version) {

        boolean accessible() {
            return accessLevel != null;
        }

        boolean canManage() {
            return "OWNER".equals(accessLevel) || "MANAGE".equals(accessLevel);
        }

        boolean canEdit() {
            return canManage() || "EDIT".equals(accessLevel);
        }

        boolean canViewDetails() {
            return canEdit() || "VIEW_DETAILS".equals(accessLevel);
        }

        boolean canViewFreeBusy() {
            return accessible();
        }
    }

    record ShareRow(
            UUID grantId,
            String principalType,
            UUID principalPersonPublicId,
            UUID principalGroupRef,
            String principalDisplayName,
            String accessLevel,
            boolean canViewPrivate,
            OffsetDateTime validUntil,
            String lifecycleState,
            long version) {
    }

    record SubscriptionRow(
            boolean selected,
            boolean favorite,
            int displayOrder,
            String colorOverride,
            long version) {
    }

    record EventPreferenceRow(boolean starred, boolean hidden, long version) {
    }

    record EventDecision(
            UUID eventId,
            UUID calendarId,
            String status,
            String visibility,
            boolean responseRequired,
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            boolean legalHold,
            long version,
            boolean organizer,
            String accessLevel,
            boolean canViewPrivate) {

        boolean canManage() {
            return organizer || "OWNER".equals(accessLevel) || "MANAGE".equals(accessLevel);
        }

        boolean canEdit() {
            return canManage() || "EDIT".equals(accessLevel);
        }

        boolean canViewDetails() {
            boolean detailed = canEdit() || "VIEW_DETAILS".equals(accessLevel)
                    || "EVENT_ATTENDEE".equals(accessLevel);
            boolean restricted = "PRIVATE".equals(visibility)
                    || "CONFIDENTIAL".equals(visibility);
            return detailed && (!restricted || organizer || "OWNER".equals(accessLevel)
                    || "EVENT_ATTENDEE".equals(accessLevel) || canViewPrivate);
        }

        boolean canViewFreeBusy() {
            return accessLevel != null;
        }
    }

    record EventMutation(
            OffsetDateTime deletedAt,
            OffsetDateTime purgeAfter,
            boolean legalHold,
            long version) {
    }
}
