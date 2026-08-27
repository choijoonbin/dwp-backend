package com.dwp.services.auth.provisioning;

import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderTenantCommandReceiptPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;
    private static AuthTenantProvisioningService service;
    private static TransactionTemplate transactions;

    private UUID tenantId;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        service = new AuthTenantProvisioningService(
                jdbc, new BCryptPasswordEncoder(), objectMapper);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void createTenant() {
        tenantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenants (public_id, code, name, status)
                VALUES (?, ?, 'Command receipt test', 'ACTIVE')
                """, tenantId, "cmd-" + tenantId.toString().substring(0, 12));
    }

    @Test
    void mutationAndReceiptCommitTogetherAndSameHashReplaysWithoutAMutation() {
        UUID commandId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        ProviderTenantCommand.Request command = command(commandId, 0, 1, payload);

        ProviderTenantCommand.Receipt first = execute(command);
        Long versionAfterFirst = tenantVersion();
        ProviderTenantCommand.Receipt replay = execute(command);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.commandId()).isEqualTo(commandId);
        assertThat(tenantVersion()).isEqualTo(versionAfterFirst);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_provider_tenant_command_receipts
                 WHERE command_id = ?
                """, Integer.class, commandId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM com_tenants WHERE public_id = ?
                """, String.class, tenantId)).isEqualTo("SUSPENDED");
    }

    @Test
    void duplicateIdentifierWithDifferentHashAndOutOfOrderRevisionReturnConflict() {
        UUID commandId = UUID.randomUUID();
        ObjectNode suspended = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        execute(command(commandId, 0, 1, suspended));

        ObjectNode active = objectMapper.createObjectNode().put("lifecycleState", "ACTIVE");
        assertThatThrownBy(() -> execute(command(commandId, 0, 1, active)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mutation");
        assertThatThrownBy(() -> execute(command(UUID.randomUUID(), 0, 1, active)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("out of order");
        assertThat(tenantVersion()).isEqualTo(1L);
    }

    @Test
    void failedMutationDoesNotLeaveAReceipt() {
        UUID missingTenant = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        UUID commandId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.execute(ignored -> service.command(
                missingTenant, command(commandId, 0, 1, payload))))
                .isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_provider_tenant_command_receipts
                 WHERE command_id = ?
                """, Integer.class, commandId)).isZero();
    }

    @Test
    void receiptInsertFailureRollsBackTheTenantMutation() {
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        ProviderTenantCommand.Request command = command(UUID.randomUUID(), 0, 1, payload);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_reject_provider_receipt()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated receipt storage failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_provider_receipt
                BEFORE INSERT ON sys_provider_tenant_command_receipts
                FOR EACH ROW EXECUTE FUNCTION test_reject_provider_receipt()
                """);
        try {
            assertThatThrownBy(() -> execute(command))
                    .rootCause()
                    .hasMessageContaining("simulated receipt storage failure");
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_reject_provider_receipt ON sys_provider_tenant_command_receipts");
            jdbc.execute("DROP FUNCTION test_reject_provider_receipt() ");
        }
        assertThat(jdbc.queryForObject("""
                SELECT status FROM com_tenants WHERE public_id = ?
                """, String.class, tenantId)).isEqualTo("ACTIVE");
        assertThat(tenantVersion()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_provider_tenant_command_receipts
                 WHERE command_id = ?
                """, Integer.class, command.commandId())).isZero();
    }

    private ProviderTenantCommand.Request command(
            UUID commandId,
            long expectedRevision,
            long targetRevision,
            ObjectNode payload) {
        return new ProviderTenantCommand.Request(
                commandId, "LIFECYCLE", expectedRevision, targetRevision,
                ProviderTenantCommand.payloadSha256(objectMapper, payload), payload);
    }

    private Long tenantVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM com_tenants WHERE public_id = ?", Long.class, tenantId);
    }

    private ProviderTenantCommand.Receipt execute(ProviderTenantCommand.Request command) {
        return transactions.execute(ignored -> service.command(tenantId, command));
    }
}
