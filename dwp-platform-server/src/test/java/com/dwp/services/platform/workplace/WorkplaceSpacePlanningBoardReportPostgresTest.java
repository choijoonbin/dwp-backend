package com.dwp.services.platform.workplace;

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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.dwp.services.platform.workplace.WorkplaceSpacePlanningBoardReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.CATALOG_MANAGE;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceSpacePlanningBoardReportPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-17T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 28_401L;
    private static final String REVISION = "psr-" + "b".repeat(64);
    private static final String RAW_PII_MARKER = "planner.person@example.invalid";
    private static final AtomicLong TENANTS = new AtomicLong(9_984_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static WorkplaceSpacePlanningBoardReportService service;

    @BeforeAll
    static void migrateAndBuildService() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkplaceDelegatedAdminScopeGuard guard = mock(WorkplaceDelegatedAdminScopeGuard.class);
        when(guard.revalidate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new WorkplaceSpacePlanningBoardReportService(
                new WorkplaceSpacePlanningBoardReportRepository(jdbc),
                new WorkplaceSpacePlanningBoardReportRenderer(), guard, mapper,
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    void pdfPreviewExecuteReceiptAndContentAreExactOnceScopedAndAudited() {
        Fixture fixture = fixture("PDF");
        var scope = scope(fixture);
        PreviewRequest previewRequest = new PreviewRequest(fixture.scenarioId(), 4L,
                ReportFormat.PDF, "Prepare aggregate board evidence");

        ReportPreview preview = tx(() -> service.preview(fixture.tenantId(), ACTOR,
                fixture.siteId(), "pdf-preview", previewRequest, "corr-preview", scope));
        ReportPreview previewReplay = tx(() -> service.preview(fixture.tenantId(), ACTOR,
                fixture.siteId(), "pdf-preview", previewRequest, "ignored", scope));
        ExecuteRequest executeRequest = new ExecuteRequest(preview.previewId(),
                preview.previewVersion(), preview.scenarioVersion(), preview.confirmationToken(),
                "Issue the reviewed aggregate board report", true);
        ReportReceipt receipt = tx(() -> service.execute(fixture.tenantId(), ACTOR,
                fixture.siteId(), "pdf-execute", executeRequest, "corr-execute", REVISION, scope));
        ReportReceipt replay = tx(() -> service.execute(fixture.tenantId(), ACTOR,
                fixture.siteId(), "pdf-execute", executeRequest, "ignored", REVISION, scope));
        ReportReceipt read = tx(() -> service.receipt(fixture.tenantId(), ACTOR,
                fixture.siteId(), receipt.commandId(), scope));
        ReportContent content = tx(() -> service.content(fixture.tenantId(), ACTOR,
                fixture.siteId(), receipt.commandId(), "corr-download", REVISION, scope));

        assertThat(preview.snapshot().personLevelDataIncluded()).isFalse();
        assertThat(preview.snapshot().personLevelRowCount()).isZero();
        assertThat(previewReplay.previewId()).isEqualTo(preview.previewId());
        assertThat(previewReplay.idempotentReplay()).isTrue();
        assertThat(replay.commandId()).isEqualTo(receipt.commandId());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(read.contentSha256()).isEqualTo(receipt.contentSha256());
        assertThat(receipt.contentHref()).isEqualTo(
                "/v1/admin/workplace/space-planning/reports/" + receipt.commandId()
                        + "/content?siteId=" + fixture.siteId());
        assertThat(content.receipt().mimeType()).isEqualTo("application/pdf");
        assertThat(content.payload()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(content.payload(), StandardCharsets.ISO_8859_1))
                .doesNotContain(RAW_PII_MARKER);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_space_planning_report_commands
                 WHERE tenant_id=? AND actor_user_id=?
                """, Integer.class, fixture.tenantId(), ACTOR)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_space_planning_report_audit_events
                 WHERE tenant_id=? AND event_type IN
                       ('PREVIEW_CREATED','REPORT_EXPORTED','CONTENT_ACCESSED')
                """, Integer.class, fixture.tenantId())).isEqualTo(3);
        assertThatThrownBy(() -> tx(() -> service.content(fixture.tenantId() + 1, ACTOR,
                fixture.siteId(), receipt.commandId(), "wrong-tenant", REVISION, scope)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void xlsxIsARealOpenXmlWorkbookWithoutRawPersonOrBookingRows() throws Exception {
        Fixture fixture = fixture("XLSX");
        var scope = scope(fixture);
        ReportPreview preview = tx(() -> service.preview(fixture.tenantId(), ACTOR,
                fixture.siteId(), "xlsx-preview",
                new PreviewRequest(fixture.scenarioId(), 4L, ReportFormat.XLSX,
                        "Prepare aggregate workbook"), "corr-xlsx-preview", scope));
        ReportReceipt receipt = tx(() -> service.execute(fixture.tenantId(), ACTOR,
                fixture.siteId(), "xlsx-execute",
                new ExecuteRequest(preview.previewId(), 1L, 4L,
                        preview.confirmationToken(), "Issue aggregate workbook", true),
                "corr-xlsx", REVISION, scope));
        ReportContent content = tx(() -> service.content(fixture.tenantId(), ACTOR,
                fixture.siteId(), receipt.commandId(), "corr-xlsx-download", REVISION, scope));

        assertThat(content.receipt().mimeType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(content.payload()).startsWith(new byte[]{'P', 'K'});
        String worksheet = zipEntry(content.payload(), "xl/worksheets/sheet1.xml");
        assertThat(worksheet).contains("Aggregate operational metrics only")
                .contains("Person-level rows").contains(">0<")
                .doesNotContain(RAW_PII_MARKER)
                .doesNotContain("bookingId").doesNotContain("actorUserId");
    }

    @Test
    void scenarioCasAndPreviewFingerprintFailClosed() {
        Fixture fixture = fixture("CAS");
        var scope = scope(fixture);
        PreviewRequest request = new PreviewRequest(fixture.scenarioId(), 4L,
                ReportFormat.PDF, "Preview current version");
        ReportPreview preview = tx(() -> service.preview(fixture.tenantId(), ACTOR,
                fixture.siteId(), "cas-preview", request, "corr-cas", scope));

        assertThatThrownBy(() -> tx(() -> service.preview(fixture.tenantId(), ACTOR,
                fixture.siteId(), "cas-preview", new PreviewRequest(fixture.scenarioId(), 4L,
                        ReportFormat.XLSX, "Different payload"), "corr-other", scope)))
                .isInstanceOf(BaseException.class);

        jdbc.update("""
                UPDATE wp_space_planning_scenarios SET version=version+1,updated_at=?
                 WHERE tenant_id=? AND scenario_id=?
                """, NOW.plusSeconds(1), fixture.tenantId(), fixture.scenarioId());
        assertThatThrownBy(() -> tx(() -> service.execute(fixture.tenantId(), ACTOR,
                fixture.siteId(), "cas-execute",
                new ExecuteRequest(preview.previewId(), 1L, 4L,
                        preview.confirmationToken(), "Reject stale report", true),
                "corr-stale", REVISION, scope)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void fullFlywayAppliesV284AndDatabaseEnforcesAggregateSnapshotFlag() {
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version='284'
                """, Boolean.class)).isTrue();
        Fixture fixture = fixture("CONSTRAINT");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_space_planning_report_previews(
                    preview_id,tenant_id,actor_user_id,scenario_id,site_id,report_format,
                    scenario_version,preview_version,report_snapshot,snapshot_sha256,
                    confirmation_token,reason,idempotency_key,request_fingerprint,
                    correlation_id,expires_at,created_at)
                VALUES(?,?,?,?,?,'PDF',4,1,'{"personLevelDataIncluded":true}'::jsonb,
                       ?,?,'Invalid raw export','invalid-raw',?,'corr',?,?)
                """, UUID.randomUUID(), fixture.tenantId(), ACTOR, fixture.scenarioId(),
                fixture.siteId(), "a".repeat(64), UUID.randomUUID().toString(),
                "b".repeat(64), NOW.plusMinutes(15), NOW))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static Fixture fixture(String suffix) {
        long tenantId = TENANTS.incrementAndGet();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        UUID planningPreviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Board report test','ACTIVE','kr','POOL',?,?)
                """, UUID.randomUUID(), tenantId, "board-report-" + tenantId, ACTOR, ACTOR);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en)
                VALUES(?,?,?,'서울 전략 오피스','Seoul strategy office')
                """, siteId, tenantId, "BOARD_" + suffix + "_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en)
                VALUES(?,?,?,21,'21층','21F')
                """, floorId, tenantId, siteId);
        jdbc.update("""
                INSERT INTO wp_space_planning_scenarios(
                    scenario_id,tenant_id,name,description,lifecycle_state,site_id,floor_id,
                    window_start,window_end,proposed_capacity,proposed_room_capacity,
                    proposed_accessible_resource_count,operating_start,operating_end,
                    affected_resource_ids,neighborhood_allocations,version,
                    created_at,created_by,updated_at,updated_by)
                VALUES(?,?,? ,?,'PREVIEWED',?,?, ?,?,92,18,6,'08:00','20:00',
                       '[]'::jsonb,'[]'::jsonb,4,?,?,?,?)
                """, scenarioId, tenantId, "Global capacity plan " + suffix,
                "Restricted author contact " + RAW_PII_MARKER, siteId, floorId,
                NOW, NOW.plusDays(30), NOW.minusHours(1), ACTOR, NOW, ACTOR);
        jdbc.update("""
                INSERT INTO wp_space_planning_scenario_previews(
                    preview_id,tenant_id,scenario_id,scenario_version,forecast_state,
                    forecast_projection,comparison,emission_projection,eligible,limitations,
                    expires_at,created_by,created_at)
                VALUES(?,?,?,3,'READY',?::jsonb,?::jsonb,?::jsonb,TRUE,'[]'::jsonb,?,?,?)
                """, planningPreviewId, tenantId, scenarioId,
                """
                {"state":"READY","calculationVersion":"forecast-v21",
                 "recommendationMetrics":{"peakDemand":84,"confidencePercent":92}}
                """, """
                {"currentCapacity":100,"proposedCapacity":92,
                 "currentRoomCapacity":20,"proposedRoomCapacity":18,
                 "currentAccessibleResourceCount":4,"proposedAccessibleResourceCount":6,
                 "currentUtilizationPercent":73.4,"proposedUtilizationPercent":79.1}
                """, """
                {"energyValue":120.5,"energyUnit":"kWh","co2eValue":52.2,
                 "co2eUnit":"kgCO2e","factorVersion":"factor-kr-2026","regionCode":"KR"}
                """, NOW.plusDays(1), ACTOR, NOW.minusMinutes(5));
        jdbc.update("""
                UPDATE wp_space_planning_scenarios SET active_preview_id=?
                 WHERE tenant_id=? AND scenario_id=?
                """, planningPreviewId, tenantId, scenarioId);
        return new Fixture(tenantId, siteId, floorId, scenarioId);
    }

    private static WorkplaceDelegatedAdminAccessScope scope(Fixture fixture) {
        return new WorkplaceDelegatedAdminAccessScope(
                new WorkplaceDelegatedAdminAccessScope.Principal(
                        fixture.tenantId(), ACTOR, Set.of(), true),
                fixture.siteId(), CATALOG_MANAGE, null);
    }

    private static String zipEntry(byte[] payload, String expected) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (expected.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing OpenXML entry: " + expected);
    }

    private static <T> T tx(Supplier<T> action) {
        return transaction.execute(ignored -> action.get());
    }

    private record Fixture(long tenantId, UUID siteId, UUID floorId, UUID scenarioId) { }
}
