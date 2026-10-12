package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.HrisConfigurationPublishedV1;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.CommandContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ReferenceConfigurationPostgresTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-12T12:00:00Z");
    private static final String SCOPE_DIGEST = "a".repeat(64);
    private static final String PAYLOAD_DIGEST = "b".repeat(64);
    private static final String SAMPLE_DIGEST = "c".repeat(64);
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

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
                new JdbcReferenceConfigurationRepository(jdbc, MAPPER), MAPPER,
                Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void v325PersistsOwnerEvidenceReceiptsAndCanonicalPublishedOutbox() throws Exception {
        UUID configurationId = UUID.randomUUID();
        AccessContext author = access(42, 101, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE");
        AccessContext publisher = access(
                42, 202, "APP.HCM:VIEW,ACTION.HRIS_POLICY_PUBLISH:PUBLISH");
        CreateVersionCommand create = new CreateVersionCommand(
                Domain.SYS, NOW, null, "employment-status", 1,
                new PayloadReference("ref_owner_1234567890", PAYLOAD_DIGEST));

        CommandReceipt created = transaction(() -> service.createVersion(
                author, command("postgres-create-version", null, false), configurationId, create));
        CommandReceipt replay = transaction(() -> service.createVersion(
                author, command("postgres-create-version", null, false), configurationId, create));
        CommandReceipt validated = transaction(() -> service.validate(
                author, command("postgres-validate-version", 0L, false), configurationId, 1,
                new ValidationCommand(SCOPE_DIGEST, 1)));
        CommandReceipt simulated = transaction(() -> service.simulate(
                author, command("postgres-simulate-version", 1L, false), configurationId, 1,
                new SimulationCommand(
                        validated.receiptId(), "ref_sample_1234567890", SAMPLE_DIGEST)));
        String impact = transaction(() -> jdbc.queryForObject("""
                SELECT simulation_impact_digest
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = 42 AND configuration_id = ? AND version = 1
                """, String.class, configurationId));
        CommandReceipt submitted = transaction(() -> service.submit(
                author, command("postgres-submit-version", 2L, false), configurationId, 1,
                new SubmitCommand(validated.receiptId(), simulated.receiptId(),
                        impact, "hris.config.approval")));
        CommandReceipt approved = transaction(() -> service.approve(
                publisher, command("postgres-approve-version", 3L, true), configurationId, 1,
                new ApprovalCommand(submitted.receiptId(), PAYLOAD_DIGEST)));
        transaction(() -> service.publish(
                publisher, command("postgres-publish-version", 4L, true), configurationId, 1,
                new PublishCommand(approved.receiptId(), impact, NOW, null)));

        assertThat(replay).isEqualTo(created);
        assertThat(transaction(() -> jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM hris_reference_configuration_versions
                 WHERE tenant_id = 42 AND configuration_id = ? AND version = 1
                """, String.class, configurationId))).isEqualTo("PUBLISHED");
        assertThat(transaction(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_reference_configuration_command_receipts
                 WHERE tenant_id = 42 AND configuration_id = ?
                """, Integer.class, configurationId))).isEqualTo(6);
        String payload = transaction(() -> jdbc.queryForObject("""
                SELECT payload::text FROM hris_reference_configuration_outbox
                 WHERE tenant_id = 42 AND aggregate_id = ?
                   AND event_type = 'dwp.hris.configuration.published.v1'
                """, String.class, configurationId));
        HrisConfigurationPublishedV1 event = MAPPER.readValue(
                payload, HrisConfigurationPublishedV1.class);
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(event),
                HrisConfigurationPublishedV1.class)).isEqualTo(event);
        JsonNode json = MAPPER.readTree(payload);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "configurationId", "configurationType", "scopeType", "scopeId", "version",
                "effectiveFrom", "effectiveTo", "payloadSchemaVersion", "payloadDigest",
                "approvalReceiptId", "publishedAt", "eventSequence");
        assertThat(json.get("approvalReceiptId").asText()).isEqualTo(approved.receiptId().toString());

        assertThatThrownBy(() -> transaction(() -> {
            jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET payload_digest = ?
                     WHERE tenant_id = 42 AND configuration_id = ? AND version = 1
                    """, SCOPE_DIGEST, configurationId);
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseNamespaceSeparatesSubjectsAndRejectsSameSubjectMismatch() {
        UUID configurationId = UUID.randomUUID();
        CreateVersionCommand create = new CreateVersionCommand(
                Domain.SYS, NOW, null, "idempotency-scope", 1,
                new PayloadReference("ref_owner_1234567890", PAYLOAD_DIGEST));
        transaction(() -> service.createVersion(
                access(42, 301, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE"),
                command("postgres-shared-idempotency", null, false), configurationId, create));
        assertThatThrownBy(() -> transaction(() -> service.createVersion(
                access(42, 301, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE"),
                command("postgres-shared-idempotency", null, false), configurationId,
                new CreateVersionCommand(
                        Domain.SYS, NOW, null, "idempotency-scope", 1,
                        new PayloadReference("ref_owner_0987654321", PAYLOAD_DIGEST)))))
                .isInstanceOf(BaseException.class).hasMessageContaining("IDEMPOTENCY_PAYLOAD_MISMATCH");
        CommandReceipt independent = transaction(() -> service.createVersion(
                access(42, 302, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE"),
                command("postgres-shared-idempotency", null, false), configurationId, create));
        assertThat(independent.resultRef()).isEqualTo(configurationId + "/2");
    }

    @Test
    void forceRlsAndTransactionLocalTenantBindingHideCrossTenantRows() {
        UUID tenant42 = UUID.randomUUID();
        UUID tenant43 = UUID.randomUUID();
        CreateVersionCommand create = new CreateVersionCommand(
                Domain.SYS, NOW, null, "rls-scope", 1,
                new PayloadReference("ref_owner_1234567890", PAYLOAD_DIGEST));
        transaction(() -> service.createVersion(
                access(42, 401, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE"),
                command("postgres-rls-tenant-42", null, false), tenant42, create));
        transaction(() -> service.createVersion(
                access(43, 402, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE"),
                command("postgres-rls-tenant-43", null, false), tenant43, create));
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(relforcerowsecurity)
                  FROM pg_class
                 WHERE relname LIKE 'hris_reference_configuration%'
                   AND relkind = 'r'
                """, Boolean.class)).isTrue();

        jdbc.execute("DROP ROLE IF EXISTS hris_reference_runtime_test");
        jdbc.execute("CREATE ROLE hris_reference_runtime_test NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO hris_reference_runtime_test");
        jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO hris_reference_runtime_test");
        Integer visible = transactions.execute(ignored -> {
            jdbc.execute("SET LOCAL ROLE hris_reference_runtime_test");
            jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', '42', true)", String.class);
            return jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM hris_reference_configurations
                     WHERE configuration_id IN (?, ?)
                    """, Integer.class, tenant42, tenant43);
        });
        assertThat(visible).isEqualTo(1);
    }

    private static AccessContext access(long tenantId, long subjectId, String permissions) {
        UUID scopeId = tenantId == 42 ? SCOPE_ID
                : UUID.nameUUIDFromBytes(("scope-" + tenantId).getBytes());
        return new AccessContext(
                tenantId, subjectId, UUID.nameUUIDFromBytes(("subject-" + subjectId).getBytes()),
                permissions, "TENANT_ADMIN",
                new PopulationScope(ScopeType.LEGAL_ENTITY, scopeId,
                        SCOPE_DIGEST, "decision-revision-7"),
                7, 9, NOW.plusDays(30), Set.of(), Set.of(), "HRIS_SYSTEM_ADMIN");
    }

    private static CommandContext command(String key, Long version, boolean highRisk) {
        return new CommandContext(
                key, UUID.nameUUIDFromBytes(key.getBytes()), "HRIS_SYSTEM_ADMIN", version,
                highRisk ? "decision-revision-7" : null,
                highRisk ? "signed-step-up" : null);
    }

    private static <T> T transaction(java.util.function.Supplier<T> action) {
        return transactions.execute(ignored -> action.get());
    }
}
