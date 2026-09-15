package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApprovalAdminTrendPostgresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        JdbcTemplate plain = new JdbcTemplate(dataSource);
        plain.execute("DROP TABLE IF EXISTS apr_tasks, apr_integration_outbox, apr_requests");
        plain.execute("""
                CREATE TABLE apr_requests (
                    request_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    management_resource_set_key VARCHAR(160) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    submitted_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ)
                """);
        plain.execute("""
                CREATE TABLE apr_tasks (
                    task_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    request_id UUID NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    due_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ)
                """);
        plain.execute("""
                CREATE TABLE apr_integration_outbox (
                    outbox_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    management_resource_set_key VARCHAR(160) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL)
                """);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Test
    void returnsTwelveContiguousSixHourBucketsFromActualTenantEvents() {
        JdbcTemplate plain = jdbc.getJdbcTemplate();
        plain.update("""
                INSERT INTO apr_requests
                    (request_id, tenant_id, management_resource_set_key, status, submitted_at, completed_at)
                VALUES
                    (gen_random_uuid(), 42, 'RS_APPROVALS', 'APPROVED', CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '1 hour'),
                    (gen_random_uuid(), 42, 'RS_OTHER', 'APPROVED', CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '1 hour'),
                    (gen_random_uuid(), 43, 'RS_APPROVALS', 'APPROVED', CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '1 hour'),
                    (gen_random_uuid(), 42, 'RS_APPROVALS', 'IN_REVIEW', CURRENT_TIMESTAMP - INTERVAL '100 hours', NULL)
                """);
        plain.update("""
                INSERT INTO apr_requests
                    (request_id, tenant_id, management_resource_set_key, status, submitted_at, completed_at)
                VALUES ('10000000-0000-4000-8000-000000000001', 42, 'RS_APPROVALS', 'IN_REVIEW', CURRENT_TIMESTAMP - INTERVAL '18 hours', NULL)
                """);
        plain.update("""
                INSERT INTO apr_tasks (task_id, tenant_id, request_id, status, due_at, completed_at)
                VALUES
                    (gen_random_uuid(), 42, '10000000-0000-4000-8000-000000000001', 'PENDING', CURRENT_TIMESTAMP - INTERVAL '10 hours', NULL),
                    (gen_random_uuid(), 42, '10000000-0000-4000-8000-000000000001', 'APPROVED', CURRENT_TIMESTAMP - INTERVAL '20 hours', CURRENT_TIMESTAMP - INTERVAL '21 hours'),
                    (gen_random_uuid(), 42, '10000000-0000-4000-8000-000000000001', 'APPROVED', CURRENT_TIMESTAMP - INTERVAL '30 hours', CURRENT_TIMESTAMP - INTERVAL '29 hours')
                """);
        plain.update("""
                INSERT INTO apr_integration_outbox
                    (outbox_id, tenant_id, management_resource_set_key, status, updated_at)
                VALUES
                    (gen_random_uuid(), 42, 'RS_APPROVALS', 'FAILED', CURRENT_TIMESTAMP - INTERVAL '4 hours'),
                    (gen_random_uuid(), 42, 'RS_APPROVALS', 'PUBLISHED', CURRENT_TIMESTAMP - INTERVAL '4 hours'),
                    (gen_random_uuid(), 42, 'RS_OTHER', 'DEAD', CURRENT_TIMESTAMP - INTERVAL '4 hours')
                """);

        List<Bucket> buckets = jdbc.query(
                ApprovalAdminTrendSql01.SELECT,
                new MapSqlParameterSource()
                        .addValue("tenantId", 42L)
                        .addValue("managementScope", "RS_APPROVALS"),
                (result, rowNumber) -> new Bucket(
                        result.getTimestamp("generated_at").toInstant(),
                        result.getTimestamp("bucket_start").toInstant(),
                        result.getTimestamp("bucket_end").toInstant(),
                        result.getInt("submitted_requests"),
                        result.getInt("completed_requests"),
                        result.getInt("sla_breaches"),
                        result.getInt("unresolved_delivery_updates"),
                        result.getInt("in_flight_requests"),
                        result.getInt("sla_eligible_tasks")));

        assertThat(buckets).hasSize(12);
        assertThat(buckets).allSatisfy(bucket ->
                assertThat(Duration.between(bucket.startsAt(), bucket.endsAt())).isEqualTo(Duration.ofHours(6)));
        for (int index = 1; index < buckets.size(); index++) {
            assertThat(buckets.get(index).startsAt()).isEqualTo(buckets.get(index - 1).endsAt());
        }
        assertThat(Duration.between(buckets.getFirst().startsAt(), buckets.getLast().endsAt()))
                .isEqualTo(Duration.ofHours(72));
        assertThat(buckets.stream().mapToInt(Bucket::submittedRequests).sum()).isEqualTo(2);
        assertThat(buckets.stream().mapToInt(Bucket::completedRequests).sum()).isEqualTo(1);
        assertThat(buckets.stream().mapToInt(Bucket::slaBreaches).sum()).isEqualTo(2);
        assertThat(buckets.stream().mapToInt(Bucket::slaEligibleTasks).sum()).isEqualTo(3);
        assertThat(buckets.stream().mapToInt(Bucket::unresolvedDeliveryUpdates).sum()).isEqualTo(1);
        assertThat(buckets.getLast().inFlightRequests()).isEqualTo(2);
        assertThat(buckets.getLast().generatedAt())
                .isAfterOrEqualTo(buckets.getLast().startsAt())
                .isBefore(buckets.getLast().endsAt());
    }

    private record Bucket(
            Instant generatedAt,
            Instant startsAt,
            Instant endsAt,
            int submittedRequests,
            int completedRequests,
            int slaBreaches,
            int unresolvedDeliveryUpdates,
            int inFlightRequests,
            int slaEligibleTasks) {
    }
}
