package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceExperienceReportPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(9_700_000L);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static JdbcTemplate jdbc;
    private static WorkplaceExperienceReportRepository repository;
    private static WorkplaceExperienceReportService service;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrateActualProductionSchema() {
        var ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        var mapper = new ObjectMapper().findAndRegisterModules();
        repository = new WorkplaceExperienceReportRepository(new NamedParameterJdbcTemplate(jdbc));
        var catalog = new WorkplaceCatalogRepository(jdbc, mapper);
        var governance = new WorkplaceRuntimeGovernance(new WorkplaceSpatialGovernanceService(
                new WorkplaceSpatialGovernanceRepository(jdbc, mapper), mapper));
        service = new WorkplaceExperienceReportService(repository, catalog,
                new WorkplaceExperienceReportPolicyPreview(catalog, governance),
                new WorkplaceExperienceCollaborationRepository(jdbc));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
    }

    @Test
    void actualBookingDataCreatesScopeSafeMetricsExceptionsAndFreshness() {
        var f = fixture("DESK");
        UUID secondResource = resource(f.tenant(), f.floor(), "DESK");
        LocalDate day = LocalDate.now(ZONE).minusDays(1);
        OffsetDateTime nine = day.atTime(9, 0).atZone(ZONE).toOffsetDateTime();
        booking(f, f.resource(), "COMPLETED", nine, nine.plusHours(2), null);
        UUID noShow = booking(f, f.resource(), "NO_SHOW", nine.plusHours(2), nine.plusHours(3), nine.plusMinutes(150));
        booking(f, secondResource, "RELEASED", nine.plusHours(3), nine.plusHours(5), nine.plusHours(4));
        booking(f, secondResource, "CANCELLED", nine.plusHours(6), nine.plusHours(7), null);
        Report result = transaction.execute(status -> service.report(f.tenant(), f.site(), f.floor(), day, day.plusDays(1), 0, 1));
        assertThat(result).isNotNull();
        assertThat(result.metadata().availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.metadata().generatedAt()).isNotNull();
        assertThat(result.metadata().sourceUpdatedAt()).isNotNull();
        assertThat(result.current().reservableResources()).isEqualTo(2);
        assertThat(result.summary().bookingCount()).isEqualTo(3);
        assertThat(result.summary().cancelledCount()).isOne();
        assertThat(result.summary().bookedMinutes()).isEqualTo(180);
        assertThat(result.summary().denominatorResourceMinutes()).isEqualTo(2880);
        assertThat(result.summary().utilizationPercent()).isEqualTo(6.25);
        assertThat(result.summary().noShowPercent()).isEqualTo(33.33);
        assertThat(result.hourlyHeatmap()).hasSize(24);
        assertThat(result.exceptions().content()).extracting(BookingDetail::bookingId).containsExactly(noShow);
        assertThat(result.externalSources().getFirst().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.externalSources().getFirst().configurationStatus()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void crossTenantAndCrossSiteDetailQueriesNeverResolveTheBooking() {
        var visible = fixture("DESK");
        var hidden = fixture("DESK");
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        UUID id = booking(hidden, hidden.resource(), "RESERVED", start, start.plusHours(1), null);
        assertThat(repository.booking(visible.tenant(), visible.site(), id)).isEmpty();
        assertThat(repository.booking(hidden.tenant(), visible.site(), id)).isEmpty();
        assertThatThrownBy(() -> service.booking(visible.tenant(), visible.site(), id)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.report(visible.tenant(), visible.site(), hidden.floor(),
                LocalDate.now(ZONE), LocalDate.now(ZONE).plusDays(1), 0, 20)).isInstanceOf(BaseException.class);
    }

    @Test
    void noShowEligibilityUsesTheImmutableBookingPolicyRatherThanTodaysPolicy() {
        var f = fixture("DESK");
        LocalDate day = LocalDate.now(ZONE).minusDays(1);
        OffsetDateTime start = day.atTime(9, 0).atZone(ZONE).toOffsetDateTime();
        booking(f, f.resource(), "NO_SHOW", start, start.plusHours(1), start.plusMinutes(30));
        jdbc.update("UPDATE wp_tenant_policies SET require_check_in = FALSE WHERE tenant_id = ?", f.tenant());
        booking(f, f.resource(), "COMPLETED", start.plusHours(1), start.plusHours(2), null);
        var result = transaction.execute(status -> service.report(f.tenant(), f.site(), null,
                day, day.plusDays(1), 0, 20));
        assertThat(result.current().policy().requireCheckIn()).isFalse();
        assertThat(result.summary().bookingCount()).isEqualTo(2);
        assertThat(result.summary().noShowEligibleCount()).isOne();
        assertThat(result.summary().noShowCount()).isOne();
        assertThat(result.summary().noShowPercent()).isEqualTo(100);
    }

    @Test
    void futureImpactIsPagedAndReadOnlyAndDoesNotScheduleReplacement() {
        var f = fixture("DESK");
        OffsetDateTime start = OffsetDateTime.now().plusDays(2);
        UUID first = booking(f, f.resource(), "RESERVED", start, start.plusHours(1), null);
        UUID second = booking(f, f.resource(), "RESERVED", start.plusHours(1), start.plusHours(2), null);
        booking(f, f.resource(), "CANCELLED", start.plusHours(2), start.plusHours(3), null);
        FutureBookingImpact impact = transaction.execute(status -> service.futureImpact(f.tenant(), f.site(),
                f.resource(), start.minusHours(1), start.plusDays(1), 1, 1));
        assertThat(impact.affectedBookings().totalElements()).isEqualTo(2);
        assertThat(impact.affectedBookings().totalPages()).isEqualTo(2);
        assertThat(impact.affectedBookings().content()).extracting(BookingDetail::bookingId).containsExactly(second);
        assertThat(impact.mutatesBookings()).isFalse();
        assertThat(impact.notificationScheduled()).isFalse();
        assertThat(impact.replacementScheduled()).isFalse();
        assertThat(repository.booking(f.tenant(), f.site(), first).orElseThrow().status()).isEqualTo("RESERVED");
        assertThat(repository.booking(f.tenant(), f.site(), first).orElseThrow().version()).isZero();
    }

    @Test
    void emptyCohortsRemainEmptyAndRoomOwnerIsExplicitlyUnavailable() {
        var desk = fixture("DESK");
        LocalDate day = LocalDate.now(ZONE);
        Report report = transaction.execute(status -> service.report(desk.tenant(), desk.site(), null, day, day.plusDays(1), 0, 20));
        assertThat(report.metadata().availability()).isEqualTo(Availability.EMPTY);
        assertThat(report.summary().utilizationPercent()).isNull();
        assertThat(report.summary().noShowPercent()).isNull();
        var room = fixture("ROOM");
        OffsetDateTime now = OffsetDateTime.now();
        var impact = service.futureImpact(room.tenant(), room.site(), room.resource(), now, now.plusDays(1), 0, 20);
        assertThat(impact.owner()).isEqualTo("ROOMS");
        assertThat(impact.metadata().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(impact.affectedBookings()).isNull();
    }

    @Test
    void readOnlyPreviewUsesEffectiveActualPolicyAndDoesNotWriteBookings() {
        var f = fixture("DESK");
        OffsetDateTime start = LocalDate.now(ZONE).plusDays(2).atTime(9, 0).atZone(ZONE).toOffsetDateTime();
        UUID id = booking(f, f.resource(), "RESERVED", start, start.plusHours(3), null);
        var changes = new PolicyChanges(null, 10, null, 60, LocalTime.of(10, 0), LocalTime.of(12, 0));
        var preview = transaction.execute(status -> service.policyImpact(f.tenant(), f.site(), f.floor(),
                start.minusHours(1), start.plusDays(1), changes, 0, 20));
        assertThat(preview.reviewedBookings()).isOne();
        assertThat(preview.affectedBookings()).isOne();
        assertThat(preview.content().getFirst().knownEffects()).contains(
                "EXCEEDS_PROPOSED_MAXIMUM_DURATION", "OUTSIDE_PROPOSED_WORKING_HOURS", "PROPOSED_AUTO_RELEASE_DEADLINE_CHANGED");
        assertThat(preview.mutatesExistingBookings()).isFalse();
        assertThat(repository.booking(f.tenant(), f.site(), id).orElseThrow().version()).isZero();
        assertThat(jdbc.queryForObject("SELECT maximum_booking_minutes FROM wp_tenant_policies WHERE tenant_id = ?",
                Integer.class, f.tenant())).isEqualTo(720);
    }

    @Test
    void dailyPolicyImpactUsesTheCompleteActualCohortBeforeExceptionPagination() {
        var f = fixture("DESK");
        OffsetDateTime start = LocalDate.now(ZONE).plusDays(2).atTime(9,0).atZone(ZONE).toOffsetDateTime();
        booking(f,f.resource(),"RESERVED",start,start.plusHours(2),null);
        booking(f,f.resource(),"RESERVED",start.plusHours(3),start.plusHours(5),null);
        booking(f,f.resource(),"RESERVED",start.plusDays(1),start.plusDays(1).plusHours(1),null);
        var changes = new PolicyChanges(null,null,null,60,null,null);
        var preview = transaction.execute(status -> service.policyImpact(f.tenant(),f.site(),f.floor(),
                start.minusHours(1),start.plusDays(3).withHour(0),changes,1,1));
        assertThat(preview.reviewedBookings()).isEqualTo(3);
        assertThat(preview.affectedBookings()).isEqualTo(2);
        assertThat(preview.content()).hasSize(1);
        assertThat(preview.dailyImpact()).hasSize(3);
        assertThat(preview.dailyImpact().getFirst()).isEqualTo(new PolicyDailyImpact(start.toLocalDate(),"Asia/Seoul",2,2));
        assertThat(preview.dailyImpact().get(1)).isEqualTo(new PolicyDailyImpact(start.toLocalDate().plusDays(1),"Asia/Seoul",1,0));
        assertThat(preview.dailyImpact().getLast().reviewedBookings()).isZero();
        assertThat(preview.dailyImpact().stream().mapToLong(PolicyDailyImpact::reviewedBookings).sum()).isEqualTo(preview.reviewedBookings());
        assertThat(preview.mutatesExistingBookings()).isFalse();
    }

    private Fixture fixture(String type) {
        long tenant = TENANTS.incrementAndGet();
        UUID site = UUID.randomUUID();
        UUID floor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(provider_tenant_id,tenant_id,tenant_key,display_name,
                    lifecycle_state,data_region,isolation_model,created_by,updated_by)
                VALUES (?, ?, ?, 'Experience report test', 'ACTIVE', 'kr', 'POOL', 7, 7)
                """, UUID.randomUUID(), tenant, "report_" + tenant);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES (?)", tenant);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone)
                VALUES (?, ?, ?, 'Seoul', 'Seoul', 'Asia/Seoul')
                """, site, tenant, "SITE_" + site);
        jdbc.update("""
                INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state)
                VALUES (?, ?, ?, 10, '10F', '10F', 'ACTIVE')
                """, floor, tenant, site);
        return new Fixture(tenant, site, floor, resource(tenant, floor, type));
    }

    private UUID resource(long tenant, UUID floor, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_resources(resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type)
                VALUES (?, ?, ?, ?, 'Workspace', 'Workspace', ?)
                """, id, tenant, floor, "R_" + id, type);
        return id;
    }

    private UUID booking(Fixture f, UUID resource, String status, OffsetDateTime from,
                         OffsetDateTime to, OffsetDateTime releasedAt) {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var policy = new WorkplaceCatalogRepository(jdbc, mapper).policy(f.tenant());
        UUID id = new WorkplaceBookingRepository(jdbc, mapper).createBooking(f.tenant(), 7L, null,
                "Private booking identity", new WorkplaceDtos.BookingRequest(resource, from, to, null, false),
                policy, null, false).bookingId();
        jdbc.update("UPDATE wp_bookings SET booking_status = ?, released_at = ? WHERE tenant_id = ? AND booking_id = ?",
                status, releasedAt, f.tenant(), id);
        return id;
    }
    private record Fixture(long tenant, UUID site, UUID floor, UUID resource) { }
}
