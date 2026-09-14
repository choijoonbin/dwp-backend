package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarDtos;
import com.dwp.services.platform.calendar.CalendarRepository;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.calendar.CalendarTypes;
import com.dwp.services.platform.calendar.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** Actual native owners, scoped access adapter and all production DB triggers remain enabled. */
@Testcontainers(disabledWithoutDocker = true)
class WorkplaceExperienceRoomCapacityPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(9_950_000L);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static CalendarService calendar;
    private static RoomService rooms;

    @BeforeAll static void actualNativeOwnerContext() throws Exception {
        var ds = new PGSimpleDataSource(); ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername()); ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds); transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var spatial = new WorkplaceSpatialGovernanceService(new WorkplaceSpatialGovernanceRepository(jdbc, mapper), mapper);
        var access = new WorkplaceRoomAccessAdapter(new NamedParameterJdbcTemplate(jdbc), new WorkplaceRuntimeGovernance(spatial));
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(CalendarRepository.class, () -> new CalendarRepository(jdbc, mapper));
        context.registerBean(WorkplaceRoomAccessPort.class, () -> access);
        context.registerBean(Class.forName("com.dwp.services.platform.calendar.CalendarSchedulingHorizon"));
        context.registerBean(Class.forName("com.dwp.services.platform.calendar.RoomBookingPolicyService"));
        context.registerBean(Class.forName("com.dwp.services.platform.calendar.RoomRepository"));
        context.registerBean(CalendarService.class); context.registerBean(RoomService.class); context.refresh();
        calendar = context.getBean(CalendarService.class); rooms = context.getBean(RoomService.class);
    }

    @Test void roomCreateAcceptsBoundaryAndCountsTheActualDeduplicatedInvitationRows() {
        var f = fixture(2);
        var invitations = List.of(new CalendarDtos.AttendeeInput(null, null, "one@sk.com", "Optional person",
                CalendarTypes.AttendeeType.OPTIONAL), attendee(" TWO@sk.com "), attendee("two@sk.com"));
        var saved = tx(() -> rooms.createRoomBooking(f.tenant, 7L, null, "Owner", "en", null, null,
                create(f.room, invitations)));
        assertThat(saved.resource().capacity()).isEqualTo(2);
        assertThat(count("cal_event_attendees", f)).isEqualTo(2);
        assertThat(count("cal_events", f)).isOne();
        assertThat(count("cal_resource_bookings", f)).isOne();
    }

    @Test void directCalendarCreateRejectsOverCapacityBeforeAnyEventBookingOrAuditIsSaved() {
        var f = fixture(2);
        assertInvalid(() -> tx(() -> calendar.create(f.tenant, 7L, null, "Owner", "en", null, null,
                create(f.room, attendees(3)))));
        assertThat(count("cal_events", f)).isZero();
        assertThat(count("cal_resource_bookings", f)).isZero();
        assertThat(count("cal_event_attendees", f)).isZero();
        assertThat(count("cal_audit_events", f)).isZero();
    }

    @Test void roomAttendeeOnlyUpdateRejectsExcessAndPreservesEveryNativeOwnerRow() {
        var f = fixture(2); var saved = save(f, 2);
        assertInvalid(() -> tx(() -> rooms.updateRoomBooking(f.tenant, 7L, null, saved.eventId(), "en", null, null,
                update(saved, f.room, attendees(3)))));
        assertUnchanged(f, saved);
    }

    @Test void directCalendarRelocationRejectsSmallerTargetAndKeepsOriginalBooking() {
        var f = fixture(2); var saved = save(f, 2); UUID smaller = resource(f, 1);
        assertInvalid(() -> tx(() -> calendar.update(f.tenant, 7L, null, saved.eventId(), "en", null, null,
                update(saved, smaller, attendees(2)))));
        assertUnchanged(f, saved);
        assertThat(jdbc.queryForObject("SELECT resource_id FROM cal_resource_bookings WHERE tenant_id=? AND event_id=? AND booking_status='CONFIRMED'",
                UUID.class, f.tenant, saved.eventId())).isEqualTo(f.room);
    }

    @Test void capacityDoesNotReplaceCalendarOwnerTenantOrCurrentSiteAuthorization() {
        var f = fixture(2); var saved = save(f, 2); var other = fixture(10);
        assertThatThrownBy(() -> tx(() -> calendar.update(f.tenant, 8L, null, saved.eventId(), "en", null, null,
                update(saved, f.room, attendees(1))))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> calendar.create(other.tenant, 7L, null, "Owner", "en", null, null,
                create(f.room, attendees(1))))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE wp_site_access_rules SET lifecycle_state='INACTIVE',version=version+1 WHERE tenant_id=? AND subject_user_id=7", f.tenant);
        assertThatThrownBy(() -> tx(() -> calendar.update(f.tenant, 7L, null, saved.eventId(), "en", null, null,
                update(saved, f.room, attendees(1))))).isInstanceOf(BaseException.class);
        assertUnchanged(f, saved); assertThat(count("cal_events", other)).isZero();
    }

    @Test void nonResourceCalendarEventHasNoInventedSinglePersonRoomCapacityRule() {
        var f = fixture(1);
        var saved = tx(() -> calendar.create(f.tenant, 7L, null, "Owner", "en", null, null,
                create(null, attendees(3))));
        assertThat(saved.resource()).isNull();
        assertThat(count("cal_event_attendees", f)).isEqualTo(3);
        assertThat(count("cal_resource_bookings", f)).isZero();
    }

    @Test void waitingBookingReadsCommittedCapacityRatherThanTheEarlierResourceSnapshot() throws Exception {
        var f = fixture(2); var pool = Executors.newFixedThreadPool(2);
        var updated = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        try {
            var changing = pool.submit(() -> tx(() -> {
                jdbc.update("UPDATE cal_resources SET capacity=1,version=version+1 WHERE tenant_id=? AND resource_id=?", f.tenant, f.room);
                updated.countDown(); await(release); return true;
            }));
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
            var booking = pool.submit(() -> { entered.countDown(); return save(f, 2); });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> booking.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(changing.get(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> booking.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BaseException.class).hasRootCauseMessage("The number of invited attendees exceeds the meeting room capacity.");
            assertThat(count("cal_events", f)).isZero(); assertThat(count("cal_resource_bookings", f)).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static CalendarDtos.EventSummary save(Fixture f, int count) {
        return tx(() -> rooms.createRoomBooking(f.tenant, 7L, null, "Owner", "en", null, null, create(f.room, attendees(count))));
    }
    private static void assertInvalid(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("attendees exceeds");
    }
    private static void assertUnchanged(Fixture f, CalendarDtos.EventSummary before) {
        assertThat(jdbc.queryForObject("SELECT version FROM cal_events WHERE tenant_id=? AND event_id=?", Long.class, f.tenant, before.eventId()))
                .isEqualTo(before.version());
        assertThat(jdbc.queryForObject("SELECT title FROM cal_events WHERE tenant_id=? AND event_id=?", String.class, f.tenant, before.eventId()))
                .isEqualTo(before.title());
        assertThat(count("cal_event_attendees", f)).isEqualTo(2);
        assertThat(count("cal_resource_bookings", f)).isOne();
        assertThat(count("cal_audit_events", f)).isOne();
    }
    private static List<CalendarDtos.AttendeeInput> attendees(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> attendee("person" + i + "@sk.com")).toList();
    }
    private static CalendarDtos.AttendeeInput attendee(String email) {
        return new CalendarDtos.AttendeeInput(null, null, email, "Invited person", CalendarTypes.AttendeeType.REQUIRED);
    }
    private static CalendarDtos.CreateEventRequest create(UUID resource, List<CalendarDtos.AttendeeInput> attendees) {
        OffsetDateTime start = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(2).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        return new CalendarDtos.CreateEventRequest("Capacity boundary", "Native agenda", CalendarTypes.EventType.MEETING,
                start, start.plusHours(1), "Asia/Seoul", false, null, null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null, true, attendees, resource, UUID.randomUUID());
    }
    private static CalendarDtos.UpdateEventRequest update(CalendarDtos.EventSummary before, UUID resource,
                                                        List<CalendarDtos.AttendeeInput> attendees) {
        return new CalendarDtos.UpdateEventRequest("Changed title", "Changed agenda", CalendarTypes.EventType.MEETING,
                before.startsAt(), before.endsAt(), "Asia/Seoul", false, null, null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null, true, attendees, resource, before.version());
    }
    private static long count(String table, Fixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, f.tenant);
    }
    private static Fixture fixture(int capacity) {
        long tenant = TENANTS.incrementAndGet(); UUID site = UUID.randomUUID(), floor = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_service_tenants(provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by) VALUES(?,?,?,'Capacity test','ACTIVE','kr','POOL',7,7)", UUID.randomUUID(), tenant, "capacity_" + tenant);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenant);
        jdbc.update("INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone) VALUES(?,?,?,'Seoul','Seoul','Asia/Seoul')", site, tenant, "SITE_" + site);
        jdbc.update("INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state) VALUES(?,?,?,10,'10F','10F','ACTIVE')", floor, tenant, site);
        for (long actor : new long[]{7, 8}) jdbc.update("INSERT INTO wp_site_access_rules(tenant_id,site_id,subject_type,subject_user_id,permission_code,effect,lifecycle_state,created_by,updated_by) VALUES(?,?,'USER',?,'MANAGE','ALLOW','ACTIVE',7,7)", tenant, site, actor);
        var f = new Fixture(tenant, site, floor, null); return new Fixture(tenant, site, floor, resource(f, capacity));
    }
    private static UUID resource(Fixture f, int capacity) {
        UUID room = UUID.randomUUID(), wp = UUID.randomUUID();
        jdbc.update("INSERT INTO cal_resources(resource_id,tenant_id,resource_code,name_ko,name_en,resource_type,site_name,floor_name,capacity) VALUES(?,?,?,'Room','Room','ROOM','Seoul','10F',?)", room, f.tenant, "ROOM_" + room, capacity);
        jdbc.update("INSERT INTO wp_resources(resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type,calendar_resource_id) VALUES(?,?,?,?,'Room','Room','ROOM',?)", wp, f.tenant, f.floor, "WP_" + wp, room);
        return room;
    }
    private static <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Capacity concurrency barrier timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private record Fixture(long tenant, UUID site, UUID floor, UUID room) { }
}
