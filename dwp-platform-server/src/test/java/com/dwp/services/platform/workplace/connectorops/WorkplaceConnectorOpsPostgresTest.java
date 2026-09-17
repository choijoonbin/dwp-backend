package com.dwp.services.platform.workplace.connectorops;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class WorkplaceConnectorOpsPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T03:00:00Z");
    private static final long ACTOR = 99001;
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static WorkplaceConnectorOpsService service;
    private static WorkplaceConnectorReplayCoordinator coordinator;
    private static PlatformTransactionManager transactionManager;

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
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        transactionManager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(transactionManager);
        WorkplaceConnectorOpsRepository repository = new WorkplaceConnectorOpsRepository(jdbc, mapper);
        WorkplaceSpatialGovernanceRepository audits =
                new WorkplaceSpatialGovernanceRepository(jdbc, mapper);
        TestReplayAdapter adapter = new TestReplayAdapter();
        service = new WorkplaceConnectorOpsService(repository, audits, mapper,
                List.of(adapter), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofDays(90),
                Clock.fixed(FIXED, ZoneOffset.UTC));
        coordinator = new WorkplaceConnectorReplayCoordinator(repository, audits, mapper,
                List.of(adapter), transactionManager, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    void configuredConnectorNeedsMatchingFreshProviderTruthBeforeHealthy() {
        long tenant = 9256001;
        fixture(tenant, "msgraph");

        assertThat(service.detail(tenant, ConnectorKind.CALENDAR).state())
                .isEqualTo(RuntimeState.CONFIGURED_UNVERIFIED);
        OffsetDateTime fresh = OffsetDateTime.now(Clock.fixed(FIXED, ZoneOffset.UTC));
        transaction.executeWithoutResult(status -> service.observeProvider(observation(
                tenant, 1, 0, fresh, ProviderReportedState.HEALTHY,
                "other-provider", "urn:dwp:provider:workplace-connector:other-provider")));
        assertThat(service.detail(tenant, ConnectorKind.CALENDAR).state())
                .isEqualTo(RuntimeState.CONFIGURED_UNVERIFIED);
        observe(tenant, 2, 99, fresh,
                ProviderReportedState.HEALTHY);
        assertThat(service.detail(tenant, ConnectorKind.CALENDAR).state())
                .isEqualTo(RuntimeState.CONFIGURED_UNVERIFIED);

        observe(tenant, 3, 0, fresh,
                ProviderReportedState.HEALTHY);
        ConnectorRuntimeTruth healthy = service.detail(tenant, ConnectorKind.CALENDAR);
        assertThat(healthy.state()).isEqualTo(RuntimeState.HEALTHY);
        assertThat(healthy.capabilities()).contains(Capability.REPLAY);

        observe(tenant, 4, 0,
                OffsetDateTime.now(Clock.fixed(FIXED.minus(Duration.ofMinutes(6)), ZoneOffset.UTC)),
                ProviderReportedState.HEALTHY);
        assertThat(service.detail(tenant, ConnectorKind.CALENDAR).state())
                .isEqualTo(RuntimeState.STALE);
        assertThat(service.detail(9256099, ConnectorKind.CALENDAR).state())
                .isEqualTo(RuntimeState.NOT_CONFIGURED);
    }

    @Test
    void replayIsTenantScopedIdempotentAuditedAndProjectsOutboxAtomically() {
        long tenant = 9256002;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        ReplayPreview preview = transaction.execute(status -> service.preview(tenant, ACTOR,
                ConnectorKind.CALENDAR, "preview-9256002",
                new ReplayPreviewRequest(now.minusHours(1), now, true, 200, 0, 1),
                "corr-preview-9256002"));
        ReplayStartRequest request = new ReplayStartRequest(
                preview.previewId(), 0, 1, "Recover failed calendar observations", true);

        ReplayStartResponse first = transaction.execute(status -> service.startReplay(
                tenant, ACTOR, ConnectorKind.CALENDAR, "replay-9256002", request, "corr-9256002"));
        ReplayStartResponse duplicate = transaction.execute(status -> service.startReplay(
                tenant, ACTOR, ConnectorKind.CALENDAR, "replay-9256002", request, "ignored"));

        assertThat(first.job().jobId()).isEqualTo(duplicate.job().jobId());
        assertThat(first.receipt().idempotentReplay()).isFalse();
        assertThat(duplicate.receipt().idempotentReplay()).isTrue();
        assertThat(service.replay(tenant, ConnectorKind.CALENDAR, first.job().jobId()).state())
                .isEqualTo(ReplayState.QUEUED);
        assertThatThrownBy(() -> service.replay(
                9256099, ConnectorKind.CALENDAR, first.job().jobId()))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> transaction.execute(status -> service.startReplay(
                tenant, ACTOR, ConnectorKind.CALENDAR, "replay-9256002",
                new ReplayStartRequest(preview.previewId(), 0, 1, "Different command", true),
                "corr-other")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        assertThat(count("wp_connector_replay_jobs", tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.connector.replay.started'
                """, Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_platform_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.connector.replay.started'
                """, Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox o
                  JOIN sys_platform_audit_events e ON e.audit_event_id = o.event_id
                 WHERE e.tenant_id = ? AND e.action = 'workplace.connector.replay.started'
                """, Long.class, tenant)).isEqualTo(1);

        jdbc.update("""
                UPDATE wp_connector_replay_jobs
                   SET replay_state = 'FAILED', result_summary = 'TEST_TERMINAL',
                       finished_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND replay_job_id = ?
                """, tenant, first.job().jobId());

        ReplayPreview rollbackPreview = transaction.execute(status -> service.preview(tenant, ACTOR,
                ConnectorKind.CALENDAR, "preview-rollback-9256002",
                new ReplayPreviewRequest(now.minusHours(2), now.minusHours(1), false, 50, 0, 1),
                "corr-preview-rollback"));
        assertThatThrownBy(() -> transaction.execute(status -> {
            service.startReplay(tenant, ACTOR, ConnectorKind.CALENDAR, "rollback-9256002",
                    new ReplayStartRequest(rollbackPreview.previewId(), 0, 1, "Rollback proof", true),
                    "corr-rollback");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("wp_connector_replay_jobs", tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND correlation_id = 'corr-rollback'
                """, Long.class, tenant)).isZero();
    }

    @Test
    void uncertainProviderDispatchPersistsResultUnknownForStatusRequery() {
        long tenant = 9256003;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        ReplayPreview preview = transaction.execute(status -> service.preview(tenant, ACTOR,
                ConnectorKind.CALENDAR, "preview-unknown-9256003",
                new ReplayPreviewRequest(now.minusMinutes(30), now, false, 100, 0, 1),
                "corr-preview-unknown"));
        ReplayStartResponse started = transaction.execute(status -> service.startReplay(
                tenant, ACTOR, ConnectorKind.CALENDAR, "unknown-9256003",
                new ReplayStartRequest(preview.previewId(), 0, 1, "Verify uncertain result", true),
                "corr-unknown"));

        ReplayJob dispatched = coordinator.dispatch(
                tenant, ConnectorKind.CALENDAR, started.job().jobId());
        assertThat(dispatched.state()).isEqualTo(ReplayState.RESULT_UNKNOWN);
        assertThat(service.replay(tenant, ConnectorKind.CALENDAR, dispatched.jobId()))
                .extracting(ReplayJob::state, ReplayJob::resultSummary)
                .containsExactly(ReplayState.RESULT_UNKNOWN,
                        "PROVIDER_DISPATCH_OUTCOME_UNKNOWN");
    }

    @Test
    void providerSourceMustOwnObservation() {
        long tenant = 9256004;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        ProviderObservation forged = observation(tenant, 1, 0, now,
                ProviderReportedState.HEALTHY, "urn:dwp:provider:workplace-connector:forged");
        assertThatThrownBy(() -> service.observeProvider(forged))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(count("wp_connector_runtime_observations", tenant)).isZero();
    }

    @Test
    void replayPreviewReturnsExactPersistedResultAfterServiceRestart() {
        long tenant = 9256005;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        AtomicInteger providerCalls = new AtomicInteger();
        WorkplaceConnectorOpsService firstProcess = previewService(
                new CountingReplayAdapter(providerCalls, Duration.ZERO));
        ReplayPreviewRequest request = new ReplayPreviewRequest(
                now.minusHours(1), now, true, 250, 0, 1);

        ReplayPreview first = transaction.execute(status -> firstProcess.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "preview-restart-9256005",
                request, "corr-preview-first"));
        WorkplaceConnectorOpsService restartedProcess = previewService(
                new CountingReplayAdapter(providerCalls, Duration.ZERO));
        ReplayPreview replayed = transaction.execute(status -> restartedProcess.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "preview-restart-9256005",
                request, "corr-preview-retry"));

        assertThat(replayed).isEqualTo(first);
        assertThat(providerCalls).hasValue(1);
        assertThat(count("wp_connector_replay_previews", tenant)).isEqualTo(1);
        assertThat(count("wp_connector_replay_preview_commands", tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.connector.replay.previewed'
                """, Long.class, tenant)).isEqualTo(1);

        assertThatThrownBy(() -> transaction.execute(status -> restartedProcess.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "preview-restart-9256005",
                new ReplayPreviewRequest(now.minusMinutes(30), now, true, 250, 0, 1),
                "corr-preview-conflict")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> transaction.execute(status -> restartedProcess.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "non ascii key 한글",
                request, "corr-preview-invalid")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void concurrentReplayPreviewRetriesSerializeBeforeProviderEvaluation() throws Exception {
        long tenant = 9256006;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        AtomicInteger providerCalls = new AtomicInteger();
        WorkplaceConnectorOpsService concurrentService = previewService(
                new CountingReplayAdapter(providerCalls, Duration.ofMillis(150)));
        ReplayPreviewRequest request = new ReplayPreviewRequest(
                now.minusHours(2), now.minusHours(1), false, 400, 0, 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ReplayPreview> first = workers.submit(() -> {
                start.await();
                return transaction.execute(status -> concurrentService.preview(
                        tenant, ACTOR, ConnectorKind.CALENDAR, "preview-concurrent-9256006",
                        request, "corr-preview-concurrent-a"));
            });
            Future<ReplayPreview> second = workers.submit(() -> {
                start.await();
                return transaction.execute(status -> concurrentService.preview(
                        tenant, ACTOR, ConnectorKind.CALENDAR, "preview-concurrent-9256006",
                        request, "corr-preview-concurrent-b"));
            });
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).previewId())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).previewId());
        } finally {
            workers.shutdownNow();
        }
        assertThat(providerCalls).hasValue(1);
        assertThat(count("wp_connector_replay_previews", tenant)).isEqualTo(1);
        assertThat(count("wp_connector_replay_preview_commands", tenant)).isEqualTo(1);
        assertPreviewAuditEvidence(tenant, 1);
    }

    @Test
    void concurrentDifferentPreviewPayloadWithSameKeyHasOneWinner() throws Exception {
        long tenant = 9256007;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        AtomicInteger providerCalls = new AtomicInteger();
        WorkplaceConnectorOpsService concurrentService = previewService(
                new CountingReplayAdapter(providerCalls, Duration.ofMillis(150)));
        ReplayPreviewRequest firstRequest = new ReplayPreviewRequest(
                now.minusHours(2), now.minusHours(1), false, 400, 0, 1);
        ReplayPreviewRequest secondRequest = new ReplayPreviewRequest(
                now.minusHours(3), now.minusHours(1), true, 300, 0, 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = workers.submit(() -> previewOutcome(
                    concurrentService, tenant, "preview-conflict-9256007", firstRequest, start));
            Future<Object> second = workers.submit(() -> previewOutcome(
                    concurrentService, tenant, "preview-conflict-9256007", secondRequest, start));
            start.countDown();
            List<Object> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(ReplayPreview.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(BaseException.class::isInstance)
                    .map(BaseException.class::cast)
                    .map(BaseException::getErrorCode))
                    .containsExactly(ErrorCode.RESOURCE_CONFLICT);
        } finally {
            workers.shutdownNow();
        }
        assertThat(providerCalls).hasValue(1);
        assertThat(count("wp_connector_replay_previews", tenant)).isEqualTo(1);
        assertThat(count("wp_connector_replay_preview_commands", tenant)).isEqualTo(1);
        assertPreviewAuditEvidence(tenant, 1);
    }

    @Test
    void previewReceiptAndAuditRollBackWithTheTransaction() {
        long tenant = 9256008;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        WorkplaceConnectorOpsService rollbackService = previewService(new TestReplayAdapter());

        assertThatThrownBy(() -> transaction.execute(status -> {
            rollbackService.preview(tenant, ACTOR, ConnectorKind.CALENDAR,
                    "preview-rollback-9256008",
                    new ReplayPreviewRequest(now.minusHours(1), now, true, 200, 0, 1),
                    "corr-preview-rollback-9256008");
            throw new IllegalStateException("force preview rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("wp_connector_replay_previews", tenant)).isZero();
        assertThat(count("wp_connector_replay_preview_commands", tenant)).isZero();
        assertPreviewAuditEvidence(tenant, 0);
    }

    @Test
    void previewIdempotencyKeyAcceptsVisibleAsciiBoundaryOnly() {
        long tenant = 9256009;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        WorkplaceConnectorOpsService boundaryService = previewService(new TestReplayAdapter());
        ReplayPreviewRequest request = new ReplayPreviewRequest(
                now.minusHours(1), now, true, 200, 0, 1);

        ReplayPreview accepted = transaction.execute(status -> boundaryService.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "k".repeat(160), request,
                "corr-preview-boundary"));
        assertThat(accepted).isNotNull();
        for (String invalidKey : List.of("k".repeat(161), "contains space", "한글-key")) {
            assertThatThrownBy(() -> transaction.execute(status -> boundaryService.preview(
                    tenant, ACTOR, ConnectorKind.CALENDAR, invalidKey, request,
                    "corr-preview-invalid")))
                    .isInstanceOfSatisfying(BaseException.class,
                            error -> assertThat(error.getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        }
        assertThat(count("wp_connector_replay_previews", tenant)).isEqualTo(1);
        assertThat(count("wp_connector_replay_preview_commands", tenant)).isEqualTo(1);
        assertPreviewAuditEvidence(tenant, 1);
    }

    @Test
    void previewOwnershipIsBoundToTheActorWhoCreatedIt() {
        long tenant = 9256010;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        ReplayPreview preview = transaction.execute(status -> service.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "preview-owner-9256010",
                new ReplayPreviewRequest(now.minusHours(1), now, false, 10, 0, 1),
                "corr-preview-owner"));

        assertThatThrownBy(() -> transaction.execute(status -> service.startReplay(
                tenant, ACTOR + 1, ConnectorKind.CALENDAR, "replay-owner-9256010",
                new ReplayStartRequest(preview.previewId(), 0, 1,
                        "Actor ownership sentinel", true), "corr-owner")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(count("wp_connector_replay_jobs", tenant)).isZero();
    }

    @Test
    void differentActorsAndKeysStillHaveExactlyOneActiveReplay() throws Exception {
        long tenant = 9256011;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        ReplayPreview firstPreview = transaction.execute(status -> service.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "preview-actor-a-9256011",
                new ReplayPreviewRequest(now.minusHours(2), now.minusHours(1), false, 20, 0, 1),
                "corr-preview-a"));
        ReplayPreview secondPreview = transaction.execute(status -> service.preview(
                tenant, ACTOR + 1, ConnectorKind.CALENDAR, "preview-actor-b-9256011",
                new ReplayPreviewRequest(now.minusHours(2), now.minusHours(1), false, 20, 0, 1),
                "corr-preview-b"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = workers.submit(() -> replayOutcome(
                    tenant, ACTOR, "replay-actor-a-9256011", firstPreview, start));
            Future<Object> second = workers.submit(() -> replayOutcome(
                    tenant, ACTOR + 1, "replay-actor-b-9256011", secondPreview, start));
            start.countDown();
            List<Object> outcomes = List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(ReplayStartResponse.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(outcomes.stream().filter(BaseException.class::isInstance)
                    .map(BaseException.class::cast).map(BaseException::getErrorCode))
                    .containsExactly(ErrorCode.RESOURCE_CONFLICT);
        } finally {
            workers.shutdownNow();
        }
        assertThat(count("wp_connector_replay_jobs", tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                 WHERE indexname = 'uq_wp_connector_replay_single_active'
                """, Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_connector_replay_jobs (
                    replay_job_id, preview_id, tenant_id, connector_kind, provider,
                    configuration_version, runtime_version, replay_state, reason,
                    credential_reference, idempotency_key, request_fingerprint,
                    requested_by, correlation_id, sensitive_payload_expires_at,
                    version, requested_at, updated_at)
                SELECT ?, preview_id, tenant_id, connector_kind, provider,
                       configuration_version, runtime_version, 'QUEUED', 'Bypass attempt',
                       credential_reference, 'direct-bypass-9256011', request_fingerprint,
                       ?, 'corr-direct-bypass', sensitive_payload_expires_at,
                       1, requested_at + INTERVAL '1 second', updated_at
                  FROM wp_connector_replay_jobs
                 WHERE tenant_id = ?
                """, UUID.randomUUID(), ACTOR + 7, tenant))
                .hasMessageContaining("uq_wp_connector_replay_single_active");
    }

    @Test
    void dispatchCommitFailureRecoversByLookupAndTerminalAuditContainsNoFreeText() {
        long tenant = 9256012;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        RecoveringReplayAdapter adapter = new RecoveringReplayAdapter();
        WorkplaceConnectorOpsService candidate = previewService(adapter);
        WorkplaceConnectorReplayCoordinator firstProcess = replayCoordinator(adapter);
        ReplayPreview preview = transaction.execute(status -> candidate.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "preview-recovery-9256012",
                new ReplayPreviewRequest(now.minusHours(1), now, true, 50, 0, 1),
                "corr-preview-recovery"));
        String piiSentinel = "person@example.invalid medical-note";
        ReplayStartResponse started = transaction.execute(status -> candidate.startReplay(
                tenant, ACTOR, ConnectorKind.CALENDAR, "replay-recovery-9256012",
                new ReplayStartRequest(preview.previewId(), 0, 1, piiSentinel, true),
                "corr-recovery"));

        jdbc.execute("""
                CREATE FUNCTION reject_connector_finalize_9256012() RETURNS trigger AS $$
                BEGIN
                  IF OLD.replay_state = 'DISPATCHING' AND NEW.replay_state <> 'DISPATCHING' THEN
                    RAISE EXCEPTION 'simulated database finalize failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_connector_finalize_9256012
                BEFORE UPDATE ON wp_connector_replay_jobs
                FOR EACH ROW EXECUTE FUNCTION reject_connector_finalize_9256012()
                """);
        try {
            assertThatThrownBy(() -> firstProcess.dispatch(
                    tenant, ConnectorKind.CALENDAR, started.job().jobId()))
                    .hasMessageContaining("simulated database finalize failure");
        } finally {
            jdbc.execute("DROP TRIGGER reject_connector_finalize_9256012 ON wp_connector_replay_jobs");
            jdbc.execute("DROP FUNCTION reject_connector_finalize_9256012()");
        }
        assertThat(candidate.replay(tenant, ConnectorKind.CALENDAR, started.job().jobId()).state())
                .isEqualTo(ReplayState.DISPATCHING);

        WorkplaceConnectorReplayCoordinator restartedProcess = replayCoordinator(adapter);
        ReplayJob recovered = restartedProcess.reconcile(
                tenant, ConnectorKind.CALENDAR, started.job().jobId());
        assertThat(recovered.state()).isEqualTo(ReplayState.SUCCEEDED);
        assertThat(adapter.dispatchCalls).hasValue(1);
        assertThat(adapter.lookupCalls).hasValue(1);
        restartedProcess.reconcile(tenant, ConnectorKind.CALENDAR, started.job().jobId());
        assertThat(adapter.lookupCalls).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.connector.replay.terminal'
                """, Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_platform_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.connector.replay.terminal'
                """, Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox outbox
                  JOIN sys_platform_audit_events event
                    ON event.audit_event_id = outbox.event_id
                 WHERE event.tenant_id = ?
                   AND event.action = 'workplace.connector.replay.terminal'
                """, Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND snapshot::text LIKE ?
                """, Long.class, tenant, "%" + piiSentinel + "%")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_platform_audit_events
                 WHERE tenant_id = ? AND COALESCE(after_snapshot::text, '') LIKE ?
                """, Long.class, tenant, "%" + piiSentinel + "%")).isZero();

        jdbc.update("""
                UPDATE wp_connector_replay_jobs
                   SET sensitive_payload_expires_at = ?
                 WHERE tenant_id = ? AND replay_job_id = ?
                """, now, tenant, started.job().jobId());
        WorkplaceConnectorOpsRepository repository = new WorkplaceConnectorOpsRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        assertThat(repository.purgeExpiredSensitivePayloads(10, now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM wp_connector_replay_jobs
                 WHERE tenant_id = ? AND replay_job_id = ?
                """, String.class, tenant, started.job().jobId())).isEqualTo("[REDACTED]");
    }

    @Test
    void providerLookupFailureIsSanitizedAndDeferredWithoutFalseTerminalState() {
        long tenant = 9256013;
        fixture(tenant, "msgraph");
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        observe(tenant, 1, 0, now, ProviderReportedState.HEALTHY);
        String sensitiveProviderBody = "person@example.invalid bearer-secret-from-provider";
        LookupFailureReplayAdapter adapter = new LookupFailureReplayAdapter(sensitiveProviderBody);
        WorkplaceConnectorOpsService candidate = previewService(adapter);
        WorkplaceConnectorReplayCoordinator relay = replayCoordinator(adapter);
        ReplayPreview preview = transaction.execute(status -> candidate.preview(
                tenant, ACTOR, ConnectorKind.CALENDAR, "preview-lookup-failure-9256013",
                new ReplayPreviewRequest(now.minusHours(1), now, true, 25, 0, 1),
                "corr-preview-lookup-failure"));
        ReplayStartResponse started = transaction.execute(status -> candidate.startReplay(
                tenant, ACTOR, ConnectorKind.CALENDAR, "replay-lookup-failure-9256013",
                new ReplayStartRequest(preview.previewId(), 0, 1,
                        "Recover provider lookup", true), "corr-lookup-failure"));

        assertThat(relay.dispatch(tenant, ConnectorKind.CALENDAR, started.job().jobId()).state())
                .isEqualTo(ReplayState.RUNNING);
        ReplayJob deferred = relay.reconcile(
                tenant, ConnectorKind.CALENDAR, started.job().jobId());

        assertThat(deferred.state()).isEqualTo(ReplayState.RESULT_UNKNOWN);
        assertThat(deferred.resultSummary()).isEqualTo("PROVIDER_STATUS_UNAVAILABLE");
        assertThat(deferred.resultSummary()).doesNotContain(sensitiveProviderBody);
        assertThat(jdbc.queryForObject("""
                SELECT reconcile_attempt_count FROM wp_connector_replay_jobs
                 WHERE tenant_id=? AND replay_job_id=?
                """, Integer.class, tenant, started.job().jobId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT next_reconcile_at FROM wp_connector_replay_jobs
                 WHERE tenant_id=? AND replay_job_id=?
                """, OffsetDateTime.class, tenant, started.job().jobId()))
                .isEqualTo(now.plusSeconds(5));
        WorkplaceConnectorOpsRepository repository = new WorkplaceConnectorOpsRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        assertThat(repository.reconciliationCandidates(100, now)).noneMatch(
                row -> row.jobId().equals(started.job().jobId()));
        assertThat(repository.reconciliationCandidates(100, now.plusSeconds(5))).anyMatch(
                row -> row.jobId().equals(started.job().jobId()));
        assertThat(adapter.dispatchCalls).hasValue(1);
        assertThat(adapter.lookupCalls).hasValue(1);
    }

    private static Object replayOutcome(
            long tenant,
            long actor,
            String key,
            ReplayPreview preview,
            CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return transaction.execute(status -> service.startReplay(
                    tenant, actor, ConnectorKind.CALENDAR, key,
                    new ReplayStartRequest(preview.previewId(), 0, 1,
                            "Concurrent connector replay", true), "corr-" + key));
        } catch (BaseException conflict) {
            return conflict;
        }
    }

    private static Object previewOutcome(
            WorkplaceConnectorOpsService candidate,
            long tenant,
            String idempotencyKey,
            ReplayPreviewRequest request,
            CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return transaction.execute(status -> candidate.preview(
                    tenant, ACTOR, ConnectorKind.CALENDAR, idempotencyKey, request,
                    "corr-" + idempotencyKey));
        } catch (BaseException conflict) {
            return conflict;
        }
    }

    private static WorkplaceConnectorOpsService previewService(
            WorkplaceConnectorReplayAdapter adapter) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new WorkplaceConnectorOpsService(
                new WorkplaceConnectorOpsRepository(jdbc, mapper),
                new WorkplaceSpatialGovernanceRepository(jdbc, mapper), mapper,
                List.of(adapter), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofDays(90),
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static WorkplaceConnectorReplayCoordinator replayCoordinator(
            WorkplaceConnectorReplayAdapter adapter) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new WorkplaceConnectorReplayCoordinator(
                new WorkplaceConnectorOpsRepository(jdbc, mapper),
                new WorkplaceSpatialGovernanceRepository(jdbc, mapper), mapper,
                List.of(adapter), transactionManager, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static void fixture(long tenant, String provider) {
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
                    data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Connector runtime test', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenant, "connector-runtime-" + tenant, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id) VALUES (?)", tenant);
        jdbc.update("""
                INSERT INTO wp_experience_connector_configurations (
                    tenant_id, connector_kind, provider, enabled, configuration_reference,
                    version, updated_by)
                VALUES (?, 'CALENDAR', ?, TRUE, 'secret/ref', 0, ?)
                """, tenant, provider, ACTOR);
    }

    private static void observe(
            long tenant,
            long sequence,
            long configurationVersion,
            OffsetDateTime receivedAt,
            ProviderReportedState state) {
        transaction.executeWithoutResult(status -> service.observeProvider(observation(
                tenant, sequence, configurationVersion, receivedAt, state,
                "urn:dwp:provider:workplace-connector:msgraph")));
    }

    private static ProviderObservation observation(
            long tenant,
            long sequence,
            long configurationVersion,
            OffsetDateTime receivedAt,
            ProviderReportedState state,
            String source) {
        return observation(tenant, sequence, configurationVersion, receivedAt, state,
                "msgraph", source);
    }

    private static ProviderObservation observation(
            long tenant,
            long sequence,
            long configurationVersion,
            OffsetDateTime receivedAt,
            ProviderReportedState state,
            String provider,
            String source) {
        return new ProviderObservation(UUID.randomUUID(), tenant, ConnectorKind.CALENDAR,
                source, provider, configurationVersion, "calendar-adapter", "1.0.0", state,
                List.of(Capability.HEALTH, Capability.CHECKPOINT, Capability.REPLAY),
                receivedAt.minusSeconds(2), receivedAt, receivedAt.minusSeconds(3), 2L,
                "opaque-checkpoint", 0L, 0L, null, sequence,
                "sha256:1234567890abcdef");
    }

    private static long count(String table, long tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class, tenant);
    }

    private static void assertPreviewAuditEvidence(long tenant, long expected) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.connector.replay.previewed'
                """, Long.class, tenant)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_platform_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.connector.replay.previewed'
                """, Long.class, tenant)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox o
                  JOIN sys_platform_audit_events e ON e.audit_event_id = o.event_id
                 WHERE e.tenant_id = ? AND e.action = 'workplace.connector.replay.previewed'
                """, Long.class, tenant)).isEqualTo(expected);
    }

    private static final class TestReplayAdapter implements WorkplaceConnectorReplayAdapter {
        @Override
        public boolean supports(String provider, ConnectorKind kind) {
            return "msgraph".equals(provider) && kind == ConnectorKind.CALENDAR;
        }

        @Override
        public PreviewEstimate preview(long tenantId, ConnectorKind kind, String provider,
                OffsetDateTime from, OffsetDateTime to, boolean failedOnly, int maximumRecords) {
            return new PreviewEstimate(Math.min(23, maximumRecords), List.of());
        }

        @Override
        public DispatchResult dispatch(long tenantId, ConnectorKind kind, String provider,
                UUID jobId, UUID previewId, OffsetDateTime from, OffsetDateTime to,
                boolean failedOnly, int maximumRecords) {
            throw new IllegalStateException("provider timed out after accepting the command");
        }
    }

    private static final class CountingReplayAdapter implements WorkplaceConnectorReplayAdapter {
        private final AtomicInteger previewCalls;
        private final Duration delay;

        private CountingReplayAdapter(AtomicInteger previewCalls, Duration delay) {
            this.previewCalls = previewCalls;
            this.delay = delay;
        }

        @Override
        public boolean supports(String provider, ConnectorKind kind) {
            return "msgraph".equals(provider) && kind == ConnectorKind.CALENDAR;
        }

        @Override
        public PreviewEstimate preview(long tenantId, ConnectorKind kind, String provider,
                OffsetDateTime from, OffsetDateTime to, boolean failedOnly, int maximumRecords) {
            previewCalls.incrementAndGet();
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("preview test interrupted", interrupted);
                }
            }
            return new PreviewEstimate(Math.min(31, maximumRecords), List.of());
        }

        @Override
        public DispatchResult dispatch(long tenantId, ConnectorKind kind, String provider,
                UUID jobId, UUID previewId, OffsetDateTime from, OffsetDateTime to,
                boolean failedOnly, int maximumRecords) {
            throw new UnsupportedOperationException("dispatch is outside this preview test");
        }
    }

    private static final class RecoveringReplayAdapter implements WorkplaceConnectorReplayAdapter {
        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private final AtomicInteger lookupCalls = new AtomicInteger();

        @Override
        public boolean supports(String provider, ConnectorKind kind) {
            return "msgraph".equals(provider) && kind == ConnectorKind.CALENDAR;
        }

        @Override
        public PreviewEstimate preview(long tenantId, ConnectorKind kind, String provider,
                OffsetDateTime from, OffsetDateTime to, boolean failedOnly, int maximumRecords) {
            return new PreviewEstimate(Math.min(7, maximumRecords), List.of());
        }

        @Override
        public DispatchResult dispatch(long tenantId, ConnectorKind kind, String provider,
                UUID jobId, UUID previewId, OffsetDateTime from, OffsetDateTime to,
                boolean failedOnly, int maximumRecords) {
            dispatchCalls.incrementAndGet();
            return new DispatchResult(ReplayState.RUNNING, "provider-operation-9256012", "ACCEPTED");
        }

        @Override
        public LookupResult lookup(ProviderContext context, UUID jobId,
                String providerOperationReference) {
            lookupCalls.incrementAndGet();
            return new LookupResult(ReplayState.SUCCEEDED,
                    "provider-operation-9256012", "COMPLETED");
        }
    }

    private static final class LookupFailureReplayAdapter
            implements WorkplaceConnectorReplayAdapter {
        private final String sensitiveProviderBody;
        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private final AtomicInteger lookupCalls = new AtomicInteger();

        private LookupFailureReplayAdapter(String sensitiveProviderBody) {
            this.sensitiveProviderBody = sensitiveProviderBody;
        }

        @Override
        public boolean supports(String provider, ConnectorKind kind) {
            return "msgraph".equals(provider) && kind == ConnectorKind.CALENDAR;
        }

        @Override
        public PreviewEstimate preview(long tenantId, ConnectorKind kind, String provider,
                OffsetDateTime from, OffsetDateTime to, boolean failedOnly, int maximumRecords) {
            return new PreviewEstimate(Math.min(5, maximumRecords), List.of());
        }

        @Override
        public DispatchResult dispatch(long tenantId, ConnectorKind kind, String provider,
                UUID jobId, UUID previewId, OffsetDateTime from, OffsetDateTime to,
                boolean failedOnly, int maximumRecords) {
            dispatchCalls.incrementAndGet();
            return new DispatchResult(ReplayState.RUNNING, "provider-operation-sensitive",
                    "ACCEPTED");
        }

        @Override
        public LookupResult lookup(ProviderContext context, UUID jobId,
                String providerOperationReference) {
            lookupCalls.incrementAndGet();
            throw new IllegalStateException(sensitiveProviderBody);
        }
    }
}
