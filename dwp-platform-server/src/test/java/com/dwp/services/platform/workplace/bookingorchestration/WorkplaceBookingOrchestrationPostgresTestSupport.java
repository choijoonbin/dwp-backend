package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.RoomService;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

abstract class WorkplaceBookingOrchestrationPostgresTestSupport {
    protected static final Instant FIXED = Instant.parse("2026-09-16T06:00:00Z");
    protected static final long ACTOR = 72001L;
    protected static final AtomicLong TENANTS = new AtomicLong(9_940_000L);

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static JdbcTemplate jdbc;
    protected static TransactionTemplate transaction;
    protected static WorkplaceBookingOrchestrationRepository repository;
    protected static WorkplaceBookingOrchestrationService service;
    protected static DataSourceTransactionManager transactionManager;
    protected static WorkplaceSpatialGovernanceService spatial;

    @BeforeAll
    protected static void migrateAndBuildActualRepository() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(transactionManager);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        repository = new WorkplaceBookingOrchestrationRepository(jdbc, mapper);
        spatial = mock(WorkplaceSpatialGovernanceService.class);
        when(spatial.evaluateFloorAccess(
                anyLong(), anyLong(), any(), any(UUID.class), any(UUID.class),
                any(WorkplaceSpatialGovernanceDtos.AccessPermission.class)))
                .thenAnswer(invocation -> new WorkplaceSpatialGovernanceDtos.SiteAccessDecision(
                        invocation.getArgument(3), invocation.getArgument(1),
                        invocation.getArgument(5), true, "TEST_ALLOW", List.of(),
                        OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC),
                        invocation.getArgument(4)));
        service = new WorkplaceBookingOrchestrationService(
                repository, spatial, mock(WorkplaceBookingBatchExecutor.class), mapper,
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    protected static IntentPreviewRequest previewRequest(Fixture fixture) {
        return new IntentPreviewRequest(List.of(new IntentItemRequest(
                "weekday-desk", ACTOR, fixture.person(), "Client supplied name", null,
                ResourceType.DESK, fixture.resource(), fixture.site(), fixture.floor(),
                starts(), starts().plusHours(1), "Deep work", true, false, List.of())),
                120, true, "Plan the work week");
    }

    protected static WaitlistCreateRequest waitlistRequest(Fixture fixture, boolean autoConfirm) {
        return new WaitlistCreateRequest(
                plannerItem(fixture, "waitlist-promotion", fixture.resource()),
                autoConfirm,
                new WaitlistConditions(
                        null, starts().minusHours(1), starts().plusHours(2),
                        PricingMode.NOT_APPLICABLE, null, null),
                List.of(NotificationChannel.IN_APP),
                "Promote the first verified resource that becomes available");
    }

    protected static WorkplaceWaitlistPromotionWorker worker(WorkplaceService workplace) {
        return worker(workplace, FIXED);
    }

    protected static WorkplaceWaitlistPromotionWorker worker(
            WorkplaceService workplace, Instant instant) {
        WorkplaceBookingBatchExecutor executor = new WorkplaceBookingBatchExecutor(
                repository, workplace, mock(RoomService.class), transactionManager,
                Clock.fixed(instant, ZoneOffset.UTC));
        return new WorkplaceWaitlistPromotionWorker(
                repository, service, executor, spatial, transactionManager,
                Clock.fixed(instant, ZoneOffset.UTC), true, 200, 120);
    }

    protected static void awaitAndMaintain(
            CountDownLatch start, WorkplaceWaitlistPromotionWorker worker) {
        try {
            start.await();
            worker.maintainOnce();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    protected static Fixture fixture() {
        long tenant = TENANTS.incrementAndGet();
        UUID site = UUID.randomUUID();
        UUID floor = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
                    data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Booking orchestration test', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenant, "booking-orchestration-" + tenant, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id) VALUES (?)", tenant);
        jdbc.update("""
                INSERT INTO wp_sites (
                    site_id, tenant_id, site_code, name_ko, name_en, time_zone)
                VALUES (?, ?, ?, '서울', 'Seoul', 'Asia/Seoul')
                """, site, tenant, "SITE_" + site);
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, name_ko, name_en,
                    lifecycle_state)
                VALUES (?, ?, ?, 10, '10층', '10F', 'ACTIVE')
                """, floor, tenant, site);
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
                    resource_type, neighborhood, position_x, position_y,
                    features, lifecycle_state, booking_mode)
                VALUES (?, ?, ?, ?, '집중 좌석', 'Focus desk', 'DESK', 'ALPHA', 5, 5,
                        '[]'::jsonb,
                        'AVAILABLE', 'RESERVABLE')
                """, resource, tenant, floor, "DESK_" + resource);
        return new Fixture(tenant, site, floor, resource, person);
    }

    protected static UUID intent(long tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_booking_intents (
                    intent_id, tenant_id, actor_user_id, intent_state, reason,
                    requested_hold_ttl_seconds, allow_alternatives, idempotency_key,
                    request_fingerprint, correlation_id, version)
                VALUES (?, ?, ?, 'HELD', 'Fixture', 120, TRUE, ?, ?, ?, 1)
                """, id, tenant, ACTOR, "intent-" + id, "a".repeat(64), "corr-" + id);
        return id;
    }

    protected static IntentItemRequest plannerItem(
            Fixture fixture, String clientKey, UUID resourceId) {
        return new IntentItemRequest(
                clientKey, ACTOR, fixture.person(), "Planner member", null,
                ResourceType.DESK, resourceId, fixture.site(), fixture.floor(),
                starts(), starts().plusHours(1), "Team work", true, false, List.of());
    }

    protected static UUID item(Fixture fixture, UUID intent, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_booking_intent_items (
                    intent_item_id, intent_id, tenant_id, client_item_key, actor_user_id,
                    beneficiary_user_id, beneficiary_display_name, resource_type,
                    preferred_resource_id, site_id, floor_id, starts_at, ends_at,
                    visible_to_colleagues, accessible_only, required_features, decision,
                    decision_code, candidate_resource_ids, version)
                VALUES (?, ?, ?, ?, ?, ?, 'Member', 'DESK', ?, ?, ?, ?, ?, TRUE, FALSE,
                        '[]'::jsonb, 'AVAILABLE', 'AVAILABLE', ?::jsonb, 1)
                """, id, intent, fixture.tenant(), key, ACTOR, ACTOR,
                fixture.resource(), fixture.site(), fixture.floor(), starts(),
                starts().plusHours(1), "[\"" + fixture.resource() + "\"]");
        return id;
    }

    protected static UUID hold(Fixture fixture, UUID intent, UUID item) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_reservation_holds (
                    hold_id, tenant_id, intent_id, intent_item_id, resource_id,
                    actor_user_id, beneficiary_user_id, hold_state, starts_at, ends_at,
                    expires_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)
                """, id, fixture.tenant(), intent, item, fixture.resource(), ACTOR, ACTOR,
                starts(), starts().plusHours(1), futureExpiry());
        return id;
    }

    protected static UUID batch(long tenant, UUID intent) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_booking_batches (
                    batch_id, tenant_id, intent_id, actor_user_id, batch_state,
                    failure_policy, reason, explicit_confirmation, idempotency_key,
                    request_fingerprint, correlation_id, version)
                VALUES (?, ?, ?, ?, 'ACCEPTED', 'KEEP_SUCCEEDED', 'Fixture', TRUE,
                        ?, ?, ?, 1)
                """, id, tenant, intent, ACTOR, "batch-" + id, "b".repeat(64), "corr-" + id);
        return id;
    }

    protected static UUID waitlist(Fixture fixture, String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_waitlist_entries (
                    waitlist_entry_id, tenant_id, actor_user_id, beneficiary_user_id,
                    beneficiary_display_name, resource_type, preferred_resource_id,
                    site_id, floor_id, starts_at, ends_at, visible_to_colleagues,
                    auto_confirm, notification_channels, waitlist_state,
                    rank_visible, idempotency_key, request_fingerprint, correlation_id,
                    version)
                VALUES (?, ?, ?, ?, 'Member', 'DESK', ?, ?, ?, ?, ?, TRUE, FALSE,
                        '[\"IN_APP\"]'::jsonb, 'ACTIVE', FALSE, ?, ?, ?, 1)
                """, id, fixture.tenant(), ACTOR, ACTOR, fixture.resource(), fixture.site(),
                fixture.floor(), starts(), starts().plusHours(1), "wait-" + suffix + id,
                "c".repeat(64), "corr-" + id);
        return id;
    }

    protected static UUID rawBooking(
            Fixture fixture, UUID resource, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.queryForObject("""
                INSERT INTO wp_bookings (
                    tenant_id, resource_id, user_id, booked_for_display_name,
                    starts_at, ends_at, booking_status, policy_snapshot,
                    policy_snapshot_hash, require_check_in_snapshot,
                    check_in_lead_minutes_snapshot, auto_release_minutes_snapshot,
                    booking_retention_days_snapshot)
                VALUES (?, ?, ?, 'Member', ?, ?, 'RESERVED', '{}'::jsonb,
                        encode(digest('{}'::jsonb::TEXT, 'sha256'), 'hex'),
                        FALSE, 15, 0, 365)
                RETURNING booking_id
                """, UUID.class, fixture.tenant(), resource, ACTOR, from, to);
    }

    protected static OffsetDateTime starts() {
        return OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC).plusDays(2);
    }

    protected static OffsetDateTime futureExpiry() {
        return OffsetDateTime.now(Clock.systemUTC()).plusHours(1);
    }

    protected static <T> T tx(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    protected static void assertConstraint(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.springframework.transaction.TransactionSystemException.class);
    }

    protected record Fixture(
            long tenant, UUID site, UUID floor, UUID resource, UUID person) { }
}
