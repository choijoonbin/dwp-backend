package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus.ACCEPTED;
import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus.TENTATIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class CalendarInvitationResponsePostgresIntegrationTest {

    private static final long TENANT_ID = 9_295_001L;
    private static final long ACTOR_ID = 9_295_101L;
    private static final long ORGANIZER_ID = 9_295_102L;
    private static final UUID ACTOR_PERSON_ID =
            UUID.fromString("92950000-0000-4000-8000-000000000001");
    private static final UUID ORGANIZER_PERSON_ID =
            UUID.fromString("92950000-0000-4000-8000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private CalendarService service;

    @BeforeAll
    static void migrateLatest() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        for (String table : new String[] {
                "cal_invitation_response_commands",
                "cal_audit_events",
                "cal_event_attendees",
                "cal_events",
                "cal_calendars",
                "cal_identity_links"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT_ID);
        }
        CalendarRepository repository = new CalendarRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        CalendarSchedulingHorizon horizon = new CalendarSchedulingHorizon(Clock.fixed(
                Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
        service = new CalendarService(
                repository,
                mock(WorkplaceRoomAccessPort.class),
                horizon,
                new RoomBookingPolicyService(repository, horizon));
    }

    @Test
    void happyPathAndExactRetryReplayOneMutationAndOneAudit() {
        Fixture fixture = fixture("happy");
        UUID key = UUID.randomUUID();
        CalendarDtos.RespondRequest request =
                new CalendarDtos.RespondRequest(ACCEPTED, 0L, key);

        CalendarDtos.EventSummary first = tx(() -> respond(fixture.eventId(), request));
        CalendarDtos.EventSummary replay = tx(() -> respond(fixture.eventId(), request));

        assertThat(first.version()).isEqualTo(1L);
        assertThat(first.myResponse()).isEqualTo(ACCEPTED);
        assertThat(replay).isEqualTo(first);
        assertThat(eventVersion(fixture.eventId())).isEqualTo(1L);
        assertThat(attendeeState(fixture.eventId()))
                .containsEntry("response_status", "ACCEPTED")
                .containsEntry("response_version", 1L);
        assertThat(commandCount(key)).isOne();
        assertThat(auditCount(fixture.eventId())).isOne();
    }

    @Test
    void reusedKeyFingerprintMismatchAndStaleVersionFailClosed() {
        Fixture fixture = fixture("mismatch");
        UUID key = UUID.randomUUID();
        tx(() -> respond(
                fixture.eventId(), new CalendarDtos.RespondRequest(ACCEPTED, 0L, key)));

        assertConflict(() -> tx(() -> respond(
                fixture.eventId(), new CalendarDtos.RespondRequest(TENTATIVE, 0L, key))));

        Fixture stale = fixture("stale");
        UUID staleKey = UUID.randomUUID();
        assertConflict(() -> tx(() -> respond(
                stale.eventId(), new CalendarDtos.RespondRequest(ACCEPTED, 9L, staleKey))));

        assertThat(eventVersion(fixture.eventId())).isEqualTo(1L);
        assertThat(commandCount(key)).isOne();
        assertThat(eventVersion(stale.eventId())).isZero();
        assertThat(commandCount(staleKey)).isZero();
    }

    @Test
    void responseAndEventDriftInvalidateAFormerlyExactReplay() {
        Fixture responseDrift = fixture("response-drift");
        UUID responseKey = UUID.randomUUID();
        CalendarDtos.RespondRequest responseRequest =
                new CalendarDtos.RespondRequest(ACCEPTED, 0L, responseKey);
        tx(() -> respond(responseDrift.eventId(), responseRequest));
        jdbc.update("""
                UPDATE cal_event_attendees
                   SET response_status = 'TENTATIVE',
                       response_version = response_version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_ID, responseDrift.eventId());

        assertConflict(() -> tx(() -> respond(responseDrift.eventId(), responseRequest)));

        Fixture eventDrift = fixture("event-drift");
        UUID eventKey = UUID.randomUUID();
        CalendarDtos.RespondRequest eventRequest =
                new CalendarDtos.RespondRequest(ACCEPTED, 0L, eventKey);
        tx(() -> respond(eventDrift.eventId(), eventRequest));
        jdbc.update("""
                UPDATE cal_events
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_ID, eventDrift.eventId());

        assertConflict(() -> tx(() -> respond(eventDrift.eventId(), eventRequest)));
        assertThat(commandCount(responseKey)).isOne();
        assertThat(commandCount(eventKey)).isOne();
    }

    @Test
    void unauthorizedPersonAndCrossTenantEventNeverReachMutationOrReceipt() {
        Fixture fixture = fixture("authority");
        UUID unknownPerson = UUID.randomUUID();
        CalendarDtos.RespondRequest request =
                new CalendarDtos.RespondRequest(ACCEPTED, 0L, UUID.randomUUID());

        assertNotFound(() -> tx(() -> service.respond(
                TENANT_ID, ACTOR_ID, unknownPerson, fixture.eventId(),
                "en-US", "corr-unknown", null, request)));
        assertNotFound(() -> tx(() -> service.respond(
                TENANT_ID + 1, ACTOR_ID, ACTOR_PERSON_ID, fixture.eventId(),
                "en-US", "corr-cross-tenant", null, request)));

        assertThat(eventVersion(fixture.eventId())).isZero();
        assertThat(commandCount(request.idempotencyKey())).isZero();
        assertThat(auditCount(fixture.eventId())).isZero();
    }

    private CalendarDtos.EventSummary respond(
            UUID eventId,
            CalendarDtos.RespondRequest request) {
        return service.respond(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, eventId,
                "en-US", "corr-response", null, request);
    }

    private Fixture fixture(String suffix) {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-01T09:00:00Z");
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?), (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID,
                TENANT_ID, ORGANIZER_ID, ORGANIZER_PERSON_ID);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Organizer', '초대 캘린더', 'Invitation calendar',
                        'TEAM', 'DETAILS', 'ACTIVE', ?, ?)
                """, calendarId, TENANT_ID, "invitation-" + suffix,
                ORGANIZER_ID, ORGANIZER_PERSON_ID, ORGANIZER_ID, ORGANIZER_ID);
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, description, event_type, starts_at, ends_at, time_zone,
                    status, visibility, recurrence_pattern, recurrence_interval,
                    response_required, source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Organizer', 'organizer@example.com',
                        'Invitation contract', 'Agenda', 'MEETING', ?, ?, 'UTC',
                        'CONFIRMED', 'DEFAULT', 'NONE', 1, TRUE, 'NATIVE', ?, ?)
                """, eventId, TENANT_ID, calendarId, ORGANIZER_ID, ORGANIZER_PERSON_ID,
                startsAt, startsAt.plusHours(1), ORGANIZER_ID, ORGANIZER_ID);
        jdbc.update("""
                INSERT INTO cal_event_attendees (
                    tenant_id, event_id, attendee_user_id,
                    attendee_person_public_id, attendee_email, attendee_name,
                    attendee_type, response_status)
                VALUES (?, ?, ?, ?, 'actor@example.com', 'Actor',
                        'REQUIRED', 'NEEDS_ACTION')
                """, TENANT_ID, eventId, ACTOR_ID, ACTOR_PERSON_ID);
        return new Fixture(eventId);
    }

    private long eventVersion(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT version FROM cal_events WHERE tenant_id = ? AND event_id = ?",
                Long.class, TENANT_ID, eventId);
    }

    private java.util.Map<String, Object> attendeeState(UUID eventId) {
        return jdbc.queryForMap("""
                SELECT response_status, response_version
                  FROM cal_event_attendees
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_ID, eventId);
    }

    private int commandCount(UUID key) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_invitation_response_commands
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, Integer.class, TENANT_ID, ACTOR_ID, key);
    }

    private int auditCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_audit_events
                 WHERE tenant_id = ? AND event_id = ?
                   AND action = 'calendar.attendee.responded'
                """, Integer.class, TENANT_ID, eventId);
    }

    private <T> T tx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private record Fixture(UUID eventId) {
    }
}
