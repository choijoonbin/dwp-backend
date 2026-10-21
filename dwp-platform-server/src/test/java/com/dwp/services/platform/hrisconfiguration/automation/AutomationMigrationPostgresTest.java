package com.dwp.services.platform.hrisconfiguration.automation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AutomationMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE sys_hris_command_receipts (
                    command_receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
                    tenant_id BIGINT NOT NULL,
                    command_type VARCHAR(100) NOT NULL,
                    originating_action VARCHAR(128) NOT NULL,
                    subject_principal_public_id UUID NOT NULL,
                    population_scope_digest CHAR(64) NOT NULL,
                    authority_population_scope_digest CHAR(64) NOT NULL,
                    field_policy_revision BIGINT NOT NULL,
                    authority_field_policy_revision BIGINT NOT NULL,
                    purpose_code VARCHAR(64) NOT NULL,
                    authorization_revision BIGINT NOT NULL,
                    authority_authorization_revision BIGINT NOT NULL,
                    origin_route_contract_key VARCHAR(240) NOT NULL,
                    origin_context_key VARCHAR(500) NOT NULL,
                    origin_context_scope_key VARCHAR(500) NOT NULL,
                    origin_decision_revision VARCHAR(200) NOT NULL,
                    idempotency_key VARCHAR(128) NOT NULL,
                    request_digest CHAR(64) NOT NULL,
                    aggregate_public_id UUID,
                    aggregate_revision BIGINT,
                    status VARCHAR(24) NOT NULL,
                    result_ref VARCHAR(500),
                    error_code VARCHAR(80),
                    correlation_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMPTZ
                )
                """);
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V333__create_hris_automation_control_plane.sql"));
        jdbc.execute(migration);
    }

    @Test
    void exactMigrationExecutesAndInstallsAllEightTablesAndRuntimeGuards() {
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name LIKE 'sys_automation_%'
                """, Integer.class);
        Integer guards = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal
                   AND tgname IN (
                       'trg_sys_automation_original_effect_authority',
                       'trg_sys_automation_compensation_authority',
                       'trg_sys_automation_checkpoint_authority',
                       'trg_sys_automation_shared_receipt')
                """, Integer.class);

        assertThat(tables).isEqualTo(8);
        assertThat(guards).isEqualTo(4);
    }
}
