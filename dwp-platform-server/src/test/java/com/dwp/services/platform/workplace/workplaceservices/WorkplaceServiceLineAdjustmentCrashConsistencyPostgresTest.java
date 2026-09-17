package com.dwp.services.platform.workplace.workplaceservices;

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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceServiceLineAdjustmentCrashConsistencyPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-17T00:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 91_001L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static WorkplaceServicesRepository repository;
    private static WorkplaceServiceLineAdjustmentTransactionStore transactionStore;
    private static ObjectMapper mapper;

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
        mapper = new ObjectMapper().findAndRegisterModules();
        repository = new WorkplaceServicesRepository(jdbc, mapper);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        transactionStore = new WorkplaceServiceLineAdjustmentTransactionStore(jdbc, transactions);
    }

    @Test
    void crashAfterDurableAcceptanceRecoversByLookupWithoutRepeatingProviderMutation() {
        Fixture fixture = fixture("CRASH_WINDOW");
        CrashAwareProvider provider = new CrashAwareProvider(jdbc);
        provider.cancelFailure.set(new SimulatedProcessCrash());
        WorkplaceServiceLineAdjustmentService service = service(provider);
        LineCancellationImpact preview = preview(service, fixture, "Crash after provider dispatch");
        LineCancellationRequest request = request(preview);

        assertThatThrownBy(() -> service.cancel(
                fixture.tenantId(), ACTOR, fixture.orderId(), fixture.lineId(),
                "crash-window-key", request, "corr-crash"))
                .isInstanceOf(SimulatedProcessCrash.class);

        assertThat(provider.stateObservedBeforeCancel).isTrue();
        assertThat(adjustmentState(fixture)).isEqualTo("CANCELLATION_PENDING");
        assertThat(commandState("crash-window-key")).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("""
                SELECT origin_command_id IS NOT NULL
                   AND provider_operation_id = line_adjustment_id
                  FROM wp_service_line_adjustments
                 WHERE tenant_id = ? AND cancellation_preview_id = ?
                """, Boolean.class, fixture.tenantId(), preview.cancellationPreviewId())).isTrue();

        provider.cancelFailure.set(null);
        provider.lookupOutcome.set(success(preview, "provider-crash-recovered"));
        LineAdjustmentCommandResult recovered = service.cancel(
                fixture.tenantId(), ACTOR, fixture.orderId(), fixture.lineId(),
                "crash-window-key", request, "ignored");

        assertThat(recovered.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(recovered.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(recovered.receipt().replayed()).isTrue();
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(1);
        assertThat(cancelledQuantity(fixture)).isEqualTo(1);

        service.cancel(fixture.tenantId(), ACTOR, fixture.orderId(), fixture.lineId(),
                "crash-window-key", request, "ignored-again");
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(1);
    }

    @Test
    void timeoutBecomesReconciliationPendingAndAdminReconcileIsGetOnlyAndIdempotent() {
        Fixture fixture = fixture("TIMEOUT_RECOVERY");
        CrashAwareProvider provider = new CrashAwareProvider(jdbc);
        provider.cancelFailure.set(new OutcomeUncertainException(
                "provider-timeout", "Provider accepted but the response was lost", null));
        WorkplaceServiceLineAdjustmentService service = service(provider);
        LineCancellationImpact preview = preview(service, fixture, "Timeout recovery");
        LineCancellationRequest request = request(preview);

        LineAdjustmentCommandResult unknown = service.cancel(
                fixture.tenantId(), ACTOR, fixture.orderId(), fixture.lineId(),
                "timeout-key", request, "corr-timeout");
        assertThat(unknown.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);
        assertThat(unknown.receipt().state()).isEqualTo(CommandState.RESULT_UNKNOWN);
        assertThat(cancelledQuantity(fixture)).isZero();

        provider.cancelFailure.set(null);
        provider.lookupOutcome.set(success(preview, "provider-timeout"));
        long pendingVersion = service.adminStatus(
                fixture.tenantId(), fixture.orderId(),
                unknown.adjustment().lineAdjustmentId()).version();
        LineAdjustmentReconcileRequest reconcile = new LineAdjustmentReconcileRequest(
                pendingVersion, true, "Read provider truth and recover locally");
        LineAdjustmentCommandResult recovered = service.reconcile(
                fixture.tenantId(), ACTOR + 1, fixture.orderId(),
                unknown.adjustment().lineAdjustmentId(), "reconcile-timeout-key",
                reconcile, "corr-reconcile");

        assertThat(recovered.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(recovered.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(1);
        assertThat(provider.legacyReconcileCalls).hasValue(0);
        assertThat(cancelledQuantity(fixture)).isEqualTo(1);

        LineAdjustmentCommandResult replay = service.reconcile(
                fixture.tenantId(), ACTOR + 1, fixture.orderId(),
                unknown.adjustment().lineAdjustmentId(), "reconcile-timeout-key",
                reconcile, "ignored");
        assertThat(replay.receipt().replayed()).isTrue();
        assertThat(provider.lookupCalls).hasValue(1);
    }

    @Test
    void migrationPublishesPendingStatesAndEnforcesOriginCommandForeignKey() {
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version = '260'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                 WHERE conname IN ('fk_wp_service_line_adjustment_origin_command',
                    'uq_wp_service_line_adjustment_provider_operation')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void boundedGlobalWorkerRecoversPendingAdjustmentByLookupAfterRestart() {
        Fixture fixture = fixture("WORKER_RESTART");
        CrashAwareProvider provider = new CrashAwareProvider(jdbc);
        provider.cancelFailure.set(new OutcomeUncertainException(
                "provider-worker-restart", "Provider response was lost", null));
        WorkplaceServiceLineAdjustmentService firstProcess = service(provider);
        LineCancellationImpact preview = preview(
                firstProcess, fixture, "Recover after process restart");
        LineAdjustmentCommandResult unknown = firstProcess.cancel(
                fixture.tenantId(), ACTOR, fixture.orderId(), fixture.lineId(),
                "worker-restart-key", request(preview), "corr-worker-restart");
        assertThat(unknown.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);

        provider.cancelFailure.set(null);
        provider.lookupOutcome.set(success(preview, "provider-worker-restart"));
        WorkplaceServiceLineAdjustmentService restarted = service(provider);
        WorkplaceServiceOperationsRepository operations =
                mock(WorkplaceServiceOperationsRepository.class);
        when(operations.pendingAccessGrants(anyInt())).thenReturn(List.of());
        WorkplaceServiceProviderRecoveryWorker worker =
                new WorkplaceServiceProviderRecoveryWorker(repository, operations, restarted,
                        mock(WorkplaceServiceOperationsService.class), true, 10);

        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(restarted.adminStatus(fixture.tenantId(), fixture.orderId(),
                unknown.adjustment().lineAdjustmentId()).state())
                .isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(1);
        assertThat(worker.processPending()).isZero();
    }

    @Test
    void unknownRecoveryAdvancesPersistedBackoffSoLaterAdjustmentsAreNotStarved() {
        Fixture first = fixture("FAIR_RECOVERY_FIRST");
        Fixture second = fixture("FAIR_RECOVERY_SECOND");
        CrashAwareProvider provider = new CrashAwareProvider(jdbc);
        provider.cancelFailure.set(new OutcomeUncertainException(
                "provider-fairness", "Provider response was lost", null));
        WorkplaceServiceLineAdjustmentService service = service(provider);
        LineCancellationImpact firstPreview = preview(service, first, "First pending lookup");
        LineCancellationImpact secondPreview = preview(service, second, "Second pending lookup");
        service.cancel(first.tenantId(), ACTOR, first.orderId(), first.lineId(),
                "fair-first", request(firstPreview), "corr-fair-first");
        service.cancel(second.tenantId(), ACTOR, second.orderId(), second.lineId(),
                "fair-second", request(secondPreview), "corr-fair-second");

        provider.cancelFailure.set(null);
        provider.lookupOutcome.set(new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                "provider-pending", BigDecimal.ZERO, null, "Provider is still pending"));
        WorkplaceServiceOperationsRepository operations =
                mock(WorkplaceServiceOperationsRepository.class);
        when(operations.pendingAccessGrants(anyInt())).thenReturn(List.of());
        WorkplaceServiceProviderRecoveryWorker worker =
                new WorkplaceServiceProviderRecoveryWorker(repository, operations, service,
                        mock(WorkplaceServiceOperationsService.class), true, 1);

        assertThat(worker.processPending()).isOne();
        assertThat(worker.processPending()).isOne();

        assertThat(provider.lookupCalls).hasValue(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_line_adjustments
                 WHERE tenant_id IN (?, ?)
                   AND provider_recovery_attempt_count = 1
                   AND provider_next_attempt_at > CURRENT_TIMESTAMP
                """, Integer.class, first.tenantId(), second.tenantId())).isEqualTo(2);
    }

    private static WorkplaceServiceLineAdjustmentService service(
            WorkplaceServiceLineAdjustmentProvider provider) {
        return new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                transactionStore, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static LineCancellationImpact preview(
            WorkplaceServiceLineAdjustmentService service, Fixture fixture, String reason) {
        return service.preview(fixture.tenantId(), ACTOR, fixture.orderId(), fixture.lineId(),
                new LineCancellationImpactRequest(1, 1, 1, reason));
    }

    private static LineCancellationRequest request(LineCancellationImpact preview) {
        return new LineCancellationRequest(preview.cancellationPreviewId(),
                preview.orderVersion(), preview.lineVersion(), true, preview.reason());
    }

    private static ProviderOutcome success(LineCancellationImpact preview, String reference) {
        return new ProviderOutcome(OutcomeState.SUCCEEDED, reference,
                preview.refundableAmount(), "receipt-" + reference, "Recovered by GET lookup");
    }

    private static String adjustmentState(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT adjustment_state FROM wp_service_line_adjustments
                 WHERE tenant_id = ? AND service_order_id = ?
                """, String.class, fixture.tenantId(), fixture.orderId());
    }

    private static String commandState(String idempotencyKey) {
        return jdbc.queryForObject("""
                SELECT command_state FROM wp_service_order_commands
                 WHERE idempotency_key = ?
                """, String.class, idempotencyKey);
    }

    private static int cancelledQuantity(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT cancelled_quantity FROM wp_service_order_lines
                 WHERE tenant_id = ? AND service_order_line_id = ?
                """, Integer.class, fixture.tenantId(), fixture.lineId());
    }

    private static Fixture fixture(String suffix) {
        long tenantId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L)
                + 2_000_000_000L;
        UUID catalogId = UUID.randomUUID();
        UUID orderPreviewId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_service_provider_truth (
                    tenant_id, provider_code, configured, configuration_version,
                    observed_configuration_version, reported_state, evidence_reference,
                    observed_at, received_at, version, updated_at)
                VALUES (?, 'EXTERNAL_TEST', true, 1, 1, 'HEALTHY', 'test-evidence',
                        ?, ?, 1, ?)
                """, tenantId, NOW, NOW, NOW);
        jdbc.update("""
                INSERT INTO wp_service_catalog_items (
                    catalog_item_id, tenant_id, service_code, category, name_ko, name_en,
                    provider_code, site_scope, option_schema, supported_resource_types,
                    unit_price, currency, minimum_quantity, maximum_quantity,
                    order_cutoff_minutes, cancellation_cutoff_minutes,
                    cancellation_policy_ko, cancellation_policy_en,
                    sla_response_minutes, sla_fulfillment_lead_minutes,
                    lifecycle_state, version, created_at, updated_at)
                VALUES (?, ?, ?, 'AV', '테스트', 'Test', 'EXTERNAL_TEST', '[]', '[]', '[]',
                        100.00, 'KRW', 1, 10, 0, 60, '취소 정책', 'Cancellation policy',
                        30, 30, 'ACTIVE', 1, ?, ?)
                """, catalogId, tenantId, suffix, NOW, NOW);
        jdbc.update("""
                INSERT INTO wp_service_order_previews (
                    preview_id, tenant_id, actor_user_id, reservation_authority,
                    reservation_id, reservation_version, reservation_starts_at,
                    reservation_ends_at, attendee_count, estimated_cost, currency,
                    eligible, limitations, request_snapshot, expires_at, created_at)
                VALUES (?, ?, ?, 'WORKPLACE', ?, 1, ?, ?, 1, 100.00, 'KRW',
                        true, '[]', '{}', ?, ?)
                """, orderPreviewId, tenantId, ACTOR, reservationId,
                NOW.plusDays(1), NOW.plusDays(1).plusHours(1), NOW.plusMinutes(10), NOW);
        jdbc.update("""
                INSERT INTO wp_service_orders (
                    service_order_id, tenant_id, requester_user_id, preview_id,
                    reservation_authority, reservation_id, reservation_version,
                    reservation_starts_at, reservation_ends_at, attendee_count,
                    estimated_cost, currency, order_state, reservation_impact,
                    reconfirmation_required, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'WORKPLACE', ?, 1, ?, ?, 1, 100.00, 'KRW',
                        'SUBMITTED', 'NONE', false, 1, ?, ?)
                """, orderId, tenantId, ACTOR, orderPreviewId, reservationId,
                NOW.plusDays(1), NOW.plusDays(1).plusHours(1), NOW, NOW);
        jdbc.update("""
                INSERT INTO wp_service_order_lines (
                    service_order_line_id, tenant_id, service_order_id, catalog_item_id,
                    service_code, category, name_ko, name_en, provider_code, quantity,
                    options, unit_price, estimated_cost, line_state, fulfilled_quantity,
                    version, catalog_version, provider_configuration_version, currency,
                    site_scope_snapshot, supported_resource_types_snapshot,
                    option_schema_snapshot, minimum_quantity, maximum_quantity,
                    order_cutoff_minutes, inspection_mode, inspection_checklist_schema,
                    cancellation_cutoff_minutes, cancellation_policy_ko,
                    cancellation_policy_en, sla_response_minutes,
                    sla_fulfillment_lead_minutes, cancelled_quantity, refunded_amount,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'AV', '테스트', 'Test', 'EXTERNAL_TEST', 1,
                        '{}', 100.00, 100.00, 'SUBMITTED', 0, 1, 1, 1, 'KRW',
                        '[]', '[]', '[]', 1, 10, 0, 'NONE', '[]', 60,
                        '취소 정책', 'Cancellation policy', 30, 30, 0, 0, ?, ?)
                """, lineId, tenantId, orderId, catalogId, suffix, NOW, NOW);
        return new Fixture(tenantId, orderId, lineId);
    }

    private record Fixture(long tenantId, UUID orderId, UUID lineId) { }

    private static final class CrashAwareProvider implements WorkplaceServiceLineAdjustmentProvider {
        private final JdbcTemplate jdbc;
        private final AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        private final AtomicReference<ProviderOutcome> lookupOutcome = new AtomicReference<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger lookupCalls = new AtomicInteger();
        private final AtomicInteger legacyReconcileCalls = new AtomicInteger();
        private volatile boolean stateObservedBeforeCancel;

        private CrashAwareProvider(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public ProviderOutcome cancel(ProviderRequest request) {
            cancelCalls.incrementAndGet();
            stateObservedBeforeCancel = "CANCELLATION_PENDING".equals(jdbc.queryForObject("""
                    SELECT adjustment_state FROM wp_service_line_adjustments
                     WHERE tenant_id = ? AND provider_operation_id = ?
                    """, String.class, request.tenantId(), request.operationId()));
            Throwable failure = cancelFailure.get();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            return lookupOutcome.get();
        }

        @Override
        public ProviderOutcome lookup(ProviderRequest request, String providerOperationReference) {
            lookupCalls.incrementAndGet();
            return lookupOutcome.get();
        }

        @Override
        public ProviderOutcome reconcile(
                ProviderRequest request, String providerOperationReference) {
            legacyReconcileCalls.incrementAndGet();
            throw new AssertionError("Recovery must use the GET-only lookup contract.");
        }
    }

    private static final class SimulatedProcessCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
