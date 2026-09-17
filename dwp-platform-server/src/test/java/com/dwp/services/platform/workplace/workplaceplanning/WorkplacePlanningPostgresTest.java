package com.dwp.services.platform.workplace.workplaceplanning;

import com.dwp.core.exception.BaseException;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningDtos.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplacePlanningPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 22_001L;
    private static final long APPROVER = 22_002L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static WorkplacePlanningRepository repository;
    private static WorkplacePlanningService service;
    private static long nextTenant = 9_992_000L;

    @BeforeAll
    static void migrateAndBuildService() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new WorkplacePlanningRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        service = new WorkplacePlanningService(repository, CLOCK);
    }

    @Test
    void evidenceBackedScenarioMovesThroughLifecycleWithoutMovingBookings() {
        Fixture fixture = fixture("LIFECYCLE");
        PlanningScope scope = scope(fixture);
        List<UUID> sourceIds = ingestEvidence(fixture, scope);
        EmissionProjection emission = tx(() -> service.observeEmission(new EmissionObservation(
                UUID.randomUUID(), fixture.tenantId(), fixture.siteId(), fixture.floorId(),
                EmissionEvidenceKind.APPROVED_MODEL, new BigDecimal("120.50"), "kWh",
                new BigDecimal("52.20"), "kgCO2e", "factor-kr-2026", "KR",
                "evidence:approved-energy-model", NOW.minusMinutes(3), NOW.minusMinutes(2),
                APPROVER, "approval:energy-model:2026")));
        tx(() -> service.observeForecast(new ForecastObservation(UUID.randomUUID(),
                fixture.tenantId(), scope, ForecastState.READY, "forecast-v22",
                "evidence:forecast-v22", sourceIds,
                List.of(new ForecastPoint(NOW.plusHours(1), new BigDecimal("14"),
                        new BigDecimal("12"), new BigDecimal("16"), "people")),
                new RecommendationMetrics(new BigDecimal("14"), new BigDecimal("4"),
                        new BigDecimal("92"), "forecast-v22"), List.of(),
                NOW.minusMinutes(1), NOW)));
        UUID bookingId = insertBooking(fixture);
        ScenarioDraftInput draft = new ScenarioDraftInput(16, 16, 2,
                LocalTime.of(8, 0), LocalTime.of(20, 0), "policy:space:v22",
                List.of(fixture.resourceId()),
                List.of(new NeighborhoodAllocationInput("North", 16)),
                emission.emissionEvidenceId());

        ScenarioCommandResult created = tx(() -> service.create(fixture.tenantId(), ACTOR,
                "scenario-create", "corr-create", new CreateScenarioRequest(
                        "North capacity plan", "Verified demand scenario", scope, draft,
                        "Prepare a governed scenario", true)));
        ScenarioCommandResult previewed = tx(() -> service.preview(fixture.tenantId(), ACTOR,
                created.scenario().scenarioId(), "scenario-preview", "corr-preview",
                new PreviewScenarioRequest(created.scenario().version(),
                        "Review forecast and booking impact", true)));
        ScenarioCommandResult submitted = tx(() -> service.submit(fixture.tenantId(), ACTOR,
                created.scenario().scenarioId(), "scenario-submit", "corr-submit",
                new ScenarioTransitionRequest(previewed.scenario().version(),
                        "Submit verified scenario", true)));
        ScenarioCommandResult approved = tx(() -> service.approve(fixture.tenantId(), APPROVER,
                created.scenario().scenarioId(), "scenario-approve", "corr-approve",
                new ScenarioApprovalRequest(submitted.scenario().version(),
                        ApprovalDecision.APPROVE, "approval:space-board:22",
                        "Approve evidence-backed scenario", true)));
        int bookingsBeforePublish = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wp_bookings WHERE tenant_id=?", Integer.class,
                fixture.tenantId());
        ScenarioCommandResult published = tx(() -> service.publish(fixture.tenantId(), APPROVER,
                created.scenario().scenarioId(), "scenario-publish", "corr-publish",
                new ScenarioTransitionRequest(approved.scenario().version(),
                        "Publish approved scenario", true)));

        assertThat(previewed.scenario().activePreview()).isNotNull();
        assertThat(previewed.scenario().activePreview().eligible()).isTrue();
        assertThat(previewed.scenario().activePreview().forecastState())
                .isEqualTo(ForecastState.READY);
        assertThat(previewed.scenario().activePreview().comparison().impactedBookingCount())
                .isEqualTo(1);
        assertThat(previewed.scenario().activePreview().comparison()
                .proposedAccessibleResourceCount()).isEqualTo(2);
        assertThat(previewed.scenario().activePreview().emission().factorVersion())
                .isEqualTo("factor-kr-2026");
        assertThat(published.scenario().state()).isEqualTo(ScenarioState.PUBLISHED);
        assertThat(published.scenario().approvedBy()).isEqualTo(APPROVER);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wp_bookings WHERE tenant_id=?", Integer.class,
                fixture.tenantId())).isEqualTo(bookingsBeforePublish);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM wp_bookings WHERE booking_id=?",
                String.class, bookingId)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_space_planning_commands
                 WHERE tenant_id=? AND command_type IN ('SUBMIT','APPROVE','PUBLISH')
                """, Integer.class, fixture.tenantId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_space_planning_outbox WHERE tenant_id=?
                """, Integer.class, fixture.tenantId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_space_planning_audit_events WHERE tenant_id=?
                """, Integer.class, fixture.tenantId())).isEqualTo(5);
    }

    @Test
    void advisoryLockMakesConcurrentSamePayloadReplayExactAndDifferentPayloadConflict() throws Exception {
        Fixture fixture = fixture("CONCURRENT");
        PlanningScope scope = scope(fixture);
        CreateScenarioRequest request = new CreateScenarioRequest("Concurrent plan", null, scope,
                draft(fixture, List.of()), "Create exactly once", true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ScenarioCommandResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return tx(() -> service.create(fixture.tenantId(), ACTOR,
                            "concurrent-key", "corr-concurrent", request));
                }));
            }
            ready.await();
            start.countDown();
            ScenarioCommandResult first = futures.get(0).get();
            ScenarioCommandResult second = futures.get(1).get();

            assertThat(second.scenario()).isEqualTo(first.scenario());
            assertThat(List.of(first.receipt().idempotentReplay(),
                    second.receipt().idempotentReplay())).containsExactlyInAnyOrder(false, true);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wp_space_planning_scenarios WHERE tenant_id=?
                    """, Integer.class, fixture.tenantId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wp_space_planning_commands
                     WHERE tenant_id=? AND idempotency_key='concurrent-key'
                    """, Integer.class, fixture.tenantId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        CreateScenarioRequest different = new CreateScenarioRequest(
                "Different payload", null, scope, draft(fixture, List.of()),
                "Create exactly once", true);
        assertThatThrownBy(() -> tx(() -> service.create(fixture.tenantId(), ACTOR,
                "concurrent-key", "corr-other", different)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void bookingImpactCommandReplaysAndTenantPredicatesPreventEvidenceLeak() {
        Fixture first = fixture("TENANT_A");
        Fixture second = fixture("TENANT_B");
        ScenarioCommandResult created = tx(() -> service.create(first.tenantId(), ACTOR,
                "impact-scenario", "corr", new CreateScenarioRequest("Impact plan", null,
                        scope(first), draft(first, List.of(first.resourceId())),
                        "Inspect booking impact", true)));
        BookingImpactPreviewRequest request = new BookingImpactPreviewRequest(
                created.scenario().version(), "Inspect affected reservations", true);

        BookingImpactCommandResult result = tx(() -> service.previewBookingImpact(
                first.tenantId(), ACTOR, created.scenario().scenarioId(), "impact-preview",
                "corr-impact", request));
        BookingImpactCommandResult replay = tx(() -> service.previewBookingImpact(
                first.tenantId(), ACTOR, created.scenario().scenarioId(), "impact-preview",
                "ignored-correlation", request));

        assertThat(replay.preview()).isEqualTo(result.preview());
        assertThat(replay.receipt().idempotentReplay()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_space_planning_booking_impact_previews
                 WHERE tenant_id=? AND scenario_id=?
                """, Integer.class, first.tenantId(), created.scenario().scenarioId()))
                .isEqualTo(1);
        assertThat(repository.scenario(second.tenantId(), created.scenario().scenarioId())).isEmpty();
        assertThatThrownBy(() -> tx(() -> service.create(first.tenantId(), ACTOR,
                "cross-tenant-resource", "corr-cross", new CreateScenarioRequest(
                        "Cross tenant", null, scope(first),
                        draft(first, List.of(second.resourceId())),
                        "Reject cross-tenant resource", true))))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void migrationIsAppliedAndDatabaseRejectsInventedNonReadyForecastMetrics() {
        Fixture fixture = fixture("INVARIANT");
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version='265'
                """, Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_space_planning_forecasts(
                    forecast_id,tenant_id,site_id,floor_id,window_start,window_end,
                    forecast_state,source_observation_ids,forecast_points,recommendation_metrics,
                    limitations,source_at,received_at)
                VALUES(?,?,?,?,?,?,'STALE','[]'::jsonb,'[{"value":99}]'::jsonb,
                       '{"peakDemand":99}'::jsonb,'[]'::jsonb,?,?)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.siteId(), fixture.floorId(),
                NOW, NOW.plusDays(1), NOW.minusMinutes(1), NOW))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema='public' AND table_name LIKE 'wp_space_planning_%'
                """, String.class)).noneMatch(name -> name.matches(
                ".*(secret|password|access_token|refresh_token|credential_value).*"));
    }

    private static List<UUID> ingestEvidence(Fixture fixture, PlanningScope scope) {
        List<UUID> ids = new ArrayList<>();
        for (PlanningSeries series : PlanningSeries.values()) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            tx(() -> service.observeSource(new SourceObservation(id, fixture.tenantId(), series,
                    scope, SourceAvailability.AVAILABLE, new BigDecimal("100"),
                    NOW.minusMinutes(3), NOW.minusMinutes(2),
                    "evidence:" + series.name().toLowerCase(), List.of(),
                    List.of(new SeriesPoint(NOW.plusHours(1), new BigDecimal("12"),
                            null, null, "people")), 1,
                    "fingerprint_" + series.name().toLowerCase())));
        }
        return List.copyOf(ids);
    }

    private static UUID insertBooking(Fixture fixture) {
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_bookings(
                    booking_id,tenant_id,resource_id,user_id,booked_for_display_name,purpose,
                    starts_at,ends_at,booking_status,policy_snapshot,policy_snapshot_hash,
                    require_check_in_snapshot,check_in_lead_minutes_snapshot,
                    auto_release_minutes_snapshot,booking_retention_days_snapshot)
                VALUES(?,?,?,?,?,'Capacity review',?,?,'RESERVED','{}'::jsonb,
                       encode(digest('{}'::jsonb::text,'sha256'),'hex'),FALSE,15,0,365)
                """, bookingId, fixture.tenantId(), fixture.resourceId(), ACTOR,
                "Planner", NOW.plusHours(1), NOW.plusHours(2));
        return bookingId;
    }

    private static ScenarioDraftInput draft(Fixture fixture, List<UUID> affected) {
        return new ScenarioDraftInput(12, 12, 1, LocalTime.of(8, 0), LocalTime.of(20, 0),
                "policy:space:v22", affected, List.of(), null);
    }

    private static PlanningScope scope(Fixture fixture) {
        return new PlanningScope(fixture.siteId(), fixture.floorId(), null, "ROOM",
                NOW, NOW.plusDays(7));
    }

    private static Fixture fixture(String suffix) {
        long tenantId = ++nextTenant;
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Planning test','ACTIVE','kr','POOL',?,?)
                """, UUID.randomUUID(), tenantId, "planning-" + tenantId, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenantId);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en)
                VALUES(?,?,?,'공간 계획 테스트','Space planning test')
                """, siteId, tenantId, "PLAN_" + suffix + "_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en)
                VALUES(?,?,?,22,'22층','22F')
                """, floorId, tenantId, siteId);
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type,
                    capacity,accessible)
                VALUES(?,?,?,?, '계획 회의실','Planning room','ROOM',12,TRUE)
                """, resourceId, tenantId, floorId, "PLAN_ROOM_" + suffix + "_" + tenantId);
        return new Fixture(tenantId, siteId, floorId, resourceId);
    }

    private static <T> T tx(Supplier<T> supplier) {
        return transaction.execute(ignored -> supplier.get());
    }

    private record Fixture(long tenantId, UUID siteId, UUID floorId, UUID resourceId) { }
}
