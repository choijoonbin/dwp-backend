package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reusable PostgreSQL fixtures and assertions for Calendar P0 governance integration tests. */
abstract class CalendarP0GovernancePostgresTestSupport {

    static final long TENANT_A = 9_120_001L;
    static final long TENANT_B = 9_120_002L;
    static final long OWNER_USER_ID = 9_121_001L;
    static final long VIEWER_USER_ID = 9_121_002L;
    static final long ATTENDEE_USER_ID = 9_121_003L;
    static final long OUTSIDER_USER_ID = 9_121_004L;

    static final UUID OWNER_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000001");
    static final UUID VIEWER_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000002");
    static final UUID ATTENDEE_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000003");
    static final UUID OUTSIDER_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000004");

    static final OffsetDateTime QUERY_FROM =
            OffsetDateTime.parse("2026-08-31T00:00:00Z");
    static final OffsetDateTime QUERY_TO =
            OffsetDateTime.parse("2026-09-02T00:00:00Z");


    static PGSimpleDataSource dataSource;
    static JdbcTemplate jdbc;
    static int resourceTenantConstraintCountBeforeV192;

    static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    static int constraintCount(String table, String constraint) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_constraint constraint_row
                  JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                  JOIN pg_namespace namespace_row ON namespace_row.oid = table_row.relnamespace
                 WHERE namespace_row.nspname = CURRENT_SCHEMA()
                   AND table_row.relname = ? AND constraint_row.conname = ?
                """, Integer.class, table, constraint);
    }

    int eventCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cal_events WHERE tenant_id = ? AND event_id = ?",
                Integer.class,
                TENANT_A,
                eventId);
    }

    int tombstoneCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM cal_event_tombstones
                 WHERE tenant_id = ? AND source_ref = ?
                """, Integer.class, TENANT_A, eventId.toString());
    }

    Set<String> columns(String table) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = CURRENT_SCHEMA() AND table_name = ?
                """, String.class, table));
    }

    void assertCompositeForeignKey(
            String table,
            String constraint,
            String sourceColumns,
            String targetColumns) {
        Map<String, Object> metadata = jdbc.queryForMap("""
                SELECT pg_get_constraintdef(constraint_row.oid) AS definition,
                       constraint_row.convalidated AS validated
                  FROM pg_constraint constraint_row
                  JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                  JOIN pg_namespace namespace_row ON namespace_row.oid = table_row.relnamespace
                 WHERE namespace_row.nspname = CURRENT_SCHEMA()
                   AND table_row.relname = ? AND constraint_row.conname = ?
                """, table, constraint);
        assertThat(metadata.get("definition").toString())
                .contains(sourceColumns)
                .contains(targetColumns);
        assertThat(metadata.get("validated")).isEqualTo(true);
    }

    void assertForeignKeyViolation(String constraint, Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining(constraint);
    }

    Fixture fixture(String visibility) {
        insertIdentity(TENANT_A, OWNER_USER_ID, OWNER_PERSON_ID);
        insertIdentity(TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID);
        insertIdentity(TENANT_A, ATTENDEE_USER_ID, ATTENDEE_PERSON_ID);
        insertIdentity(TENANT_A, OUTSIDER_USER_ID, OUTSIDER_PERSON_ID);
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertCalendar(TENANT_A, calendarId, OWNER_USER_ID, OWNER_PERSON_ID);
        insertEvent(
                TENANT_A, eventId, calendarId, OWNER_USER_ID, OWNER_PERSON_ID,
                "Confidential strategy", visibility);
        return new Fixture(calendarId, eventId);
    }

    void insertIdentity(long tenantId, long userId, UUID personId) {
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, tenantId, userId, personId);
    }

    void insertCalendar(
            long tenantId,
            UUID calendarId,
            long ownerUserId,
            UUID ownerPersonId) {
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Calendar owner', '개인 캘린더', 'Personal calendar',
                        'PERSONAL', 'DETAILS', 'ACTIVE', ?, ?)
                """, calendarId, tenantId, "personal-" + calendarId,
                ownerUserId, ownerPersonId, ownerUserId, ownerUserId);
    }

    void insertSystemCalendar(long tenantId, UUID calendarId) {
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, name_ko, name_en,
                    calendar_type, visibility, subscription_policy,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, '회사 캘린더', 'Company calendar',
                        'SYSTEM', 'DETAILS', 'REQUIRED', 'ACTIVE', ?, ?)
                """, calendarId, tenantId, "company-" + calendarId,
                OWNER_USER_ID, OWNER_USER_ID);
    }

    void insertEvent(
            long tenantId,
            UUID eventId,
            UUID calendarId,
            long organizerUserId,
            UUID organizerPersonId,
            String title,
            String visibility) {
        insertEvent(
                tenantId,
                eventId,
                calendarId,
                organizerUserId,
                organizerPersonId,
                title,
                visibility,
                QUERY_FROM.plusHours(10),
                QUERY_FROM.plusHours(11));
    }

    void insertEvent(
            long tenantId,
            UUID eventId,
            UUID calendarId,
            long organizerUserId,
            UUID organizerPersonId,
            String title,
            String visibility,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, event_type,
                    starts_at, ends_at, time_zone, status, visibility,
                    recurrence_pattern, recurrence_interval, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Calendar owner', ?, 'MEETING',
                        ?, ?, 'UTC', 'CONFIRMED', ?, 'NONE', 1, FALSE,
                        'NATIVE', ?, ?)
                """, eventId, tenantId, calendarId, organizerUserId, organizerPersonId,
                title, startsAt, endsAt, visibility,
                organizerUserId, organizerUserId);
    }

    void insertResource(long tenantId, UUID resourceId, String code) {
        jdbc.update("""
                INSERT INTO cal_resources (
                    resource_id, tenant_id, resource_code, name_ko, name_en,
                    resource_type, site_name, capacity, time_zone,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, '테스트 회의실', 'Test room', 'ROOM',
                        'Test site', 8, 'UTC', 'AVAILABLE', ?, ?)
                """, resourceId, tenantId, code, OWNER_USER_ID, OWNER_USER_ID);
    }

    void insertBooking(long tenantId, UUID eventId, UUID resourceId) {
        insertBooking(
                tenantId,
                eventId,
                resourceId,
                QUERY_FROM.plusHours(10),
                QUERY_FROM.plusHours(11));
    }

    void insertBooking(
            long tenantId,
            UUID eventId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    tenant_id, event_id, resource_id, starts_at, ends_at,
                    booking_status, requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)
                """, tenantId, eventId, resourceId, startsAt, endsAt,
                OWNER_USER_ID, OWNER_USER_ID, OWNER_USER_ID);
    }

    void insertGrant(
            UUID calendarId,
            UUID personId,
            String accessLevel,
            boolean canViewPrivate,
            OffsetDateTime validUntil) {
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type,
                    principal_person_public_id, principal_display_name,
                    access_level, can_view_private, valid_until,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, 'PERSON', ?, 'Explicit viewer', ?, ?, ?, 'ACTIVE', ?, ?)
                """, TENANT_A, calendarId, personId, accessLevel,
                canViewPrivate, validUntil, OWNER_USER_ID, OWNER_USER_ID);
    }

    void insertTenantGrant(UUID calendarId, String accessLevel) {
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type,
                    access_level, can_view_private, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, 'TENANT', ?, FALSE, 'ACTIVE', ?, ?)
                """, TENANT_A, calendarId, accessLevel, OWNER_USER_ID, OWNER_USER_ID);
    }

    void insertGroupGrant(UUID calendarId, UUID groupRef, String accessLevel) {
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type, principal_group_ref,
                    principal_display_name, access_level, can_view_private,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, 'GROUP', ?, 'Calendar editors', ?, FALSE, 'ACTIVE', ?, ?)
                """, TENANT_A, calendarId, groupRef, accessLevel,
                OWNER_USER_ID, OWNER_USER_ID);
    }

    CalendarDtos.UpdateEventRequest roomUpdate(UUID resourceId) {
        return new CalendarDtos.UpdateEventRequest(
                "Group room review",
                "Decision agenda",
                CalendarTypes.EventType.MEETING,
                QUERY_FROM.plusHours(10),
                QUERY_FROM.plusHours(11),
                "UTC",
                false,
                "Test room",
                null,
                CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE,
                1,
                (LocalDate) null,
                false,
                List.of(),
                resourceId,
                0L);
    }

    List<UUID> visibleCalendarIds(long viewerUserId, UUID viewerPersonId) {
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(CalendarAccessSql.CALENDARS);
            statement.setLong(1, viewerUserId);
            statement.setObject(2, viewerPersonId);
            statement.setArray(3, connection.createArrayOf("uuid", new UUID[0]));
            statement.setBoolean(4, false);
            statement.setLong(5, TENANT_A);
            return statement;
        }, (result, ignored) -> result.getObject("calendar_id", UUID.class));
    }

    List<String> accessDecision(
            UUID calendarId, long viewerUserId, UUID viewerPersonId) {
        return jdbc.query(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(CalendarAccessSql.ACCESS_DECISION);
            statement.setLong(1, viewerUserId);
            statement.setObject(2, viewerPersonId);
            statement.setArray(3, connection.createArrayOf("uuid", new UUID[0]));
            statement.setLong(4, TENANT_A);
            statement.setObject(5, calendarId);
            return statement;
        }, (result, ignored) -> result.getString("access_level"));
    }

    List<EventAccessRow> visibleEvents(long viewerUserId, UUID viewerPersonId) {
        return jdbc.query(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(CalendarAccessSql.VISIBLE_EVENTS);
            statement.setLong(1, viewerUserId);
            statement.setObject(2, viewerPersonId);
            statement.setArray(3, connection.createArrayOf("uuid", new UUID[0]));
            statement.setBoolean(4, false);
            statement.setBoolean(5, false);
            statement.setLong(6, TENANT_A);
            statement.setObject(7, QUERY_TO);
            statement.setObject(8, QUERY_FROM);
            statement.setObject(9, QUERY_TO);
            statement.setObject(10, QUERY_FROM);
            statement.setObject(11, QUERY_FROM);
            return statement;
        }, (result, ignored) -> new EventAccessRow(
                result.getObject("event_id", UUID.class),
                result.getString("access_level"),
                result.getString("detail_level")));
    }

    record Fixture(UUID calendarId, UUID eventId) {
    }

    record EventAccessRow(UUID eventId, String accessLevel, String detailLevel) {
    }
}
