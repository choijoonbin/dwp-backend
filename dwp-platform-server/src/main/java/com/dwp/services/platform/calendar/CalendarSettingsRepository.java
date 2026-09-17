package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CalendarSettingsRepository {

    private static final String SETTINGS_COLUMNS = """
            working_days_mask, working_day_start, working_day_end, time_zone,
            week_start, default_event_minutes, speedy_meeting_mode,
            default_buffer_minutes, default_visibility, default_reminder_minutes,
            settings_origin, version, updated_at
            """;

    private static final String DELEGATION_COLUMNS = """
            delegation_id, owner_person_public_id, delegate_person_public_id,
            can_respond, can_edit_schedule, can_create, valid_from, valid_until,
            status, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public CalendarSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean matchesIdentity(CalendarSettingsAccess.Actor actor) {
        return !jdbc.query("""
                SELECT 1
                  FROM cal_identity_links
                 WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?
                """, (result, ignored) -> result.getInt(1),
                actor.tenantId(), actor.userId(), actor.personPublicId()).isEmpty();
    }

    Optional<SettingsRow> settings(CalendarSettingsAccess.Actor actor) {
        return jdbc.query("SELECT " + SETTINGS_COLUMNS + " FROM cal_user_settings"
                        + " WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?",
                        (result, ignored) -> settingsRow(result),
                        actor.tenantId(), actor.userId(), actor.personPublicId())
                .stream().findFirst();
    }

    Optional<SettingsRow> saveSettings(
            CalendarSettingsAccess.Actor actor,
            SettingsWrite value,
            long expectedVersion) {
        return jdbc.query("""
                INSERT INTO cal_user_settings (
                    tenant_id, user_id, person_public_id, working_days_mask,
                    working_day_start, working_day_end, time_zone, week_start,
                    default_event_minutes, speedy_meeting_mode, default_buffer_minutes,
                    default_visibility, default_reminder_minutes, settings_origin, version,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO UPDATE
                   SET working_days_mask = EXCLUDED.working_days_mask,
                       working_day_start = EXCLUDED.working_day_start,
                       working_day_end = EXCLUDED.working_day_end,
                       time_zone = EXCLUDED.time_zone,
                       week_start = EXCLUDED.week_start,
                       default_event_minutes = EXCLUDED.default_event_minutes,
                       speedy_meeting_mode = EXCLUDED.speedy_meeting_mode,
                       default_buffer_minutes = EXCLUDED.default_buffer_minutes,
                       default_visibility = EXCLUDED.default_visibility,
                       default_reminder_minutes = EXCLUDED.default_reminder_minutes,
                       settings_origin = EXCLUDED.settings_origin,
                       version = cal_user_settings.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = EXCLUDED.updated_by
                 WHERE cal_user_settings.person_public_id = EXCLUDED.person_public_id
                   AND cal_user_settings.version = ?
                RETURNING
                """ + SETTINGS_COLUMNS,
                        (result, ignored) -> settingsRow(result),
                        actor.tenantId(), actor.userId(), actor.personPublicId(),
                        value.workingDaysMask(), value.workingDayStart(), value.workingDayEnd(),
                        value.timeZone(), value.weekStart(), value.defaultEventMinutes(),
                        value.speedyMeetingMode(), value.defaultBufferMinutes(),
                        value.defaultVisibility(), value.defaultReminderMinutes(),
                        value.settingsOrigin(), actor.userId(), actor.userId(), expectedVersion)
                .stream().findFirst();
    }

    void lockSettings(
            CalendarSettingsAccess.Actor actor,
            SettingsWrite baseline) {
        jdbc.update("""
                INSERT INTO cal_user_settings (
                    tenant_id, user_id, person_public_id, working_days_mask,
                    working_day_start, working_day_end, time_zone, week_start,
                    default_event_minutes, speedy_meeting_mode, default_buffer_minutes,
                    default_visibility, default_reminder_minutes, settings_origin,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, actor.tenantId(), actor.userId(), actor.personPublicId(),
                baseline.workingDaysMask(), baseline.workingDayStart(), baseline.workingDayEnd(),
                baseline.timeZone(), baseline.weekStart(), baseline.defaultEventMinutes(),
                baseline.speedyMeetingMode(), baseline.defaultBufferMinutes(),
                baseline.defaultVisibility(), baseline.defaultReminderMinutes(),
                baseline.settingsOrigin(), actor.userId(), actor.userId());
        jdbc.query("""
                SELECT user_id
                  FROM cal_user_settings
                 WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?
                 FOR UPDATE
                """, (result, ignored) -> result.getLong(1),
                actor.tenantId(), actor.userId(), actor.personPublicId());
    }

    Optional<PolicyRow> policy(long tenantId) {
        return jdbc.query("""
                SELECT week_start, working_day_start, working_day_end,
                       default_event_minutes, default_buffer_minutes
                  FROM cal_tenant_policies
                 WHERE tenant_id = ?
                """, (result, ignored) -> new PolicyRow(
                        result.getInt("week_start"),
                        result.getObject("working_day_start", LocalTime.class),
                        result.getObject("working_day_end", LocalTime.class),
                        result.getInt("default_event_minutes"),
                        result.getInt("default_buffer_minutes")), tenantId)
                .stream().findFirst();
    }

    boolean personExists(long tenantId, UUID personPublicId) {
        return !jdbc.query("""
                SELECT 1 FROM cal_identity_links
                 WHERE tenant_id = ? AND person_public_id = ?
                """, (result, ignored) -> result.getInt(1), tenantId, personPublicId).isEmpty();
    }

    List<DelegationRow> delegations(CalendarSettingsAccess.Actor actor) {
        return jdbc.query("SELECT " + DELEGATION_COLUMNS
                        + " FROM cal_calendar_delegations"
                        + " WHERE tenant_id = ? AND owner_user_id = ? AND owner_person_public_id = ?"
                        + " ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,"
                        + " valid_from, created_at, delegation_id",
                (result, ignored) -> delegationRow(result),
                actor.tenantId(), actor.userId(), actor.personPublicId());
    }

    boolean hasOverlappingDelegation(
            CalendarSettingsAccess.Actor actor,
            UUID delegatePersonPublicId,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            OffsetDateTime now) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM cal_calendar_delegations
                 WHERE tenant_id = ? AND owner_user_id = ? AND owner_person_public_id = ?
                   AND delegate_person_public_id = ? AND status = 'ACTIVE'
                   AND valid_until > ?
                   AND tstzrange(valid_from, valid_until, '[)')
                       && tstzrange(?, ?, '[)')
                """, Integer.class, actor.tenantId(), actor.userId(), actor.personPublicId(),
                delegatePersonPublicId, now, validFrom, validUntil);
        return count != null && count > 0;
    }

    DelegationRow createDelegation(
            CalendarSettingsAccess.Actor actor,
            UUID delegationId,
            UUID delegatePersonPublicId,
            boolean canRespond,
            boolean canEditSchedule,
            boolean canCreate,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil) {
        return jdbc.queryForObject("""
                INSERT INTO cal_calendar_delegations (
                    delegation_id, tenant_id, owner_user_id, owner_person_public_id,
                    delegate_person_public_id, can_respond, can_edit_schedule, can_create,
                    valid_from, valid_until, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING
                """ + DELEGATION_COLUMNS,
                (result, ignored) -> delegationRow(result),
                delegationId, actor.tenantId(), actor.userId(), actor.personPublicId(),
                delegatePersonPublicId, canRespond, canEditSchedule, canCreate,
                validFrom, validUntil, actor.userId(), actor.userId());
    }

    Optional<DelegationRow> delegation(
            CalendarSettingsAccess.Actor actor,
            UUID delegationId) {
        return jdbc.query("SELECT " + DELEGATION_COLUMNS
                        + " FROM cal_calendar_delegations"
                        + " WHERE tenant_id = ? AND owner_user_id = ?"
                        + " AND owner_person_public_id = ? AND delegation_id = ?",
                        (result, ignored) -> delegationRow(result),
                        actor.tenantId(), actor.userId(), actor.personPublicId(), delegationId)
                .stream().findFirst();
    }

    boolean revokeDelegation(
            CalendarSettingsAccess.Actor actor,
            UUID delegationId,
            long version,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_calendar_delegations
                   SET status = 'REVOKED', revoked_at = ?, revoked_by = ?,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND owner_person_public_id = ?
                   AND delegation_id = ? AND status = 'ACTIVE'
                   AND valid_until > ? AND version = ?
                """, now, actor.userId(), now, actor.userId(), actor.tenantId(),
                actor.userId(), actor.personPublicId(), delegationId, now, version) == 1;
    }

    private static SettingsRow settingsRow(ResultSet result) throws SQLException {
        return new SettingsRow(
                result.getInt("working_days_mask"),
                result.getObject("working_day_start", LocalTime.class),
                result.getObject("working_day_end", LocalTime.class),
                result.getString("time_zone"),
                result.getInt("week_start"),
                result.getInt("default_event_minutes"),
                result.getString("speedy_meeting_mode"),
                result.getInt("default_buffer_minutes"),
                result.getString("default_visibility"),
                result.getInt("default_reminder_minutes"),
                result.getString("settings_origin"),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private static DelegationRow delegationRow(ResultSet result) throws SQLException {
        return new DelegationRow(
                result.getObject("delegation_id", UUID.class),
                result.getObject("owner_person_public_id", UUID.class),
                result.getObject("delegate_person_public_id", UUID.class),
                result.getBoolean("can_respond"),
                result.getBoolean("can_edit_schedule"),
                result.getBoolean("can_create"),
                result.getObject("valid_from", OffsetDateTime.class),
                result.getObject("valid_until", OffsetDateTime.class),
                result.getString("status"),
                result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    record SettingsWrite(
            int workingDaysMask,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            String timeZone,
            int weekStart,
            int defaultEventMinutes,
            String speedyMeetingMode,
            int defaultBufferMinutes,
            String defaultVisibility,
            int defaultReminderMinutes,
            String settingsOrigin) {
    }

    record SettingsRow(
            int workingDaysMask,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            String timeZone,
            int weekStart,
            int defaultEventMinutes,
            String speedyMeetingMode,
            int defaultBufferMinutes,
            String defaultVisibility,
            int defaultReminderMinutes,
            String settingsOrigin,
            long version,
            OffsetDateTime updatedAt) {
    }

    record PolicyRow(
            int weekStart,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            int defaultEventMinutes,
            int defaultBufferMinutes) {
    }

    record DelegationRow(
            UUID delegationId,
            UUID ownerPersonPublicId,
            UUID delegatePersonPublicId,
            boolean canRespond,
            boolean canEditSchedule,
            boolean canCreate,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            String status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
