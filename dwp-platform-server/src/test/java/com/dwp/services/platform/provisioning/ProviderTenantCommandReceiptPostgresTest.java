package com.dwp.services.platform.provisioning;

import com.dwp.core.provisioning.ProviderTenantCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProviderTenantCommandReceiptPostgresTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;
    private static PlatformTenantProvisioningService service;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateThroughReceiptContract() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("191")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        service = new PlatformTenantProvisioningService(
                jdbc, Path.of(System.getProperty("java.io.tmpdir"), "dwp-platform-command-test").toString(),
                objectMapper);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void v191AppliesLifecycleAndReceiptAtomicallyAndReplaysTheReceipt() {
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        ProviderTenantCommand.Request command = new ProviderTenantCommand.Request(
                UUID.randomUUID(), "LIFECYCLE", 0, 1,
                ProviderTenantCommand.payloadSha256(objectMapper, payload), payload);

        ProviderTenantCommand.Receipt first = transactions.execute(
                ignored -> service.command(TENANT_ID, command));
        ProviderTenantCommand.Receipt replay = transactions.execute(
                ignored -> service.command(TENANT_ID, command));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM sys_service_tenants WHERE provider_tenant_id = ?
                """, String.class, TENANT_ID)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_provider_tenant_command_receipts WHERE command_id = ?
                """, Integer.class, command.commandId())).isEqualTo(1);
    }
}
