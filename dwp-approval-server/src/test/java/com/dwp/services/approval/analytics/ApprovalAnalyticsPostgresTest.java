package com.dwp.services.approval.analytics;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.analytics.ApprovalAnalyticsModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalAnalyticsPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ApprovalAnalyticsService service;
    private UUID largeCohort;
    private UUID smallCohort;
    private final List<UUID> largeRequests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc.execute("SELECT seed_approval_tenant(42)");
        service = new ApprovalAnalyticsService(
                new ApprovalAnalyticsRepository(new NamedParameterJdbcTemplate(dataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        List<UUID> forms = jdbc.queryForList("""
                SELECT form_version_id FROM apr_form_versions
                 WHERE tenant_id = 42 ORDER BY form_version_id LIMIT 2
                """, UUID.class);
        largeCohort = forms.get(0);
        smallCohort = forms.get(1);

        for (int index = 0; index < 5; index++) {
            UUID request = insertRequest(
                    largeCohort, "LARGE-" + index,
                    7_200 + index * 600L, true, index);
            largeRequests.add(request);
        }
        for (int index = 0; index < 4; index++) {
            insertRequest(
                    smallCohort, "SMALL-" + index,
                    3_600 + index * 300L, index < 3, 10 + index);
        }
        insertMissingSubmitted(smallCohort);

        insertEvent(largeRequests.get(0), "TASK_INFORMATION_REQUESTED");
        insertEvent(largeRequests.get(1), "TASK_DELEGATED");
        insertEvent(largeRequests.get(2), "SLA_ESCALATED");
        insertEvent(largeRequests.get(3), "TASK_REASSIGNED_BY_OPERATOR");
    }

    @Test
    void readModelProvidesDefinedMetricsCoverageAndSmallCohortSuppression() {
        Dashboard dashboard = service.dashboard(scope(), query());

        assertThat(dashboard.generatedAt()).isEqualTo(NOW);
        assertThat(dashboard.definitions()).extracting(MetricDefinition::key)
                .containsExactly(
                        "cycle.p50", "cycle.p90", "stage.wait", "sla.compliance",
                        "rework", "delegation", "escalation", "route.conformance");
        assertThat(dashboard.coverage().candidateRequests()).isEqualTo(10);
        assertThat(dashboard.coverage().includedRequests()).isEqualTo(9);
        assertThat(dashboard.coverage().excludedData())
                .containsEntry("MISSING_SUBMITTED_AT", 1)
                .containsEntry("IN_FLIGHT_FOR_CYCLE", 1)
                .containsEntry("INVALID_TEMPORAL_ORDER", 0);
        assertThat(dashboard.coverage().projectedAt()).isNotNull();

        assertThat(dashboard.overall().suppressed()).isFalse();
        assertThat(dashboard.overall().cycleP50Seconds()).isNotNull();
        assertThat(dashboard.overall().cycleP90Seconds())
                .isGreaterThanOrEqualTo(dashboard.overall().cycleP50Seconds());
        assertThat(dashboard.overall().requestsWithRework()).isEqualTo(1);
        assertThat(dashboard.overall().delegatedRequests()).isEqualTo(1);
        assertThat(dashboard.overall().escalatedRequests()).isEqualTo(1);
        assertThat(dashboard.overall().routeConformantRequests()).isEqualTo(8);
        assertThat(dashboard.stageWaits()).hasSize(1);
        assertThat(dashboard.stageWaits().getFirst().metrics().stageWaitP50Seconds())
                .isNotNull();

        Cohort large = cohort(dashboard, largeCohort);
        Cohort small = cohort(dashboard, smallCohort);
        assertThat(large.metrics().suppressed()).isFalse();
        assertThat(large.metrics().sampleSize()).isEqualTo(5);
        assertThat(small.metrics().suppressed()).isTrue();
        assertThat(small.metrics().sampleSize()).isNull();
        assertThat(small.metrics().cycleP50Seconds()).isNull();
    }

    @Test
    void representativeDrilldownReauthorizesEveryRequestAndRejectsSmallCohorts() {
        UUID allowed = largeRequests.get(2);
        List<Representative> representatives = service.representatives(
                scope(), query(), largeCohort.toString(), 10,
                (tenant, resourceSet, actor, request) -> request.equals(allowed));

        assertThat(representatives).extracting(Representative::requestId)
                .containsExactly(allowed);
        assertThatThrownBy(() -> service.representatives(
                scope(), query(), smallCohort.toString(), 10,
                (tenant, resourceSet, actor, request) -> true))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void analyticsNeverReadsAnAdjacentTenantOrResourceSet() {
        jdbc.execute("SELECT seed_approval_tenant(43)");
        UUID foreignForm = jdbc.queryForObject("""
                SELECT form_version_id FROM apr_form_versions
                 WHERE tenant_id = 43 ORDER BY form_version_id LIMIT 1
                """, UUID.class);
        insertRequest(43, foreignForm, "FOREIGN-1", 300, true, 20);

        Dashboard dashboard = service.dashboard(scope(), query());

        assertThat(dashboard.coverage().candidateRequests()).isEqualTo(10);
        assertThat(dashboard.overall().sampleSize()).isEqualTo(9);
    }

    @Test
    void refreshMovesRequestAndStageFactsToTheOnlyCurrentResourceSet() {
        UUID request = largeRequests.getFirst();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_analytics_stage_facts
                 WHERE tenant_id=42 AND request_id=?
                   AND management_resource_set_key='RS_APPROVALS'
                """, Integer.class, request)).isPositive();

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                statement.executeUpdate("""
                        UPDATE apr_requests
                           SET management_resource_set_key='RS_FINANCE',
                               updated_at=clock_timestamp()
                         WHERE tenant_id=42 AND request_id='%s'::uuid
                        """.formatted(request));
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET session_replication_role = origin");
                }
            }
            return null;
        });
        jdbc.execute("SELECT refresh_approval_analytics_request(42, '%s'::uuid)"
                .formatted(request));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_analytics_request_facts
                 WHERE tenant_id = 42 AND request_id = ?
                """, Integer.class, request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT management_resource_set_key FROM apr_analytics_request_facts
                 WHERE tenant_id = 42 AND request_id = ?
                """, String.class, request)).isEqualTo("RS_FINANCE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_analytics_stage_facts
                 WHERE tenant_id = 42 AND request_id = ?
                   AND management_resource_set_key = 'RS_APPROVALS'
                """, Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_analytics_stage_facts
                 WHERE tenant_id = 42 AND request_id = ?
                   AND management_resource_set_key = 'RS_FINANCE'
                """, Integer.class, request)).isPositive();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_analytics_request_facts(
                    tenant_id,management_resource_set_key,request_id,
                    workflow_version_id,form_version_id,request_status,
                    data_classification,request_created_at,submitted_at,due_at,
                    completed_at,cycle_seconds,stage_count,completed_stage_count,
                    rework_count,delegation_count,escalation_count,
                    route_override_count,source_event_count,source_last_event_at,projected_at)
                SELECT tenant_id,'RS_APPROVALS',request_id,
                       workflow_version_id,form_version_id,request_status,
                       data_classification,request_created_at,submitted_at,due_at,
                       completed_at,cycle_seconds,stage_count,completed_stage_count,
                       rework_count,delegation_count,escalation_count,
                       route_override_count,source_event_count,source_last_event_at,projected_at
                  FROM apr_analytics_request_facts
                 WHERE tenant_id=42 AND request_id=?
                """, request)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Cohort cohort(Dashboard dashboard, UUID key) {
        return dashboard.cohorts().stream()
                .filter(item -> item.key().equals(key.toString()))
                .findFirst().orElseThrow();
    }

    private Scope scope() {
        return new Scope(
                42, "RS_APPROVALS", 17,
                Set.of(Capability.VIEW, Capability.DRILL_DOWN));
    }

    private Query query() {
        return new Query(
                NOW.minusSeconds(86_400), NOW.plusSeconds(30),
                CohortDimension.FORM, 5);
    }

    private UUID insertRequest(
            UUID formVersion,
            String number,
            long cycleSeconds,
            boolean completed,
            int ordinal) {
        return insertRequest(42, formVersion, number, cycleSeconds, completed, ordinal);
    }

    private UUID insertRequest(
            long tenantId,
            UUID formVersion,
            String number,
            long cycleSeconds,
            boolean completed,
            int ordinal) {
        UUID workflow = jdbc.queryForObject("""
                SELECT workflow_version_id FROM apr_workflow_versions
                 WHERE tenant_id = ? ORDER BY workflow_version_id LIMIT 1
                """, UUID.class, tenantId);
        Instant submitted = NOW.minusSeconds(20_000L + ordinal * 60L);
        Instant completedAt = submitted.plusSeconds(cycleSeconds);
        UUID request = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_requests (
                    request_id, tenant_id, request_number,
                    workflow_version_id, form_version_id, title,
                    requester_user_id, status, submitted_at, due_at,
                    completed_at, management_resource_set_key,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'Analytics request', 17, ?, ?, ?, ?,
                    'RS_APPROVALS', ?, ?)
                """, request, tenantId, number, workflow, formVersion,
                completed ? "APPROVED" : "IN_REVIEW",
                java.sql.Timestamp.from(submitted),
                java.sql.Timestamp.from(submitted.plusSeconds(cycleSeconds - 60)),
                completed ? java.sql.Timestamp.from(completedAt) : null,
                java.sql.Timestamp.from(submitted.minusSeconds(60)),
                java.sql.Timestamp.from(completed ? completedAt : NOW.minusSeconds(30)));
        insertStage(tenantId, request, submitted, completedAt, completed);
        return request;
    }

    private void insertStage(
            long tenantId,
            UUID request,
            Instant started,
            Instant completedAt,
            boolean completed) {
        UUID step = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_steps (
                    step_id, tenant_id, request_id, step_key, step_name,
                    sequence_number, approval_mode, status, started_at,
                    due_at, completed_at)
                VALUES (?, ?, ?, 'PRIMARY_REVIEW', 'Primary review', 1, 'ANY',
                    ?, ?, ?, ?)
                """, step, tenantId, request, completed ? "APPROVED" : "IN_PROGRESS",
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(completedAt.minusSeconds(60)),
                completed ? java.sql.Timestamp.from(completedAt) : null);
        jdbc.update("""
                INSERT INTO apr_tasks (
                    task_id, tenant_id, request_id, step_id,
                    assignee_user_id, status, due_at, completed_at,
                    decision_payload_revision, decision_payload_sha256)
                VALUES (?, ?, ?, ?, 17, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, request, step,
                completed ? "APPROVED" : "PENDING",
                java.sql.Timestamp.from(completedAt.minusSeconds(60)),
                completed ? java.sql.Timestamp.from(completedAt) : null,
                completed ? 1 : null,
                completed ? "e".repeat(64) : null);
    }

    private void insertMissingSubmitted(UUID formVersion) {
        UUID workflow = jdbc.queryForObject("""
                SELECT workflow_version_id FROM apr_workflow_versions
                 WHERE tenant_id = 42 ORDER BY workflow_version_id LIMIT 1
                """, UUID.class);
        jdbc.update("""
                INSERT INTO apr_requests (
                    request_id, tenant_id, request_number,
                    workflow_version_id, form_version_id, title,
                    requester_user_id, status, management_resource_set_key,
                    created_at, updated_at)
                VALUES (?, 42, 'MISSING-SUBMITTED', ?, ?, 'Draft analytics request',
                    17, 'DRAFT', 'RS_APPROVALS', ?, ?)
                """, UUID.randomUUID(), workflow, formVersion,
                java.sql.Timestamp.from(NOW.minusSeconds(120)),
                java.sql.Timestamp.from(NOW.minusSeconds(120)));
    }

    private void insertEvent(UUID request, String type) {
        jdbc.update("""
                INSERT INTO apr_request_events (
                    event_id, tenant_id, request_id, event_type,
                    actor_type, actor_id, outcome, event_data, occurred_at)
                VALUES (?, 42, ?, ?, 'USER', '17', 'SUCCESS', '{}'::jsonb, ?)
                """, UUID.randomUUID(), request, type,
                java.sql.Timestamp.from(NOW.minusSeconds(60)));
    }
}
