package com.dwp.services.platform.calendar;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.calendar.CalendarReminderDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CalendarReminderPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(9_981_000L);

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper json;

    private long tenantId;
    private long ownerUserId;
    private UUID ownerPersonId;
    private CalendarReminderRepository repository;
    private CalendarRepository calendar;

    @BeforeAll
    static void migrateLatest() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        json = new ObjectMapper().findAndRegisterModules();
    }

    @BeforeEach
    void setUp() {
        tenantId = TENANTS.incrementAndGet();
        ownerUserId = tenantId + 100;
        ownerPersonId = UUID.randomUUID();
        repository = new CalendarReminderRepository(jdbc, json);
        calendar = new CalendarRepository(jdbc, json);
    }

    @Test
    void multipleDefinitionsAreVersionedAndTenantPersonAndGroupAuthorityFailClosed() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        Fixture fixture = fixture(
                "versioned", now.plusDays(2), "UTC", "NONE", 1, null);
        CalendarReminderService service = service(now);
        CalendarReminderAccess.Actor owner = actor(
                tenantId, ownerUserId, ownerPersonId, null);

        ReminderSet first = tx(() -> service.replace(
                owner, fixture.eventId(), "corr-reminders",
                new ReplaceRemindersRequest(0L, List.of(
                        new ReminderInput(10, ReminderChannel.IN_APP),
                        new ReminderInput(60, ReminderChannel.IN_APP)))));
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.eventVersion()).isZero();
        assertThat(first.state()).isEqualTo(ReminderSetState.ACTIVE);
        assertThat(first.reminders()).extracting(ReminderDefinition::offsetMinutes)
                .containsExactly(10, 60);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cal_event_reminder_definitions
                 WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, tenantId, fixture.eventId())).isEqualTo(2);

        assertThatThrownBy(() -> tx(() -> service.replace(
                owner, fixture.eventId(), null,
                new ReplaceRemindersRequest(0L, List.of()))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> service.reminders(
                actor(tenantId + 1, ownerUserId, ownerPersonId, null), fixture.eventId())))
                .isInstanceOf(BaseException.class);

        long groupUser = tenantId + 200;
        UUID groupPerson = UUID.randomUUID();
        UUID groupRef = UUID.randomUUID();
        identity(tenantId, groupUser, groupPerson);
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    grant_id, tenant_id, calendar_id, principal_type,
                    principal_group_ref, principal_display_name, access_level,
                    can_view_private, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'GROUP', ?, 'Reminder viewers', 'VIEW_DETAILS',
                        TRUE, 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), tenantId, fixture.calendarId(), groupRef,
                ownerUserId, ownerUserId);
        assertThatThrownBy(() -> tx(() -> service.reminders(
                actor(tenantId, groupUser, groupPerson, null), fixture.eventId())))
                .isInstanceOf(BaseException.class);
        ReminderSet groupSet = tx(() -> service.replace(
                actor(tenantId, groupUser, groupPerson, groupRef.toString()),
                fixture.eventId(), null,
                new ReplaceRemindersRequest(0L, List.of(
                        new ReminderInput(5, ReminderChannel.IN_APP)))));
        assertThat(groupSet.version()).isEqualTo(1);
        assertThat(groupSet.reminders()).hasSize(1);

        long freeBusyUser = tenantId + 300;
        UUID freeBusyPerson = UUID.randomUUID();
        UUID freeBusyGroup = UUID.randomUUID();
        identity(tenantId, freeBusyUser, freeBusyPerson);
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    grant_id, tenant_id, calendar_id, principal_type,
                    principal_group_ref, principal_display_name, access_level,
                    can_view_private, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'GROUP', ?, 'Free busy only', 'VIEW_FREE_BUSY',
                        FALSE, 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), tenantId, fixture.calendarId(), freeBusyGroup,
                ownerUserId, ownerUserId);
        assertThatThrownBy(() -> tx(() -> service.replace(
                actor(tenantId, freeBusyUser, freeBusyPerson, freeBusyGroup.toString()),
                fixture.eventId(), null,
                new ReplaceRemindersRequest(0L, List.of(
                        new ReminderInput(5, ReminderChannel.IN_APP))))))
                .isInstanceOf(BaseException.class);

        long attendeeUser = tenantId + 400;
        UUID attendeePerson = UUID.randomUUID();
        identity(tenantId, attendeeUser, attendeePerson);
        jdbc.update("""
                INSERT INTO cal_event_attendees (
                    tenant_id, event_id, attendee_user_id,
                    attendee_person_public_id, attendee_email, attendee_name,
                    attendee_type, response_status)
                VALUES (?, ?, ?, ?, 'attendee@example.com', 'Reminder attendee',
                        'REQUIRED', 'ACCEPTED')
                """, tenantId, fixture.eventId(), attendeeUser, attendeePerson);
        ReminderSet attendeeSet = tx(() -> service.replace(
                actor(tenantId, attendeeUser, attendeePerson, null),
                fixture.eventId(), null,
                new ReplaceRemindersRequest(0L, List.of(
                        new ReminderInput(15, ReminderChannel.IN_APP)))));
        assertThat(attendeeSet.state()).isEqualTo(ReminderSetState.ACTIVE);

        assertThatThrownBy(() -> tx(() -> service.replace(
                owner, fixture.eventId(), null,
                new ReplaceRemindersRequest(1L, List.of(
                        new ReminderInput(0, ReminderChannel.IN_APP),
                        new ReminderInput(1, ReminderChannel.IN_APP),
                        new ReminderInput(2, ReminderChannel.IN_APP),
                        new ReminderInput(3, ReminderChannel.IN_APP),
                        new ReminderInput(4, ReminderChannel.IN_APP),
                        new ReminderInput(5, ReminderChannel.IN_APP))))))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rescheduleVersionAndCancellationInvalidateClaimsUntilExplicitReplacement() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-01T00:00:00Z");
        Fixture fixture = fixture(
                "invalidate", now.plusHours(2), "UTC", "NONE", 1, null);
        CalendarReminderService service = service(now);
        CalendarReminderAccess.Actor owner = actor(
                tenantId, ownerUserId, ownerPersonId, null);
        tx(() -> service.replace(owner, fixture.eventId(), null,
                new ReplaceRemindersRequest(0L, List.of(
                        new ReminderInput(60, ReminderChannel.IN_APP)))));

        jdbc.update("""
                UPDATE cal_events
                   SET starts_at = starts_at + INTERVAL '2 hours',
                       ends_at = ends_at + INTERVAL '2 hours',
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND event_id = ?
                """, tenantId, fixture.eventId());
        ReminderSet invalidated = tx(() -> service.reminders(owner, fixture.eventId()));
        assertThat(invalidated.state()).isEqualTo(ReminderSetState.INVALIDATED);
        assertThat(jdbc.queryForMap("""
                SELECT reminder_state, invalidation_reason
                  FROM cal_event_reminder_sets
                 WHERE tenant_id = ? AND event_id = ? AND owner_user_id = ?
                """, tenantId, fixture.eventId(), ownerUserId))
                .containsEntry("reminder_state", "INVALIDATED")
                .containsEntry("invalidation_reason", "EVENT_RESCHEDULED");
        tx(() -> publisher().publishDue(now.plusHours(4), 10));
        assertThat(outboxCount(fixture.eventId())).isZero();

        ReminderSet rebound = tx(() -> service.replace(
                owner, fixture.eventId(), null,
                new ReplaceRemindersRequest(1L, List.of(
                        new ReminderInput(60, ReminderChannel.IN_APP)))));
        assertThat(rebound.version()).isEqualTo(2);
        assertThat(rebound.state()).isEqualTo(ReminderSetState.ACTIVE);

        jdbc.update("""
                UPDATE cal_events
                   SET status = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND event_id = ?
                """, tenantId, fixture.eventId());
        assertThat(jdbc.queryForMap("""
                SELECT reminder_state, invalidation_reason
                  FROM cal_event_reminder_sets
                 WHERE tenant_id = ? AND event_id = ? AND owner_user_id = ?
                """, tenantId, fixture.eventId(), ownerUserId))
                .containsEntry("reminder_state", "INVALIDATED")
                .containsEntry("invalidation_reason", "EVENT_CANCELLED");
        assertThat(tx(() -> publisher().publishDue(now.plusDays(1), 10)).emitted()).isZero();
        assertThat(outboxCount(fixture.eventId())).isZero();
    }

    @Test
    void globalWorkersRaceAndRestartWithoutDuplicatesAndAdvanceAcrossDst() throws Exception {
        OffsetDateTime configuredAt = OffsetDateTime.parse("2026-03-06T00:00:00Z");
        OffsetDateTime firstOccurrence = OffsetDateTime.parse("2026-03-07T09:00:00-05:00");
        Fixture fixture = fixture(
                "dst", firstOccurrence, "America/New_York", "DAILY", 1,
                java.time.LocalDate.parse("2026-03-10"));
        CalendarReminderService service = service(configuredAt);
        tx(() -> service.replace(
                actor(tenantId, ownerUserId, ownerPersonId, null),
                fixture.eventId(), null,
                new ReplaceRemindersRequest(0L, List.of(
                        new ReminderInput(0, ReminderChannel.IN_APP)))));
        CalendarReminderTransactions publisher = publisher();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_service_tenants WHERE tenant_id = ?
                """, Integer.class, tenantId)).isZero();

        var first = tx(() -> publisher.publishDue(firstOccurrence, 10));
        var restart = tx(() -> publisher.publishDue(firstOccurrence, 10));
        assertThat(first.emitted()).isOne();
        assertThat(restart.emitted()).isZero();
        OffsetDateTime secondOccurrence = jdbc.queryForObject("""
                SELECT next_occurrence_starts_at
                  FROM cal_event_reminder_definitions
                 WHERE tenant_id = ? AND event_id = ?
                """, OffsetDateTime.class, tenantId, fixture.eventId());
        assertThat(secondOccurrence.toInstant())
                .isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
        assertThat(java.time.Duration.between(
                firstOccurrence.toInstant(), secondOccurrence.toInstant()).toHours())
                .isEqualTo(23);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<CalendarReminderTransactions.BatchResult> left = executor.submit(() -> {
                start.await();
                return tx(() -> publisher.publishDue(secondOccurrence, 10));
            });
            Future<CalendarReminderTransactions.BatchResult> right = executor.submit(() -> {
                start.await();
                return tx(() -> publisher.publishDue(secondOccurrence, 10));
            });
            start.countDown();
            assertThat(left.get().emitted() + right.get().emitted()).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cal_event_reminder_delivery_ledger
                 WHERE tenant_id = ? AND event_id = ? AND decision = 'EMITTED'
                """, Integer.class, tenantId, fixture.eventId())).isEqualTo(2);
        assertThat(outboxCount(fixture.eventId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT domain_event_id)
                  FROM cal_event_reminder_delivery_ledger
                 WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, tenantId, fixture.eventId())).isEqualTo(2);
    }

    private CalendarReminderService service(OffsetDateTime now) {
        return new CalendarReminderService(
                repository, calendar, false,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
    }

    private CalendarReminderTransactions publisher() {
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        DomainEventRecorder recorder = new DomainEventRecorder(
                new DomainEventOutboxRepository(
                        new NamedParameterJdbcTemplate(dataSource), json),
                contracts,
                json);
        return new CalendarReminderTransactions(
                repository,
                new CalendarReminderNotificationEvents(recorder, contracts, json));
    }

    private Fixture fixture(
            String suffix,
            OffsetDateTime startsAt,
            String timeZone,
            String recurrence,
            int recurrenceInterval,
            java.time.LocalDate recurrenceUntil) {
        identity(tenantId, ownerUserId, ownerPersonId);
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key,
                    owner_user_id, owner_person_public_id, owner_display_name,
                    name_ko, name_en, calendar_type, visibility,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Reminder owner',
                        '리마인더 캘린더', 'Reminder calendar', 'PERSONAL', 'PRIVATE',
                        'ACTIVE', ?, ?)
                """, calendarId, tenantId, "reminder-" + suffix,
                ownerUserId, ownerPersonId, ownerUserId, ownerUserId);
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, event_type, starts_at, ends_at, time_zone,
                    status, visibility, recurrence_pattern, recurrence_interval,
                    recurrence_until, source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Reminder owner', 'owner@example.com',
                        'Reminder verification', 'MEETING', ?, ?, ?,
                        'CONFIRMED', 'DEFAULT', ?, ?, ?, 'NATIVE', ?, ?)
                """, eventId, tenantId, calendarId, ownerUserId, ownerPersonId,
                startsAt, startsAt.plusHours(1), timeZone,
                recurrence, recurrenceInterval, recurrenceUntil,
                ownerUserId, ownerUserId);
        return new Fixture(calendarId, eventId);
    }

    private void identity(long tenant, long user, UUID person) {
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, tenant, user, person);
    }

    private int outboxCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_domain_event_outbox
                 WHERE tenant_id = ?
                   AND event_source = 'urn:dwp:platform:calendar'
                   AND payload -> 'data' -> 'notificationIntents' -> 0
                       -> 'variables' ->> 'eventId' = ?
                """, Integer.class, tenantId, eventId.toString());
    }

    private CalendarReminderAccess.Actor actor(
            long tenant,
            long user,
            UUID person,
            String groups) {
        return new CalendarReminderAccess.Actor(tenant, user, person, groups);
    }

    private <T> T tx(Supplier<T> callback) {
        return transactions.execute(status -> callback.get());
    }

    private record Fixture(UUID calendarId, UUID eventId) {
    }
}
