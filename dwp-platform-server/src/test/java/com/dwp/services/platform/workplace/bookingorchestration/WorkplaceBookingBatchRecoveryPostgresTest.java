package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.RoomService;
import com.dwp.services.platform.workplace.WorkplaceDtos;
import com.dwp.services.platform.workplace.WorkplaceOperationsService;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceBookingBatchRecoveryPostgresTest {
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-17T03:00:00Z");
    private static final long ACTOR = 73001L;
    private static final AtomicLong TENANTS = new AtomicLong(9_970_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static WorkplaceBookingOrchestrationRepository repository;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        repository = new WorkplaceBookingOrchestrationRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void crashAfterBatchClaimIsRecoveredAfterLeaseExpiry() {
        Fixture fixture = fixture(1, "KEEP_SUCCEEDED");
        UUID deadToken = UUID.randomUUID();
        assertThat(repository.claimBatchExecution(
                fixture.tenant(), fixture.batchId(), deadToken, NOW, NOW.minusSeconds(1),
                NOW.minusSeconds(30)))
                .isPresent();
        Owner owner = ownerReturningBookings();

        owner.executor().execute(fixture.tenant(), fixture.batchId(), "en", null);

        assertThat(batchState(fixture)).isEqualTo("SUCCEEDED");
        assertThat(itemState(fixture.itemIds().getFirst())).isEqualTo("SUCCEEDED");
        assertThat(attempts(fixture)).isEqualTo(2);
        verify(owner.operations()).createBooking(
                eq(fixture.tenant()), eq(ACTOR), any(), anyString(), eq("en"), anyString(),
                eq(commandKey(fixture.itemIds().getFirst())), eq(null), any());
    }

    @Test
    void ownerExceptionRollsBackTransientProcessingAndLeavesDurableUnknown() {
        Fixture fixture = fixture(1, "KEEP_SUCCEEDED");
        WorkplaceOperationsService operations = mock(WorkplaceOperationsService.class);
        when(operations.createBooking(
                anyLong(), anyLong(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenThrow(new RuntimeException("lost owner reply"));
        executor(operations, mock(WorkplaceService.class)).execute(
                fixture.tenant(), fixture.batchId(), "en", null);

        assertThat(itemState(fixture.itemIds().getFirst())).isEqualTo("RESULT_UNKNOWN");
        assertThat(batchState(fixture)).isEqualTo("RESULT_UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_batch_items
                 WHERE tenant_id = ? AND batch_id = ? AND item_state = 'PROCESSING'
                """, Long.class, fixture.tenant(), fixture.batchId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT hold_state FROM wp_reservation_holds WHERE tenant_id = ? AND hold_id = ?
                """, String.class, fixture.tenant(), fixture.holdIds().getFirst()))
                .isEqualTo("BATCHED");
    }

    @Test
    void staleProcessingItemIsRefusedWithoutBlindOwnerReplay() {
        Fixture fixture = fixture(1, "KEEP_SUCCEEDED");
        jdbc.update("""
                UPDATE wp_booking_batches
                   SET batch_state = 'PROCESSING', execution_claim_token = ?,
                       execution_lease_expires_at = ?, execution_attempt_count = 1
                 WHERE tenant_id = ? AND batch_id = ?
                """, UUID.randomUUID(), NOW.minusSeconds(1),
                fixture.tenant(), fixture.batchId());
        jdbc.update("""
                UPDATE wp_booking_batch_items SET item_state = 'PROCESSING'
                 WHERE tenant_id = ? AND batch_item_id = ?
                """, fixture.tenant(), fixture.itemIds().getFirst());
        WorkplaceOperationsService operations = mock(WorkplaceOperationsService.class);

        executor(operations, mock(WorkplaceService.class)).execute(
                fixture.tenant(), fixture.batchId(), "en", null);

        verify(operations, never()).createBooking(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        assertThat(itemState(fixture.itemIds().getFirst())).isEqualTo("RESULT_UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT error_code FROM wp_booking_batch_items
                 WHERE tenant_id = ? AND batch_item_id = ?
                """, String.class, fixture.tenant(), fixture.itemIds().getFirst()))
                .isEqualTo("INTERRUPTED_OWNER_RESULT_UNKNOWN");
        assertThat(batchState(fixture)).isEqualTo("RESULT_UNKNOWN");
    }

    @Test
    void activeClaimExcludesConcurrentWorkerAndStaleTokenCannotRenewOrFinalize() {
        Fixture fixture = fixture(1, "KEEP_SUCCEEDED");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(repository.claimBatchExecution(
                fixture.tenant(), fixture.batchId(), first, NOW, NOW.plusSeconds(30),
                NOW.minusSeconds(30)))
                .isPresent();
        assertThat(repository.claimBatchExecution(
                fixture.tenant(), fixture.batchId(), second, NOW, NOW.plusSeconds(30),
                NOW.minusSeconds(30)))
                .isEmpty();
        jdbc.update("""
                UPDATE wp_booking_batches SET execution_lease_expires_at = ?
                 WHERE tenant_id = ? AND batch_id = ?
                """, NOW.minusSeconds(1), fixture.tenant(), fixture.batchId());
        assertThat(repository.claimBatchExecution(
                fixture.tenant(), fixture.batchId(), second, NOW, NOW.plusSeconds(30),
                NOW.minusSeconds(30)))
                .isPresent();

        assertThat(repository.renewBatchExecution(
                fixture.tenant(), fixture.batchId(), first, NOW.plusSeconds(30), NOW))
                .isFalse();
        assertThat(repository.finishBatchExecution(
                fixture.tenant(), fixture.batchId(), first,
                WorkplaceBookingOrchestrationDtos.BatchState.SUCCEEDED, NOW))
                .isFalse();
        assertThat(attempts(fixture)).isEqualTo(2);

        Fixture legacy = fixture(1, "KEEP_SUCCEEDED");
        jdbc.update("""
                UPDATE wp_booking_batches SET batch_state = 'PROCESSING',
                       execution_claim_token = NULL, execution_lease_expires_at = NULL,
                       updated_at = ? WHERE tenant_id = ? AND batch_id = ?
                """, NOW, legacy.tenant(), legacy.batchId());
        assertThat(repository.claimBatchExecution(
                legacy.tenant(), legacy.batchId(), UUID.randomUUID(), NOW,
                NOW.plusSeconds(30), NOW.minusSeconds(30))).isEmpty();
        jdbc.update("""
                UPDATE wp_booking_batches SET updated_at = ?
                 WHERE tenant_id = ? AND batch_id = ?
                """, NOW.minusSeconds(31), legacy.tenant(), legacy.batchId());
        assertThat(repository.claimBatchExecution(
                legacy.tenant(), legacy.batchId(), UUID.randomUUID(), NOW,
                NOW.plusSeconds(30), NOW.minusSeconds(30))).isPresent();
    }

    @Test
    void partialRestartSkipsCommittedSuccessAndFinalizesExactlyOnce() {
        Fixture fixture = fixture(2, "KEEP_SUCCEEDED");
        UUID committedItem = fixture.itemIds().getFirst();
        UUID ownerReference = UUID.randomUUID();
        seedSucceeded(fixture, 0, ownerReference);
        jdbc.update("""
                UPDATE wp_booking_batches SET batch_state = 'PROCESSING',
                       execution_claim_token = ?, execution_lease_expires_at = ?,
                       execution_attempt_count = 1
                 WHERE tenant_id = ? AND batch_id = ?
                """, UUID.randomUUID(), NOW.minusSeconds(1),
                fixture.tenant(), fixture.batchId());
        Owner owner = ownerReturningBookings();

        owner.executor().execute(fixture.tenant(), fixture.batchId(), "en", null);
        owner.executor().execute(fixture.tenant(), fixture.batchId(), "en", null);

        assertThat(batchState(fixture)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT owner_reference_id FROM wp_booking_batch_items
                 WHERE tenant_id = ? AND batch_item_id = ?
                """, UUID.class, fixture.tenant(), committedItem)).isEqualTo(ownerReference);
        verify(owner.operations()).createBooking(
                eq(fixture.tenant()), eq(ACTOR), any(), anyString(), eq("en"), anyString(),
                eq(commandKey(fixture.itemIds().get(1))), eq(null), any());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND event_type = 'BookingBatchCompleted'
                """, Long.class, fixture.tenant(), fixture.batchId())).isEqualTo(1L);
    }

    @Test
    void workplaceOwnerReceivesStableBatchItemIdempotencyKey() {
        Fixture fixture = fixture(1, "KEEP_SUCCEEDED");
        Owner owner = ownerReturningBookings();

        owner.executor().execute(fixture.tenant(), fixture.batchId(), "en", null);
        owner.executor().execute(fixture.tenant(), fixture.batchId(), "en", null);

        verify(owner.operations()).createBooking(
                eq(fixture.tenant()), eq(ACTOR), any(), eq("Member"), eq("en"), anyString(),
                eq(commandKey(fixture.itemIds().getFirst())), eq(null), any());
        assertThat(batchState(fixture)).isEqualTo("SUCCEEDED");
    }

    @Test
    void compensationCommitAckReplayPreservesTerminalOutcomeWithoutSecondOwnerCall() {
        Fixture fixture = fixture(1, "KEEP_SUCCEEDED");
        UUID ownerReference = UUID.randomUUID();
        seedSucceeded(fixture, 0, ownerReference);
        jdbc.update("""
                UPDATE wp_booking_batches SET batch_state = 'COMPENSATING'
                 WHERE tenant_id = ? AND batch_id = ?
                """, fixture.tenant(), fixture.batchId());
        WorkplaceService workplace = mock(WorkplaceService.class);
        WorkplaceBookingBatchExecutor executor =
                executor(mock(WorkplaceOperationsService.class), workplace);

        executor.compensateSelected(
                fixture.tenant(), ACTOR, fixture.batchId(),
                Set.of(fixture.itemIds().getFirst()), "en", null);
        executor.compensateSelected(
                fixture.tenant(), ACTOR, fixture.batchId(),
                Set.of(fixture.itemIds().getFirst()), "en", null);

        verify(workplace).cancelBooking(
                fixture.tenant(), ACTOR, ownerReference, "en", fixture.correlationId(),
                null, "booking-batch-compensation:" + fixture.itemIds().getFirst(),
                new WorkplaceDtos.VersionRequest(1L));
        assertThat(itemState(fixture.itemIds().getFirst())).isEqualTo("COMPENSATED");
        assertThat(batchState(fixture)).isEqualTo("COMPENSATED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND event_type = 'BookingBatchCompleted'
                """, Long.class, fixture.tenant(), fixture.batchId())).isEqualTo(1L);
    }

    @Test
    void compensationOwnerFailureDoesNotLeavePendingState() {
        Fixture fixture = fixture(1, "KEEP_SUCCEEDED");
        seedSucceeded(fixture, 0, UUID.randomUUID());
        jdbc.update("""
                UPDATE wp_booking_batches SET batch_state = 'COMPENSATING'
                 WHERE tenant_id = ? AND batch_id = ?
                """, fixture.tenant(), fixture.batchId());
        WorkplaceService workplace = mock(WorkplaceService.class);
        doThrow(new RuntimeException("cancel reply lost")).when(workplace).cancelBooking(
                anyLong(), anyLong(), any(), anyString(), anyString(), any(),
                anyString(), any());

        executor(mock(WorkplaceOperationsService.class), workplace).compensateSelected(
                fixture.tenant(), ACTOR, fixture.batchId(),
                Set.of(fixture.itemIds().getFirst()), "en", null);

        assertThat(itemState(fixture.itemIds().getFirst())).isEqualTo("COMPENSATION_FAILED");
        assertThat(batchState(fixture)).isEqualTo("RESULT_UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_batch_items
                 WHERE tenant_id = ? AND batch_id = ?
                   AND item_state = 'COMPENSATION_PENDING'
                """, Long.class, fixture.tenant(), fixture.batchId())).isZero();
    }

    private static Owner ownerReturningBookings() {
        WorkplaceOperationsService operations = mock(WorkplaceOperationsService.class);
        when(operations.createBooking(
                anyLong(), anyLong(), any(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenAnswer(invocation -> {
                    WorkplaceDtos.BookingRequest request = invocation.getArgument(8);
                    return booking(UUID.randomUUID(), request);
                });
        return new Owner(operations,
                executor(operations, mock(WorkplaceService.class)));
    }

    private static WorkplaceBookingBatchExecutor executor(
            WorkplaceOperationsService operations,
            WorkplaceService workplace) {
        return new WorkplaceBookingBatchExecutor(
                repository, operations, workplace, mock(RoomService.class), transactions,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private static WorkplaceDtos.Booking booking(
            UUID bookingId, WorkplaceDtos.BookingRequest request) {
        return new WorkplaceDtos.Booking(
                bookingId, request.resourceId(), "Desk", ResourceType.DESK,
                "Seoul", "10F", request.purpose(), request.startsAt(), request.endsAt(),
                BookingStatus.RESERVED, request.visibleToColleagues(), null, null,
                false, true, true, null, null, 1L);
    }

    private static Fixture fixture(int itemCount, String failurePolicy) {
        long tenant = TENANTS.incrementAndGet();
        UUID site = UUID.randomUUID();
        UUID floor = UUID.randomUUID();
        UUID intent = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        String correlation = "corr-" + batch;
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
                    data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Batch recovery test', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenant, "batch-recovery-" + tenant, ACTOR, ACTOR);
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
                INSERT INTO wp_booking_intents (
                    intent_id, tenant_id, actor_user_id, intent_state, reason,
                    requested_hold_ttl_seconds, allow_alternatives, idempotency_key,
                    request_fingerprint, correlation_id, version)
                VALUES (?, ?, ?, 'CONFIRMING', 'Fixture', 120, TRUE, ?, ?, ?, 1)
                """, intent, tenant, ACTOR, "intent-" + intent,
                "a".repeat(64), correlation);
        jdbc.update("""
                INSERT INTO wp_booking_batches (
                    batch_id, tenant_id, intent_id, actor_user_id, batch_state,
                    failure_policy, reason, explicit_confirmation, idempotency_key,
                    request_fingerprint, correlation_id, version)
                VALUES (?, ?, ?, ?, 'ACCEPTED', ?, 'Fixture', TRUE, ?, ?, ?, 1)
                """, batch, tenant, intent, ACTOR, failurePolicy,
                "batch-" + batch, "b".repeat(64), correlation);
        List<UUID> itemIds = new ArrayList<>();
        List<UUID> holdIds = new ArrayList<>();
        for (int index = 0; index < itemCount; index++) {
            UUID resource = UUID.randomUUID();
            UUID item = UUID.randomUUID();
            UUID hold = UUID.randomUUID();
            UUID batchItem = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO wp_resources (
                        resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
                        resource_type, neighborhood, position_x, position_y,
                        features, lifecycle_state, booking_mode)
                    VALUES (?, ?, ?, ?, '집중 좌석', 'Focus desk', 'DESK', 'ALPHA', 5, 5,
                            '[]'::jsonb, 'AVAILABLE', 'RESERVABLE')
                    """, resource, tenant, floor, "DESK_" + resource);
            jdbc.update("""
                    INSERT INTO wp_booking_intent_items (
                        intent_item_id, intent_id, tenant_id, client_item_key, actor_user_id,
                        beneficiary_user_id, beneficiary_display_name, resource_type,
                        preferred_resource_id, site_id, floor_id, starts_at, ends_at,
                        purpose, visible_to_colleagues, accessible_only, required_features,
                        decision, decision_code, candidate_resource_ids, version)
                    VALUES (?, ?, ?, ?, ?, ?, 'Member', 'DESK', ?, ?, ?, ?, ?, 'Work', TRUE,
                            FALSE, '[]'::jsonb, 'AVAILABLE', 'AVAILABLE', ?::jsonb, 1)
                    """, item, intent, tenant, "item-" + index, ACTOR, ACTOR,
                    resource, site, floor, NOW.plusHours(2), NOW.plusHours(3),
                    "[\"" + resource + "\"]");
            jdbc.update("""
                    INSERT INTO wp_reservation_holds (
                        hold_id, tenant_id, intent_id, intent_item_id, resource_id,
                        actor_user_id, beneficiary_user_id, hold_state, starts_at, ends_at,
                        expires_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'BATCHED', ?, ?, ?, 2)
                    """, hold, tenant, intent, item, resource, ACTOR, ACTOR,
                    NOW.plusHours(2), NOW.plusHours(3), NOW.plusMinutes(30));
            jdbc.update("""
                    INSERT INTO wp_booking_batch_items (
                        batch_item_id, batch_id, tenant_id, intent_id, intent_item_id, hold_id,
                        authority, item_state, compensation_available, requery_required, version)
                    VALUES (?, ?, ?, ?, ?, ?, 'WORKPLACE', 'PENDING', FALSE, FALSE, 1)
                    """, batchItem, batch, tenant, intent, item, hold);
            itemIds.add(batchItem);
            holdIds.add(hold);
        }
        return new Fixture(tenant, batch, correlation, itemIds, holdIds);
    }

    private static void seedSucceeded(Fixture fixture, int index, UUID ownerReference) {
        jdbc.update("""
                UPDATE wp_booking_batch_items
                   SET item_state = 'SUCCEEDED', owner_reference_id = ?, owner_version = 1,
                       compensation_available = TRUE, version = version + 1
                 WHERE tenant_id = ? AND batch_item_id = ?
                """, ownerReference, fixture.tenant(), fixture.itemIds().get(index));
        jdbc.update("""
                UPDATE wp_reservation_holds SET hold_state = 'CONSUMED', version = version + 1
                 WHERE tenant_id = ? AND hold_id = ?
                """, fixture.tenant(), fixture.holdIds().get(index));
    }

    private static String batchState(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT batch_state FROM wp_booking_batches
                 WHERE tenant_id = ? AND batch_id = ?
                """, String.class, fixture.tenant(), fixture.batchId());
    }

    private static String itemState(UUID itemId) {
        return jdbc.queryForObject("""
                SELECT item_state FROM wp_booking_batch_items WHERE batch_item_id = ?
                """, String.class, itemId);
    }

    private static int attempts(Fixture fixture) {
        Integer value = jdbc.queryForObject("""
                SELECT execution_attempt_count FROM wp_booking_batches
                 WHERE tenant_id = ? AND batch_id = ?
                """, Integer.class, fixture.tenant(), fixture.batchId());
        return value == null ? 0 : value;
    }

    private static String commandKey(UUID itemId) {
        return "booking-batch-item:" + itemId;
    }

    private record Fixture(
            long tenant, UUID batchId, String correlationId,
            List<UUID> itemIds, List<UUID> holdIds) { }
    private record Owner(
            WorkplaceOperationsService operations,
            WorkplaceBookingBatchExecutor executor) { }
}
