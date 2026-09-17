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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.AccessPermission;
import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingMode;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceBookingCommandPostgresTest {

    private static final AtomicLong TENANTS = new AtomicLong(9_690_000L);
    private static final long ACTOR = 9_691_001L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static ObjectMapper mapper;

    @BeforeAll
    static void migrateLatestSchema() {
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
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void concurrentCancelReplaysExactlyAndSurvivesServiceRestart() throws Exception {
        Fixture fixture = fixture();
        WorkplaceService service = service();
        WorkplaceBookingRepository repository = new WorkplaceBookingRepository(jdbc, mapper);
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2).withNano(0);
        WorkplaceBookingRepository.BookingRow created = tx(() -> repository.createBooking(
                fixture.tenantId(), ACTOR, UUID.randomUUID(), "Member",
                new WorkplaceDtos.BookingRequest(
                        fixture.resourceId(), startsAt, startsAt.plusHours(1), "Focus", true),
                new WorkplaceCatalogRepository(jdbc, mapper).policy(fixture.tenantId()),
                null, false));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<WorkplaceDtos.Booking> committed = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkplaceDtos.Booking> first = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return cancel(service, fixture.tenantId(), created.bookingId(), "cancel-once", 0L);
            });
            Future<WorkplaceDtos.Booking> second = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return cancel(service, fixture.tenantId(), created.bookingId(), "cancel-once", 0L);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            WorkplaceDtos.Booking firstResult = first.get(20, TimeUnit.SECONDS);
            WorkplaceDtos.Booking secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(firstResult.status()).isEqualTo(WorkplaceTypes.BookingStatus.CANCELLED);
            committed.set(firstResult);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_commands
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key='cancel-once'
                """, Integer.class, fixture.tenantId(), ACTOR)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id=? AND aggregate_id=? AND action='workplace.booking.cancelled'
                """, Integer.class, fixture.tenantId(), created.bookingId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox outbox
                  JOIN wp_booking_commands command
                    ON command.tenant_id=outbox.tenant_id
                   AND command.audit_event_id=outbox.event_id
                 WHERE command.tenant_id=? AND command.idempotency_key='cancel-once'
                """, Integer.class, fixture.tenantId())).isEqualTo(1);

        WorkplaceDtos.Booking restartedReplay = cancel(
                service(), fixture.tenantId(), created.bookingId(), "cancel-once", 0L);
        assertThat(restartedReplay).isEqualTo(committed.get());

        assertThatThrownBy(() -> cancel(
                service(), fixture.tenantId(), created.bookingId(), "cancel-once", 1L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("different booking command");

        assertThat(new WorkplaceBookingRepository(jdbc, mapper)
                .bookingCommand(fixture.tenantId() + 1, ACTOR, "cancel-once")).isEmpty();

        jdbc.update("""
                UPDATE wp_bookings
                   SET personal_data_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 day'
                 WHERE tenant_id=? AND booking_id=?
                """, fixture.tenantId(), created.bookingId());
        assertThat(tx(() -> new WorkplacePrivacyRepository(jdbc).anonymizeExpired(10)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_commands
                 WHERE tenant_id=? AND booking_id=?
                """, Integer.class, fixture.tenantId(), created.bookingId())).isZero();
    }

    private static WorkplaceDtos.Booking cancel(
            WorkplaceService service,
            long tenantId,
            UUID bookingId,
            String key,
            long version) {
        return tx(() -> service.cancelBooking(
                tenantId, ACTOR, bookingId, "en-US", "screen16", null, key,
                new WorkplaceDtos.VersionRequest(version)));
    }

    private static WorkplaceService service() {
        WorkplaceCatalogRepository catalog = new WorkplaceCatalogRepository(jdbc, mapper);
        WorkplaceBookingRepository bookings = new WorkplaceBookingRepository(jdbc, mapper);
        WorkplaceSpatialGovernanceService spatial = new WorkplaceSpatialGovernanceService(
                new WorkplaceSpatialGovernanceRepository(jdbc, mapper), mapper);
        WorkplaceRuntimeGovernance runtime = new WorkplaceRuntimeGovernance(spatial);
        return new WorkplaceService(
                catalog, bookings, mock(CalendarService.class), mock(TenantMediaStorage.class),
                mock(WorkplaceFloorPlanValidator.class), mock(WorkplaceMediaCleanupRepository.class),
                spatial, new WorkplaceReleaseWindowRepository(jdbc),
                mock(WorkplaceDomainEvents.class), runtime);
    }

    private static Fixture fixture() {
        long tenantId = TENANTS.incrementAndGet();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Booking command','ACTIVE','kr','POOL',?,?)
                """, UUID.randomUUID(), tenantId, "booking_command_" + tenantId, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenantId);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone)
                VALUES(?,?,?,'서울','Seoul','Asia/Seoul')
                """, siteId, tenantId, "SITE_" + siteId);
        jdbc.update("""
                INSERT INTO wp_floors(
                    floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state)
                VALUES(?,?,?,10,'10층','10F','ACTIVE')
                """, floorId, tenantId, siteId);
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,
                    resource_type,booking_mode)
                VALUES(?,?,?,?,'좌석','Desk',?,?)
                """, resourceId, tenantId, floorId, "DESK_" + resourceId,
                ResourceType.DESK.name(), BookingMode.RESERVABLE.name());
        jdbc.update("""
                INSERT INTO wp_site_access_rules(
                    tenant_id,site_id,subject_type,subject_user_id,permission_code,
                    effect,lifecycle_state,created_by,updated_by)
                VALUES(?,?,'USER',?,?,'ALLOW','ACTIVE',?,?)
                """, tenantId, siteId, ACTOR, AccessPermission.MANAGE.name(), ACTOR, ACTOR);
        return new Fixture(tenantId, resourceId);
    }

    private static <T> T tx(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private record Fixture(long tenantId, UUID resourceId) {
    }
}
