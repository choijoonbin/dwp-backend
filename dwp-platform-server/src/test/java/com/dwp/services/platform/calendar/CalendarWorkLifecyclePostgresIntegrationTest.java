package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.calendar.WorkCalendarDtos;
import com.dwp.services.platform.workhub.calendar.WorkCalendarRepository;
import com.dwp.services.platform.workhub.calendar.WorkCalendarService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos;
import com.dwp.services.platform.workhub.personal.PersonalWorkRepository;
import com.dwp.services.platform.workhub.personal.PersonalWorkService;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class CalendarWorkLifecyclePostgresIntegrationTest {

    private static final long TENANT_ID = 9_180_001L;
    private static final long USER_ID = 9_181_001L;
    private static final UUID PERSON_ID =
            UUID.fromString("91800000-0000-4000-8000-000000000001");
    private static final UUID LINK_ID =
            UUID.fromString("91800000-0000-4000-8000-000000000002");
    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.parse("2026-11-01T01:30:00-04:00");
    private static final OffsetDateTime ENDS_AT =
            OffsetDateTime.parse("2026-11-01T01:30:00-05:00");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private CalendarRepository calendarRepository;
    private CalendarService calendarService;
    private PersonalWorkService personalWorkService;
    private WorkCalendarService workCalendarService;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .target("228")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        for (String table : List.of(
                "personal_work_calendar_links",
                "personal_work_timeline",
                "personal_work_day_plan_items",
                "personal_work_day_plans",
                "personal_work_command_receipts",
                "personal_work_tasks",
                "cal_event_occurrence_overrides",
                "cal_event_user_preferences",
                "cal_calendar_subscriptions",
                "cal_calendar_access_grants",
                "cal_resource_bookings",
                "cal_event_attendees",
                "cal_audit_events",
                "cal_events",
                "cal_calendars",
                "cal_event_tombstones",
                "cal_identity_links")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT_ID);
        }

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        calendarRepository = new CalendarRepository(jdbc, mapper);
        CalendarSchedulingHorizon horizon = new CalendarSchedulingHorizon(Clock.fixed(
                Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));
        WorkplaceRoomAccessPort roomAccess = mock(WorkplaceRoomAccessPort.class);
        when(roomAccess.viewableResourceIds(
                anyLong(), anyLong(), nullable(String.class), anySet())).thenReturn(Set.of());
        calendarService = new CalendarService(
                calendarRepository,
                roomAccess,
                horizon,
                new RoomBookingPolicyService(calendarRepository, horizon));

        PlatformAuditService audit = mock(PlatformAuditService.class);
        when(audit.successWithId(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> UUID.randomUUID());
        PersonalWorkAccess workAccess = new PersonalWorkAccess();
        personalWorkService = new PersonalWorkService(
                new PersonalWorkRepository(jdbc), workAccess, List.of(), audit, mapper);
        workCalendarService = new WorkCalendarService(
                new WorkCalendarRepository(jdbc), workAccess,
                personalWorkService, List.of(), audit);
    }

    @Test
    void personalFocusEventAndExactWorkLinkHaveIndependentReplayAndRemovalLifecycles() {
        PersonalWorkDtos.AccessContext authorized = context(true);
        PersonalWorkDtos.Task work = tx(() -> personalWorkService.create(
                authorized,
                UUID.randomUUID(),
                "corr-work-create",
                new PersonalWorkDtos.CreateTaskRequest(
                        "Prepare the operating review",
                        null,
                        PersonalWorkDtos.Priority.NORMAL,
                        OffsetDateTime.parse("2026-11-03T10:00:00-05:00"),
                        null)));
        UUID calendarId = tx(() -> {
            calendarRepository.linkIdentity(TENANT_ID, USER_ID, PERSON_ID);
            return calendarRepository.ensurePersonalCalendar(TENANT_ID, USER_ID, PERSON_ID);
        });
        CalendarDtos.CalendarSummary personalCalendar = tx(() -> calendarService.calendars(
                        TENANT_ID, USER_ID, PERSON_ID, "en-US"))
                .stream()
                .filter(candidate -> candidate.calendarId().equals(calendarId))
                .findFirst()
                .orElseThrow();
        assertThat(personalCalendar.type()).isEqualTo(CalendarTypes.CalendarType.PERSONAL);
        assertThat(personalCalendar.ownerPersonPublicId()).isEqualTo(PERSON_ID);
        assertThat(personalCalendar.capabilities().canCreateEvents()).isTrue();

        CalendarDtos.CreateEventRequest command = new CalendarDtos.CreateEventRequest(
                "Focus · Prepare the operating review",
                null,
                CalendarTypes.EventType.FOCUS,
                STARTS_AT,
                ENDS_AT,
                "America/New_York",
                false,
                null,
                null,
                CalendarTypes.EventVisibility.PRIVATE,
                CalendarTypes.RecurrencePattern.NONE,
                1,
                null,
                false,
                List.of(),
                null,
                LINK_ID,
                calendarId,
                CalendarTypes.EventImportance.NORMAL);

        CalendarDtos.EventSummary created = create(command, "corr-calendar-create");
        CalendarDtos.EventSummary replayAfterLostResponse =
                create(command, "corr-calendar-create-replay");
        assertThat(replayAfterLostResponse.eventId()).isEqualTo(created.eventId());
        assertThat(created.calendarId()).isEqualTo(calendarId);
        assertThat(created.type()).isEqualTo(CalendarTypes.EventType.FOCUS);
        assertThat(created.timeZone()).isEqualTo("America/New_York");
        assertThat(Duration.between(created.startsAt(), created.endsAt()))
                .isEqualTo(Duration.ofHours(1));
        assertThat(created.startsAt().toInstant()).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(created.endsAt().toInstant()).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
        assertThat(count("cal_events")).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT idempotency_key FROM cal_events WHERE tenant_id = ? AND event_id = ?",
                UUID.class,
                TENANT_ID,
                created.eventId())).isEqualTo(LINK_ID);

        PersonalWorkDtos.SourceReference workReference = new PersonalWorkDtos.SourceReference(
                "PERSONAL_TASK", work.taskId().toString(), null);
        WorkCalendarDtos.LinkRequest linkCommand =
                new WorkCalendarDtos.LinkRequest(workReference, created.eventId());
        PersonalWorkDtos.AccessContext revokedCalendar = context(false);
        assertThatThrownBy(() -> tx(() -> workCalendarService.put(
                revokedCalendar, LINK_ID, linkCommand, "corr-link-denied")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(count("cal_events")).isOne();
        assertThat(count("personal_work_calendar_links")).isZero();
        assertThat(tx(() -> personalWorkService.get(authorized, work.taskId())).status())
                .isEqualTo(PersonalWorkDtos.Status.OPEN);

        WorkCalendarDtos.Link linked = tx(() -> workCalendarService.put(
                authorized, LINK_ID, linkCommand, "corr-link-create"));
        WorkCalendarDtos.Link linkReplayAfterLostResponse = tx(() -> workCalendarService.put(
                authorized, LINK_ID, linkCommand, "corr-link-replay"));
        assertThat(linkReplayAfterLostResponse).isEqualTo(linked);
        assertThat(linked.linkId()).isEqualTo(LINK_ID);
        assertThat(linked.work()).isEqualTo(workReference);
        assertThat(linked.eventId()).isEqualTo(created.eventId());
        assertThat(linked.state()).isEqualTo("LINKED");
        assertThat(linked.calendarAvailability()).isEqualTo("REFERENCE_ONLY");
        assertThat(count("personal_work_calendar_links")).isOne();

        List<CalendarDtos.EventSummary> visible = tx(() -> calendarService.events(
                TENANT_ID,
                USER_ID,
                PERSON_ID,
                OffsetDateTime.parse("2026-11-01T00:00:00-04:00"),
                OffsetDateTime.parse("2026-11-02T00:00:00-05:00"),
                "en-US"));
        assertThat(visible).singleElement().extracting(CalendarDtos.EventSummary::eventId)
                .isEqualTo(created.eventId());
        assertThat(tx(() -> workCalendarService.list(authorized, 0, 100)).items())
                .containsExactly(linked);

        WorkCalendarDtos.Link removed = tx(() -> workCalendarService.remove(
                revokedCalendar, LINK_ID, linked.version(), "corr-unlink"));
        assertThat(removed.state()).isEqualTo("REMOVED");
        assertThat(removed.version()).isGreaterThan(linked.version());
        assertThat(tx(() -> workCalendarService.list(authorized, 0, 100)).items()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cal_events WHERE tenant_id = ? AND event_id = ?",
                String.class,
                TENANT_ID,
                created.eventId())).isEqualTo("CONFIRMED");
        assertThat(tx(() -> personalWorkService.get(authorized, work.taskId())).status())
                .isEqualTo(PersonalWorkDtos.Status.OPEN);

        txRun(() -> calendarService.cancel(
                TENANT_ID,
                USER_ID,
                PERSON_ID,
                created.eventId(),
                "en-US",
                "corr-calendar-cancel",
                new CalendarDtos.VersionRequest(created.version())));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cal_events WHERE tenant_id = ? AND event_id = ?",
                String.class,
                TENANT_ID,
                created.eventId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM personal_work_calendar_links "
                        + "WHERE tenant_id = ? AND owner_user_id = ? AND link_id = ?",
                String.class,
                TENANT_ID,
                USER_ID,
                LINK_ID)).isEqualTo("REMOVED");
        assertThat(tx(() -> personalWorkService.get(authorized, work.taskId())).status())
                .isEqualTo(PersonalWorkDtos.Status.OPEN);
    }

    private CalendarDtos.EventSummary create(
            CalendarDtos.CreateEventRequest request,
            String correlationId) {
        return tx(() -> calendarService.create(
                TENANT_ID,
                USER_ID,
                PERSON_ID,
                "Calendar owner",
                "en-US",
                correlationId,
                request));
    }

    private PersonalWorkDtos.AccessContext context(boolean calendarAccess) {
        return new PersonalWorkDtos.AccessContext(
                TENANT_ID,
                USER_ID,
                "APP.WORK:VIEW,APP.WORK:UPDATE" + (calendarAccess ? ",APP.CALENDAR:VIEW" : ""),
                PERSON_ID,
                null,
                "en-US");
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
                Integer.class,
                TENANT_ID);
    }

    private <T> T tx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private void txRun(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
    }
}
