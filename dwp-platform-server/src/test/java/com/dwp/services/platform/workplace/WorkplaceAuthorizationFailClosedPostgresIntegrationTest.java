package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.media.TenantMediaStorage;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.AccessPermission;
import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingMode;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceAuthorizationFailClosedPostgresIntegrationTest {

    private static final long BOOKING_TENANT = 9_130_001L;
    private static final long ROOM_TENANT = 9_130_002L;
    private static final long USER_ID = 9_131_001L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void migrateLatestSchema() {
        dataSource = new PGSimpleDataSource();
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
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void permissionRevocationImmediatelyHidesAndBlocksAnExistingBooking() {
        Fixture fixture = fixture(BOOKING_TENANT, "BOOKING", ResourceType.DESK, null);
        WorkplaceSpatialGovernanceService governance = governance();
        WorkplaceRuntimeGovernance runtimeGovernance =
                new WorkplaceRuntimeGovernance(governance);
        WorkplaceCatalogRepository catalog = new WorkplaceCatalogRepository(jdbc, objectMapper);
        WorkplaceBookingRepository bookings = new WorkplaceBookingRepository(jdbc, objectMapper);
        WorkplaceService service = workplaceService(
                catalog, bookings, governance, runtimeGovernance);
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(1);
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(BOOKING_TENANT);
        WorkplaceBookingRepository.BookingRow booking = bookings.createBooking(
                BOOKING_TENANT,
                USER_ID,
                UUID.randomUUID(),
                "Enterprise member",
                new WorkplaceDtos.BookingRequest(
                        fixture.resourceId(), startsAt, startsAt.plusHours(1), "Focus", true),
                policy,
                null,
                false);

        assertThat(governance.evaluateSiteAccess(
                BOOKING_TENANT, USER_ID, null, fixture.siteId(), AccessPermission.BOOK))
                .satisfies(decision -> {
                    assertThat(decision.allowed()).isFalse();
                    assertThat(decision.decision()).isEqualTo("DENY_NOT_CONFIGURED");
                });

        UUID ruleId = allowUser(BOOKING_TENANT, fixture.siteId(), AccessPermission.MANAGE);
        assertThat(service.myBookings(
                BOOKING_TENANT,
                USER_ID,
                startsAt.minusDays(1),
                startsAt.plusDays(1),
                "en-US",
                null))
                .extracting(WorkplaceDtos.Booking::bookingId)
                .containsExactly(booking.bookingId());

        jdbc.update("""
                UPDATE wp_site_access_rules
                   SET lifecycle_state = 'INACTIVE', version = version + 1
                 WHERE tenant_id = ? AND access_rule_id = ?
                """, BOOKING_TENANT, ruleId);

        assertThat(service.myBookings(
                BOOKING_TENANT,
                USER_ID,
                startsAt.minusDays(1),
                startsAt.plusDays(1),
                "en-US",
                null)).isEmpty();
        assertThatThrownBy(() -> service.cancelBooking(
                BOOKING_TENANT,
                USER_ID,
                booking.bookingId(),
                "en-US",
                "revoked-access",
                null,
                new WorkplaceDtos.VersionRequest(booking.version())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("""
                SELECT booking_status FROM wp_bookings
                 WHERE tenant_id = ? AND booking_id = ?
                """, String.class, BOOKING_TENANT, booking.bookingId()))
                .isEqualTo("RESERVED");
    }

    @Test
    void calendarRoomMustBeMappedAndRemainAuthorized() {
        UUID calendarResourceId = UUID.randomUUID();
        Fixture fixture = fixture(
                ROOM_TENANT, "ROOM", ResourceType.ROOM, calendarResourceId);
        WorkplaceRuntimeGovernance runtimeGovernance =
                new WorkplaceRuntimeGovernance(governance());
        WorkplaceRoomAccessAdapter adapter = new WorkplaceRoomAccessAdapter(
                new NamedParameterJdbcTemplate(dataSource), runtimeGovernance);
        UUID unmapped = UUID.randomUUID();

        assertThat(adapter.viewableResourceIds(
                ROOM_TENANT, USER_ID, null, List.of(unmapped))).isEmpty();
        assertThatThrownBy(() -> adapter.requireBook(
                ROOM_TENANT, USER_ID, null, unmapped))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        UUID ruleId = allowUser(ROOM_TENANT, fixture.siteId(), AccessPermission.MANAGE);
        assertThat(adapter.viewableResourceIds(
                ROOM_TENANT, USER_ID, null, List.of(calendarResourceId)))
                .containsExactly(calendarResourceId);
        adapter.requireBook(ROOM_TENANT, USER_ID, null, calendarResourceId);

        jdbc.update("""
                UPDATE wp_site_access_rules
                   SET lifecycle_state = 'INACTIVE', version = version + 1
                 WHERE tenant_id = ? AND access_rule_id = ?
                """, ROOM_TENANT, ruleId);

        assertThat(adapter.viewableResourceIds(
                ROOM_TENANT, USER_ID, null, Set.of(calendarResourceId))).isEmpty();
        assertThatThrownBy(() -> adapter.requireBook(
                ROOM_TENANT, USER_ID, null, calendarResourceId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static WorkplaceSpatialGovernanceService governance() {
        return new WorkplaceSpatialGovernanceService(
                new WorkplaceSpatialGovernanceRepository(jdbc, objectMapper), objectMapper);
    }

    private static WorkplaceService workplaceService(
            WorkplaceCatalogRepository catalog,
            WorkplaceBookingRepository bookings,
            WorkplaceSpatialGovernanceService governance,
            WorkplaceRuntimeGovernance runtimeGovernance) {
        return new WorkplaceService(
                catalog,
                bookings,
                mock(CalendarService.class),
                mock(TenantMediaStorage.class),
                mock(WorkplaceFloorPlanValidator.class),
                mock(WorkplaceMediaCleanupRepository.class),
                governance,
                new WorkplaceReleaseWindowRepository(jdbc),
                mock(WorkplaceDomainEvents.class),
                runtimeGovernance);
    }

    private static Fixture fixture(
            long tenantId,
            String suffix,
            ResourceType resourceType,
            UUID calendarResourceId) {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name,
                    lifecycle_state, data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenantId, "workplace-auth-" + suffix.toLowerCase(),
                "Workplace auth " + suffix, USER_ID, USER_ID);
        jdbc.update("""
                INSERT INTO wp_tenant_policies (tenant_id, created_by, updated_by)
                VALUES (?, ?, ?)
                """, tenantId, USER_ID, USER_ID);
        if (calendarResourceId != null) {
            jdbc.update("""
                    INSERT INTO cal_resources (
                        resource_id, tenant_id, resource_code, name_ko, name_en,
                        resource_type, site_name, floor_name, capacity,
                        created_by, updated_by)
                    VALUES (?, ?, ?, '회의실', 'Meeting room', 'ROOM',
                            'Seoul', '10F', 8, ?, ?)
                    """, calendarResourceId, tenantId, "CAL-" + suffix, USER_ID, USER_ID);
        }
        jdbc.update("""
                INSERT INTO wp_sites (
                    site_id, tenant_id, site_code, name_ko, name_en, site_type,
                    time_zone, total_floor_count, created_by, updated_by)
                VALUES (?, ?, ?, '서울', 'Seoul', 'HEADQUARTERS',
                        'Asia/Seoul', 1, ?, ?)
                """, siteId, tenantId, "SITE_" + suffix, USER_ID, USER_ID);
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, name_ko, name_en,
                    plan_width, plan_height, created_by, updated_by)
                VALUES (?, ?, ?, 10, '10층', '10F', 1200, 760, ?, ?)
                """, floorId, tenantId, siteId, USER_ID, USER_ID);
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, calendar_resource_id,
                    resource_code, name_ko, name_en, resource_type, booking_mode,
                    capacity, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, '업무 공간', 'Workspace', ?, ?, 1, ?, ?)
                """, resourceId, tenantId, floorId, calendarResourceId,
                "RESOURCE_" + suffix, resourceType.name(), BookingMode.RESERVABLE.name(),
                USER_ID, USER_ID);
        return new Fixture(siteId, floorId, resourceId);
    }

    private static UUID allowUser(
            long tenantId, UUID siteId, AccessPermission permission) {
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_site_access_rules (
                    access_rule_id, tenant_id, site_id, subject_type, subject_user_id,
                    permission_code, effect, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'USER', ?, ?, 'ALLOW', 'ACTIVE', ?, ?)
                """, ruleId, tenantId, siteId, USER_ID, permission.name(), USER_ID, USER_ID);
        return ruleId;
    }

    private record Fixture(UUID siteId, UUID floorId, UUID resourceId) {
    }
}
