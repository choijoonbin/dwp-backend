package com.dwp.services.platform.workplace.exceptionconsole;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceExceptionV278MigrationPostgresTest {
    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void exportPreviewAndReceiptAreTenantActorBoundAndIdempotent() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("""
                CREATE TABLE sys_service_tenants (
                    provider_tenant_id UUID NOT NULL,
                    tenant_id BIGINT PRIMARY KEY,
                    tenant_key VARCHAR(80) NOT NULL,
                    display_name VARCHAR(200) NOT NULL,
                    lifecycle_state VARCHAR(32) NOT NULL,
                    data_region VARCHAR(32) NOT NULL,
                    isolation_model VARCHAR(32) NOT NULL,
                    created_by BIGINT NOT NULL,
                    updated_by BIGINT NOT NULL
                )
                """);
        new ResourceDatabasePopulator(new FileSystemResource(
                "src/main/resources/db/migration/V278__govern_workplace_exception_exports.sql"))
                .execute(dataSource());
        long tenantId = 9278001L;
        UUID previewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T03:00:00Z");
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,'exception-v278','Exception V278','ACTIVE','kr','POOL',1,1)
                """, UUID.randomUUID(), tenantId);
        jdbc.update("""
                INSERT INTO wp_exception_export_previews(
                    preview_id,tenant_id,actor_user_id,purpose,row_count,content_sha256,
                    idempotency_key,request_fingerprint,correlation_id,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, previewId, tenantId, 99, "Incident review", 1, "a".repeat(64),
                "preview-key", "b".repeat(64), "corr", now.plusMinutes(10), now);
        jdbc.update("""
                INSERT INTO wp_exception_export_commands(
                    command_id,preview_id,tenant_id,actor_user_id,reason,row_count,csv_content,
                    content_sha256,idempotency_key,request_fingerprint,correlation_id,
                    accepted_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, commandId, previewId, tenantId, 99, "Incident review", 1,
                "header\nvalue\n", "c".repeat(64), "export-key", "d".repeat(64),
                "corr", now, now.plusHours(1));

        assertThat(jdbc.queryForObject("""
                SELECT row_count FROM wp_exception_export_commands
                 WHERE tenant_id=? AND actor_user_id=? AND command_id=?
                """, Integer.class, tenantId, 99, commandId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_exception_export_commands(
                    command_id,preview_id,tenant_id,actor_user_id,reason,row_count,csv_content,
                    content_sha256,idempotency_key,request_fingerprint,correlation_id,
                    accepted_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), previewId, tenantId, 99, "Different", 1,
                "header\nvalue\n", "e".repeat(64), "export-key", "f".repeat(64),
                "corr-2", now, now.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
