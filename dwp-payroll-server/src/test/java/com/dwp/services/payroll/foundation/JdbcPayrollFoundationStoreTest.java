package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandType;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CreateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.MutationResult;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReceiptStatus;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.UpdateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.VersionCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.GROUP_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.LEGAL_ENTITY_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.currency;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.definition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcPayrollFoundationStoreTest {

    private static final String RUNTIME_USER = "payroll_runtime";
    private static final String RUNTIME_PASSWORD = "runtime-test-password";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate adminJdbc;
    private PayrollFoundationService service;

    @BeforeAll
    static void migrateAndStartRuntimeContext() throws Exception {
        DataSource adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
        }
        Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("payrollRuntimeRole", RUNTIME_USER))
                .load()
                .migrate();
        adminJdbc = new JdbcTemplate(adminDataSource);
        context = new AnnotationConfigApplicationContext(DatabaseTestConfiguration.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void reset() {
        adminJdbc.execute("TRUNCATE TABLE pay_foundation_audit_events, "
                + "pay_foundation_command_receipts, pay_foundation_versions, "
                + "pay_foundation_configurations CASCADE");
        service = new PayrollFoundationService(
                context.getBean(PayrollFoundationStore.class),
                (tenantId, pin) -> java.util.OptionalLong.empty(),
                Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void persistsTenantBoundVersionsReceiptsAndExactAuditActors() {
        PayrollFoundationStore store = context.getBean(PayrollFoundationStore.class);
        FoundationDefinition first = definition(
                LEGAL_ENTITY_ID, GROUP_ID,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Set.of(currency("USD")), List.of());
        UUID createCommandId = UUID.randomUUID();
        MutationResult created = service.create(
                author(1, 101), createCommandId, "alice-create",
                new CreateConfigurationRequest(first));
        UUID firstId = created.configuration().configurationId();
        UUID simulationCommandId = UUID.randomUUID();
        MutationResult simulated = service.simulate(
                author(1, 202), firstId, simulationCommandId, "bob-simulates-alice",
                new VersionCommand(1));

        assertThat(simulated.configuration().version()).isEqualTo(2);
        assertThat(simulated.configuration().status()).isEqualTo(Lifecycle.SIMULATED);
        Map<String, Object> audit = adminJdbc.queryForMap("""
                SELECT actor_id, subject_id, correlation_id, authority_purpose,
                       legal_entity_scope_digest, policy_revision, authorization_revision
                  FROM pay_foundation_audit_events
                 WHERE event_type = 'FOUNDATION_SIMULATED'
                """);
        assertThat(audit.get("actor_id")).isEqualTo(202L);
        assertThat(audit.get("subject_id")).isEqualTo(101L);
        assertThat(audit.get("correlation_id")).isEqualTo("bob-simulates-alice");
        assertThat(audit.get("authority_purpose")).isEqualTo("PAYROLL_CONFIGURATION");
        assertThat(audit.get("legal_entity_scope_digest"))
                .isEqualTo(PayrollFoundationCanonical.textDigest("*"));
        assertThat(audit.get("policy_revision"))
                .isEqualTo("test-PAYROLL-ACCESS-COMPATIBILITY-v1");
        assertThat(audit.get("authorization_revision"))
                .isEqualTo("test-authorization-revision");
        Map<String, Object> receiptEvidence = adminJdbc.queryForMap("""
                SELECT authority_purpose, legal_entity_scope_digest,
                       policy_revision, authorization_revision
                  FROM pay_foundation_command_receipts
                 WHERE tenant_id = 1 AND command_id = ?
                """, simulationCommandId);
        assertThat(receiptEvidence).containsAllEntriesOf(Map.of(
                "authority_purpose", "PAYROLL_CONFIGURATION",
                "legal_entity_scope_digest", PayrollFoundationCanonical.textDigest("*"),
                "policy_revision", "test-PAYROLL-ACCESS-COMPATIBILITY-v1",
                "authorization_revision", "test-authorization-revision"));
        assertThat(store.byCommand(1, createCommandId).orElseThrow().version()).isEqualTo(1);
        assertThat(store.version(1, firstId, 1).orElseThrow().version()).isEqualTo(1);
        assertThat(store.byCommand(2, createCommandId)).isEmpty();
        MutationResult recoveredCreate = service.receipt(
                author(1, 101), createCommandId);
        assertThat(recoveredCreate.receipt().resultVersion()).isEqualTo(1L);
        assertThat(recoveredCreate.configuration().version()).isEqualTo(1L);
        assertThat(store.current(1, firstId).orElseThrow().version()).isEqualTo(2L);
        assertThatThrownBy(() -> adminJdbc.update("""
                UPDATE pay_foundation_command_receipts
                   SET receipt_status = 'RESULT_UNKNOWN',
                       failure_code = 'RESULT_UNKNOWN',
                       completed_at = now()
                 WHERE tenant_id = 1 AND command_id = ?
                """, createCommandId))
                .isInstanceOf(DataAccessException.class);

        UUID secondLegalEntity = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID secondGroup = UUID.fromString("20000000-0000-0000-0000-000000000002");
        FoundationDefinition second = definition(
                secondLegalEntity, secondGroup,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Set.of(currency("EUR")), List.of());
        MutationResult secondCreated = service.create(
                author(1, 101), UUID.randomUUID(), null,
                new CreateConfigurationRequest(second));
        UUID secondId = secondCreated.configuration().configurationId();
        service.update(
                author(1, 202), secondId, UUID.randomUUID(), "bob-update",
                new UpdateConfigurationRequest(1, second));
        MutationResult secondSimulation = service.simulate(
                author(1, 101), secondId, UUID.randomUUID(), null,
                new VersionCommand(2));

        assertThatThrownBy(() -> service.publish(
                publisher(1, 202), secondId, UUID.randomUUID(), null,
                new VersionCommand(secondSimulation.configuration().version())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SOD_CONFLICT));
        MutationResult published = service.publish(
                publisher(1, 303), secondId, UUID.randomUUID(), "carol-publish",
                new VersionCommand(secondSimulation.configuration().version()));
        assertThat(published.configuration().status()).isEqualTo(Lifecycle.PUBLISHED);

        assertThat(store.versions(1, secondId))
                .extracting(snapshot -> snapshot.status())
                .containsExactly(
                        Lifecycle.PUBLISHED, Lifecycle.SIMULATED,
                        Lifecycle.DRAFT, Lifecycle.DRAFT);
        assertThat(store.current(2, secondId)).isEmpty();
        assertThat(service.receipt(
                publisher(1, 303), published.receipt().commandId()).receipt())
                .isEqualTo(published.receipt());

        JdbcTemplate runtimeJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(runtimeJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_foundation_configurations", Long.class))
                .isZero();
        assertThatThrownBy(() -> runtimeJdbc.update(
                "UPDATE pay_foundation_versions SET lifecycle_state = 'DRAFT'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtimeJdbc.update(
                "UPDATE pay_foundation_audit_events SET correlation_id = 'tampered'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtimeJdbc.update(
                "UPDATE pay_foundation_command_receipts "
                        + "SET authorization_revision = 'tampered'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void letsAnUnknownReversalReceiptConvergeToDefinitiveFailure() {
        PayrollFoundationStore store = context.getBean(PayrollFoundationStore.class);
        PayrollFoundationAccess.Actor actor = publisher(1, 303);
        UUID configurationId = UUID.randomUUID();
        UUID publishCommandId = UUID.randomUUID();
        UUID reversalCommandId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T09:00:00Z");
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.REVERSE, configurationId, 3L, null, publishCommandId);
        CommandReceipt pending = new CommandReceipt(
                actor.tenantId(), actor.actorId(), reversalCommandId, CommandType.REVERSE,
                ReceiptStatus.PENDING, digest, configurationId, null,
                publishCommandId, null, null, actor.purpose(), actor.legalEntityScopeDigest(),
                actor.policyRevision(), actor.authorizationRevision(), now, null);

        store.reserve(pending);
        store.replaceReceipt(pending.unknown(configurationId, 3L, now));
        store.replaceReceipt(pending.reversalFailed(configurationId, 3L, now));

        assertThat(store.receipt(actor.tenantId(), reversalCommandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.REVERSAL_FAILED);
    }

    private PayrollFoundationAccess.Actor author(long tenant, long actor) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "CONFIGURATION_AUTHOR",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:EDIT "
                        + "PAYROLL_FOUNDATION:SIMULATE",
                "PAYROLL_CONFIGURATION", "*");
    }

    private PayrollFoundationAccess.Actor publisher(long tenant, long actor) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "CONFIGURATION_PUBLISHER",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:PUBLISH "
                        + "PAYROLL_FOUNDATION:REVERSE PAYROLL_FOUNDATION:RECONCILE",
                "PAYROLL_CONFIGURATION", "*");
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class DatabaseTestConfiguration {

        @Bean
        DataSource dataSource() {
            return JdbcPayrollFoundationStoreTest.dataSource(
                    POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PayrollFoundationStore payrollFoundationStore(
                NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
            return new JdbcPayrollFoundationStore(jdbc, objectMapper);
        }
    }
}
