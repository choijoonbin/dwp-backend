package com.dwp.services.platform.workplace.connectorops;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceConnectorV273MigrationPostgresTest {
    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void rotatedLegacyReplayNeverReceivesTheCurrentCredentialBinding() {
        String schema = "connector_v273_legacy_binding";
        Flyway throughV272 = flyway(schema, "272");
        throughV272.clean();
        throughV272.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        long tenantId = 9273001L;
        UUID previewId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T03:00:00Z");

        jdbc.update("""
                INSERT INTO %s.sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,'connector-v273','Connector V273','ACTIVE','kr','POOL',1,1)
                """.formatted(schema), UUID.randomUUID(), tenantId);
        jdbc.update("INSERT INTO %s.wp_tenant_policies(tenant_id) VALUES(?)".formatted(schema),
                tenantId);
        jdbc.update("""
                INSERT INTO %s.wp_experience_connector_configurations(
                    tenant_id,connector_kind,provider,enabled,configuration_reference,version)
                VALUES(?,'CALENDAR','current-provider',TRUE,
                    'secret-manager://connector/current/v2',2)
                """.formatted(schema), tenantId);
        jdbc.update("""
                INSERT INTO %s.wp_connector_replay_previews(
                    preview_id,tenant_id,connector_kind,provider,configuration_version,
                    runtime_version,replay_from,replay_to,failed_only,maximum_records,
                    estimated_records,eligible,limitations,expires_at,created_by,created_at)
                VALUES(?,?,'CALENDAR','legacy-provider',1,1,?,?,FALSE,10,1,TRUE,
                    '[]'::jsonb,?,1,?)
                """.formatted(schema), previewId, tenantId, now.minusHours(1), now,
                now.plusHours(1), now);
        jdbc.update("""
                INSERT INTO %s.wp_connector_replay_jobs(
                    replay_job_id,preview_id,tenant_id,connector_kind,provider,
                    configuration_version,runtime_version,replay_state,reason,idempotency_key,
                    request_fingerprint,requested_by,version,requested_at,updated_at)
                VALUES(?, ?, ?, 'CALENDAR','legacy-provider',1,1,'RESULT_UNKNOWN',
                    'Legacy accepted replay','legacy-key',?,1,1,?,?)
                """.formatted(schema), jobId, previewId, tenantId, "a".repeat(64), now, now);

        flyway(schema, "273").migrate();

        assertThat(jdbc.queryForObject("""
                SELECT credential_reference FROM %s.wp_connector_replay_jobs
                 WHERE tenant_id=? AND replay_job_id=?
                """.formatted(schema), String.class, tenantId, jobId)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT reconcile_attempt_count FROM %s.wp_connector_replay_jobs
                 WHERE tenant_id=? AND replay_job_id=?
                """.formatted(schema), Integer.class, tenantId, jobId)).isZero();
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource()).schemas(schema).defaultSchema(schema)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false).target(target).load();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
