package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceExperienceOperationsScopePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final AtomicLong TENANTS = new AtomicLong(9_960_000L);
    static JdbcTemplate jdbc;
    static WorkplaceCatalogRepository catalog;
    static WorkplaceBookingRepository bookings;
    static WorkplaceOperationsService service;
    static final OffsetDateTime FROM = OffsetDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);

    @BeforeAll static void migrateActualSchema() {
        var ds = new PGSimpleDataSource(); ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername()); ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper().findAndRegisterModules();
        catalog = new WorkplaceCatalogRepository(jdbc, mapper);
        bookings = new WorkplaceBookingRepository(jdbc, mapper);
        service = new WorkplaceOperationsService(catalog, bookings,
                new WorkplaceOperationsRepository(new NamedParameterJdbcTemplate(jdbc), mapper),
                mock(WorkplaceService.class), mock(WorkplaceDomainEvents.class));
    }

    @Test void sitePaginationCountsAndCanonicalIdsUseSameTenantJoinedCohort() {
        var one = fixture(); var otherSite = location(one.tenant); var otherTenant = fixture();
        var first = booking(one, 0); var second = booking(one, 2); booking(otherSite, 4); booking(otherTenant, 6);
        var page0 = search(one.tenant, one.site, null, 0, 1);
        var page1 = search(one.tenant, one.site, one.floor, 1, 1);
        assertThat(page0.totalElements()).isEqualTo(2); assertThat(page1.totalElements()).isEqualTo(2);
        assertThat(page0.totalPages()).isEqualTo(2); assertThat(page0.content()).hasSize(1);
        assertThat(page0.content().getFirst().bookingId()).isEqualTo(second);
        assertThat(page1.content().getFirst().bookingId()).isEqualTo(first);
        assertThat(page0.content().getFirst().siteId()).isEqualTo(one.site);
        assertThat(page0.content().getFirst().floorId()).isEqualTo(one.floor);
        var legacy = service.adminBookings(one.tenant, FROM.minusHours(1), FROM.plusDays(1), null, null, null, "en", 0, 100);
        assertThat(legacy.totalElements()).isEqualTo(3);
        assertThat(legacy.content()).extracting(WorkplaceOperationsDtos.AdminBooking::siteId).containsOnly(one.site, otherSite.site);
    }

    @Test void invalidForeignAndMismatchedCanonicalScopeIs404WithoutRows() {
        var one = fixture(); var otherSite = location(one.tenant); var otherTenant = fixture(); booking(one, 0);
        for (var site : new UUID[]{UUID.randomUUID(), otherTenant.site}) {
            assertThatThrownBy(() -> search(one.tenant, site, null, 0, 10)).isInstanceOfSatisfying(BaseException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        for (var floor : new UUID[]{UUID.randomUUID(), otherSite.floor, otherTenant.floor}) {
            assertThatThrownBy(() -> search(one.tenant, one.site, floor, 0, 10)).isInstanceOfSatisfying(BaseException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        assertThatThrownBy(() -> search(one.tenant, null, one.floor, 0, 10)).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void optionalScopeComposesWithExistingResourceUserStatusAndPageBounds() {
        var one = fixture(); var other = location(one.tenant); var id = booking(one, 0); booking(other, 2);
        var result = service.adminBookings(one.tenant, FROM.minusHours(1), FROM.plusDays(1), one.site, one.floor,
                BookingStatus.RESERVED, one.resource, 7L, "en", 0, 10);
        assertThat(result.totalElements()).isOne(); assertThat(result.content().getFirst().bookingId()).isEqualTo(id);
        var mismatch = service.adminBookings(one.tenant, FROM.minusHours(1), FROM.plusDays(1), one.site, one.floor,
                null, other.resource, null, "en", 0, 10);
        assertThat(mismatch.content()).isEmpty(); assertThat(mismatch.totalElements()).isZero();
        assertThatThrownBy(() -> search(one.tenant, one.site, one.floor, 0, 101)).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_bookings WHERE tenant_id=? AND booking_status='RESERVED'", Long.class, one.tenant)).isEqualTo(2);
    }

    private WorkplaceOperationsDtos.AdminBookingPage search(long tenant, UUID site, UUID floor, int page, int size) {
        return service.adminBookings(tenant, FROM.minusHours(1), FROM.plusDays(1), site, floor, null, null, null, "en", page, size);
    }
    private Fixture fixture() {
        long tenant = TENANTS.incrementAndGet();
        jdbc.update("""
            INSERT INTO sys_service_tenants(provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by)
            VALUES (?, ?, ?, 'Operations scope test', 'ACTIVE', 'kr', 'POOL', 7, 7)
            """, UUID.randomUUID(), tenant, "operations_scope_" + tenant);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES (?)", tenant); return location(tenant);
    }
    private Fixture location(long tenant) {
        UUID site = UUID.randomUUID(), floor = UUID.randomUUID(), resource = UUID.randomUUID();
        jdbc.update("INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone) VALUES (?,?,?,'서울','Seoul','Asia/Seoul')", site, tenant, "S_" + site);
        jdbc.update("INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state) VALUES (?,?,?,1,'1층','1F','ACTIVE')", floor, tenant, site);
        jdbc.update("INSERT INTO wp_resources(resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type) VALUES (?,?,?,?,'자원','Resource','DESK')", resource, tenant, floor, "R_" + resource);
        return new Fixture(tenant, site, floor, resource);
    }
    private UUID booking(Fixture f, int hour) {
        return bookings.createBooking(f.tenant, 7L, null, "Private owner",
                new WorkplaceDtos.BookingRequest(f.resource, FROM.plusHours(hour), FROM.plusHours(hour + 1), null, false),
                catalog.policy(f.tenant), null, false).bookingId();
    }
    record Fixture(long tenant, UUID site, UUID floor, UUID resource) { }
}
