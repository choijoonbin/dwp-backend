package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.calendar.CalendarBookingDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CalendarBookingPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(9_983_000L);

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper json;

    private long tenantId;
    private CalendarBookingRepository repository;
    private CalendarBookingAccess.Actor owner;
    private CalendarBookingAccess.Actor secondBooker;
    private UUID calendarId;
    private OffsetDateTime now;

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
        repository = new CalendarBookingRepository(jdbc, json);
        owner = actor(tenantId + 100, UUID.randomUUID());
        secondBooker = actor(tenantId + 200, UUID.randomUUID());
        identity(owner);
        identity(secondBooker);
        calendarId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Booking host', '예약 캘린더', 'Booking calendar',
                        'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                """, calendarId, tenantId, "booking-" + tenantId, owner.userId(),
                owner.personPublicId(), owner.userId(), owner.userId());
        now = OffsetDateTime.parse("2026-10-28T01:00:00Z");
    }

    @Test
    void concurrentPagesForTheSameHostCalendarCannotHoldTheSameBufferedPeriod() throws Exception {
        CalendarBookingRepository.PageRow firstPage = publishedPage("first");
        CalendarBookingRepository.PageRow secondPage = publishedPage("second");
        OffsetDateTime startsAt = now.plusDays(2).withHour(2);
        OffsetDateTime endsAt = startsAt.plusMinutes(30);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch secondWaitingForHost = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CalendarBookingRepository.HoldRow> first = executor.submit(() -> tx(() -> {
                repository.lockHostSchedule(tenantId, calendarId);
                repository.expireHolds(tenantId, calendarId, now);
                CalendarBookingRepository.HoldRow result = insertHold(
                        owner, firstPage, startsAt, endsAt, now.plusMinutes(5), "1");
                firstInserted.countDown();
                await(releaseFirst);
                return result;
            }));
            Future<CalendarBookingRepository.HoldRow> second = executor.submit(() -> {
                await(firstInserted);
                return tx(() -> {
                    secondWaitingForHost.countDown();
                    repository.lockHostSchedule(tenantId, calendarId);
                    repository.expireHolds(tenantId, calendarId, now);
                    return insertHold(secondBooker, secondPage, startsAt, endsAt,
                            now.plusMinutes(5), "2");
                });
            });

            assertThat(secondWaitingForHost.await(10, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            CalendarBookingRepository.HoldRow winner = first.get(20, TimeUnit.SECONDS);
            assertThat(winner.state()).isEqualTo(BookingHoldState.HELD);
            assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(value -> assertThat(value.getCause())
                            .isInstanceOf(DataIntegrityViolationException.class));
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM cal_booking_holds
                     WHERE tenant_id = ? AND host_calendar_id = ? AND state = 'HELD'
                    """, Integer.class, tenantId, calendarId)).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logicalExpiryReleasesTheHostScopeAndFencingRemainsMonotonic() {
        CalendarBookingRepository.PageRow firstPage = publishedPage("expiry-first");
        CalendarBookingRepository.PageRow secondPage = publishedPage("expiry-second");
        OffsetDateTime startsAt = now.plusDays(2).withHour(3);
        OffsetDateTime endsAt = startsAt.plusMinutes(30);
        CalendarBookingRepository.HoldRow expired = tx(() -> {
            repository.lockHostSchedule(tenantId, calendarId);
            return insertHold(owner, firstPage, startsAt, endsAt,
                    now.plusMinutes(1), "3");
        });

        OffsetDateTime advanced = now.plusMinutes(2);
        CalendarBookingRepository.HoldRow replacement = tx(() -> {
            repository.lockHostSchedule(tenantId, calendarId);
            repository.expireHolds(tenantId, calendarId, advanced);
            return insertHold(secondBooker, secondPage, startsAt, endsAt,
                    advanced.plusMinutes(5), "4", advanced);
        });

        assertThat(jdbc.queryForObject("""
                SELECT state FROM cal_booking_holds
                 WHERE tenant_id = ? AND hold_id = ?
                """, String.class, tenantId, expired.holdId())).isEqualTo("EXPIRED");
        assertThat(replacement.state()).isEqualTo(BookingHoldState.HELD);
        assertThat(replacement.fencingToken()).isGreaterThan(expired.fencingToken());
    }

    @Test
    void databaseRejectsLongLivedHoldsAndLegalHoldDeletion() {
        CalendarBookingRepository.PageRow page = publishedPage("constraints");
        OffsetDateTime startsAt = now.plusDays(3).withHour(2);
        assertThatThrownBy(() -> tx(() -> insertHold(
                owner, page, startsAt, startsAt.plusMinutes(30),
                now.plusMinutes(11), "5")))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE cal_booking_pages SET legal_hold = TRUE
                 WHERE tenant_id = ? AND page_id = ?
                """, tenantId, page.pageId());
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM cal_booking_pages WHERE tenant_id = ? AND page_id = ?
                """, tenantId, page.pageId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("legal hold");
    }

    @Test
    void concurrentConsumeUsesTheFenceAndVersionAsAnAtomicSingleWinnerGate() throws Exception {
        CalendarBookingRepository.PageRow page = publishedPage("atomic-consume");
        OffsetDateTime startsAt = now.plusDays(2).withHour(4);
        CalendarBookingRepository.HoldRow hold = tx(() -> insertHold(
                owner, page, startsAt, startsAt.plusMinutes(30),
                now.plusMinutes(5), "6"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch consume = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Supplier<Integer> attempt = () -> {
                ready.countDown();
                await(consume);
                return tx(() -> repository.consumeHold(
                        owner, hold, hold.fencingToken(), now.plusSeconds(1)));
            };
            Future<Integer> first = executor.submit(attempt::get);
            Future<Integer> second = executor.submit(attempt::get);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            consume.countDown();

            assertThat(List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
            assertThat(repository.lockHold(tenantId, page.pageId(), hold.holdId()))
                    .get().satisfies(value -> {
                        assertThat(value.state()).isEqualTo(BookingHoldState.CONSUMED);
                        assertThat(value.version()).isEqualTo(1L);
                    });
            assertThat(repository.consumeHold(
                    owner, hold, hold.fencingToken() + 1, now.plusSeconds(2))).isZero();
        } finally {
            consume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exactTeamGrantIsCurrentAndFailsClosedImmediatelyAfterRevocation() {
        UUID granted = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    grant_id, tenant_id, calendar_id, principal_type,
                    principal_group_ref, principal_display_name, access_level,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'GROUP', ?, 'Booking managers', 'MANAGE',
                        'ACTIVE', ?, ?)
                """, grantId, tenantId, calendarId, granted,
                owner.userId(), owner.userId());

        assertThat(repository.hasActiveGroupWriteGrant(
                tenantId, calendarId, granted, now)).isTrue();
        assertThat(repository.hasActiveGroupWriteGrant(
                tenantId, calendarId, unrelated, now)).isFalse();

        jdbc.update("""
                UPDATE cal_calendar_access_grants
                   SET lifecycle_state = 'REVOKED', version = version + 1
                 WHERE grant_id = ?
                """, grantId);
        assertThat(repository.hasActiveGroupWriteGrant(
                tenantId, calendarId, granted, now)).isFalse();
    }

    @Test
    void hostBusyUsesBoundedRecurrenceProjectionAndCanExcludeTheCurrentAppointmentEvent() {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime startsAt = now.plusDays(1);
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, event_type,
                    starts_at, ends_at, time_zone, status, visibility,
                    recurrence_pattern, recurrence_interval, source_type,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Booking host', 'Busy check', 'MEETING',
                        ?, ?, 'UTC', 'CONFIRMED', 'DEFAULT', 'NONE', 1, 'NATIVE', ?, ?)
                """, eventId, tenantId, calendarId, owner.userId(), owner.personPublicId(),
                startsAt, startsAt.plusMinutes(30), owner.userId(), owner.userId());

        assertThat(repository.hostBusy(
                tenantId, owner.personPublicId(), startsAt.minusHours(1),
                startsAt.plusHours(1), null))
                .extracting(CalendarBookingRepository.HostBusyRow::eventId)
                .containsExactly(eventId);
        assertThat(repository.hostBusy(
                tenantId, owner.personPublicId(), startsAt.minusHours(1),
                startsAt.plusHours(1), eventId)).isEmpty();
    }

    private CalendarBookingRepository.PageRow publishedPage(String suffix) {
        UUID pageId = UUID.randomUUID();
        SaveBookingPageRequest request = new SaveBookingPageRequest(
                BookingOwnerType.PERSONAL, null, calendarId, "Booking " + suffix, null,
                "Asia/Seoul", 30, 10, 10, 8, 0, 60, MeetingMode.VIDEO, null,
                "https://meet.google.com/abc-defg-hij", null,
                List.of(new AvailabilityRule(
                        DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))),
                List.of(), 0L);
        repository.insertPage(pageId, owner, request, now);
        assertThat(repository.changeState(
                owner, pageId, 0, BookingPageState.PUBLISHED,
                CalendarBookingTokens.sha256(pageId.toString()),
                1, now, null)).isEqualTo(1);
        return repository.page(tenantId, pageId).orElseThrow();
    }

    private CalendarBookingRepository.HoldRow insertHold(
            CalendarBookingAccess.Actor actor,
            CalendarBookingRepository.PageRow page,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            String discriminator) {
        return insertHold(actor, page, startsAt, endsAt, expiresAt, discriminator, now);
    }

    private CalendarBookingRepository.HoldRow insertHold(
            CalendarBookingAccess.Actor actor,
            CalendarBookingRepository.PageRow page,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            String discriminator,
            OffsetDateTime createdAt) {
        CreateBookingHoldRequest request = new CreateBookingHoldRequest(
                startsAt, endsAt, UUID.randomUUID(), page.version());
        return repository.insertHold(
                UUID.randomUUID(), actor, page, request,
                startsAt.minusMinutes(10), endsAt.plusMinutes(10), expiresAt,
                discriminator.repeat(64), createdAt);
    }

    private CalendarBookingAccess.Actor actor(long userId, UUID personId) {
        return new CalendarBookingAccess.Actor(
                tenantId, userId, personId, null, Set.of());
    }

    private void identity(CalendarBookingAccess.Actor actor) {
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, actor.tenantId(), actor.userId(), actor.personPublicId());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Calendar booking concurrency latch timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Calendar booking concurrency test interrupted.", exception);
        }
    }

    private <T> T tx(Supplier<T> callback) {
        return transactions.execute(status -> callback.get());
    }
}
