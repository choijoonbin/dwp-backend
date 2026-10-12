package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.CommandContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ReferenceConfigurationPostgresTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-12T12:00:00Z");
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ReferenceConfigurationService service;

    @BeforeAll
    static void applyExactLeaseAndCreateService() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .baselineVersion("324")
                .target("325")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.baseline();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new ReferenceConfigurationService(
                new JdbcReferenceConfigurationRepository(
                        jdbc, JsonMapper.builder().findAndAddModules().build()),
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void v325PersistsReplayableReceiptsAndPublishedOutboxWithoutRawOwnerContent() {
        UUID configurationId = UUID.randomUUID();
        AccessContext author = new AccessContext(
                42, 101, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE", "TENANT_ADMIN");
        AccessContext publisher = new AccessContext(
                42, 202, "APP.HCM:VIEW,ACTION.HRIS_POLICY_PUBLISH:PUBLISH", "TENANT_ADMIN");
        CreateVersionCommand create = new CreateVersionCommand(
                Domain.SYS, NOW.plusDays(1), null, "employment-status", 1,
                new PayloadReference("ref_owner_1234567890", DIGEST_A));

        CommandReceipt created = transaction(() -> service.createVersion(
                author, command("postgres-create-version", null, false), configurationId, create));
        CommandReceipt replay = transaction(() -> service.createVersion(
                author, command("postgres-create-version", null, false), configurationId, create));
        CommandReceipt validated = transaction(() -> service.validate(
                author, command("postgres-validate-version", 0L, false), configurationId, 1,
                new ValidationCommand(DIGEST_A, 1)));
        CommandReceipt simulated = transaction(() -> service.simulate(
                author, command("postgres-simulate-version", 1L, false), configurationId, 1,
                new SimulationCommand(validated.receiptId(), "ref_sample_1234567890", DIGEST_B)));
        transaction(() -> service.submit(
                author, command("postgres-submit-version", 2L, false), configurationId, 1,
                new SubmitCommand(
                        validated.receiptId(), simulated.receiptId(), DIGEST_B, "hris.config.approval")));
        UUID approval = UUID.randomUUID();
        transaction(() -> service.approve(
                publisher, command("postgres-approve-version", 3L, true), configurationId, 1,
                new ApprovalCommand(approval, DIGEST_A)));
        transaction(() -> service.publish(
                publisher, command("postgres-publish-version", 4L, true), configurationId, 1,
                new PublishCommand(approval, DIGEST_B, NOW.plusDays(1), null)));

        assertThat(replay).isEqualTo(created);
        assertThat(jdbc.queryForObject("""
                SELECT payload_reference
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = 42 AND configuration_id = ? AND version = 1
                """, String.class, configurationId)).isEqualTo("ref_owner_1234567890");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = 42 AND configuration_id = ? AND version = 1
                """, String.class, configurationId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_reference_configuration_command_receipts
                 WHERE tenant_id = 42
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_reference_configuration_outbox
                 WHERE tenant_id = 42 AND event_type = 'dwp.hris.configuration.published.v1'
                """, Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO hris_reference_configuration_versions (
                    tenant_id, configuration_id, version, domain, lifecycle_state,
                    effective_from, schema_key, schema_version, payload_reference,
                    payload_digest, aggregate_version, authored_by, approved_by)
                VALUES (42, ?, 2, 'SYS', 'PUBLISHED', ?, 'employment-status', 1,
                        'ref_owner_another_1234', ?, 0, 303, 202)
                """, configurationId, NOW.plusDays(2), DIGEST_A))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static CommandContext command(String key, Long version, boolean highRisk) {
        return new CommandContext(
                key,
                UUID.nameUUIDFromBytes(key.getBytes()),
                "HRIS_CONFIGURATION",
                version,
                highRisk ? "decision-revision-7" : null,
                highRisk ? "signed-step-up" : null);
    }

    private static <T> T transaction(java.util.function.Supplier<T> action) {
        return transactions.execute(ignored -> action.get());
    }
}
