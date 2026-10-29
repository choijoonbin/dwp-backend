package com.dwp.services.payroll.configuration;

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
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Action;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateFormulaVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.MutationResult;
import static com.dwp.services.payroll.configuration.PayFormulaAstContract.PayValueType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.RoundingPolicy;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.SimulationCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.TypedValue;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.VersionCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.CLOCK;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.GOLDEN_DIGEST;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.LEGAL_ENTITY;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.PAYROLL_GROUP;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.actor;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.grossFormula;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcPayrollConfigurationStoreTest {

    private static final String RUNTIME_USER = "payroll_configuration_runtime";
    private static final String RUNTIME_PASSWORD = "runtime-test-password";
    private static final String PUBLISHER_USER = "payroll_configuration_publisher";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate adminJdbc;
    private PayrollConfigurationStore store;
    private PayrollConfigurationService service;

    @BeforeAll
    static void migrateFromV1ThroughV6() throws Exception {
        DataSource adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PUBLISHER_USER
                    + " LOGIN NOINHERIT PASSWORD 'publisher-test-password'");
        }
        Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME_USER,
                        "payrollProjectionPublisherRole", PUBLISHER_USER))
                .load()
                .migrate();
        adminJdbc = new JdbcTemplate(adminDataSource);
        context = new AnnotationConfigApplicationContext(DatabaseTestConfiguration.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
    }

    @BeforeEach
    void reset() {
        adminJdbc.execute("TRUNCATE TABLE pay_configuration_outbox_events, "
                + "pay_configuration_command_receipts, pay_formula_versions, "
                + "pay_element_versions CASCADE");
        store = context.getBean(PayrollConfigurationStore.class);
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        PayrollConfigurationCanonical canonical = new PayrollConfigurationCanonical(mapper);
        service = new PayrollConfigurationService(
                store,
                new PayrollFormulaEngine(canonical, System::nanoTime),
                canonical,
                CLOCK);
    }

    @Test
    void cleanV1ToV6ForcesRlsAndLimitsRuntimeGrants() {
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM flyway_schema_history
                 WHERE version IN ('1', '6') AND success
                """, Long.class)).isEqualTo(2L);
        assertThat(adminJdbc.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class
                 WHERE relname IN (
                     'pay_element_versions',
                     'pay_formula_versions',
                     'pay_configuration_command_receipts',
                     'pay_configuration_outbox_events')
                """, Boolean.class)).isTrue();

        JdbcTemplate runtimeJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        for (String table : List.of(
                "pay_element_versions",
                "pay_formula_versions",
                "pay_configuration_command_receipts",
                "pay_configuration_outbox_events")) {
            assertThat(runtimeJdbc.queryForObject(
                    "SELECT has_table_privilege(current_user, ?, 'SELECT,INSERT')",
                    Boolean.class, table)).isTrue();
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(runtimeJdbc.queryForObject(
                        "SELECT has_table_privilege(current_user, ?, ?)",
                        Boolean.class, table, privilege)).isFalse();
            }
        }
        assertThat(runtimeJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_formula_versions", Long.class)).isZero();
        assertThatThrownBy(() -> runtimeJdbc.update(
                "UPDATE pay_formula_versions SET lifecycle_state = 'DRAFT'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void tenantIsolationAndCanonicalReceiptReplayAreDurable() {
        var author = actor(1, 101, Action.FORMULA_VERSION_CREATE);
        UUID commandId = UUID.randomUUID();
        CreateFormulaVersionRequest request = formulaRequest();
        MutationResult created = service.createFormulaVersion(
                author, commandId, UUID.randomUUID().toString(), request);
        MutationResult replay = service.createFormulaVersion(
                author, commandId, created.receipt().correlationId(), request);

        assertThat(replay.receipt()).isEqualTo(created.receipt());
        assertThat(store.currentFormula(1, created.formula().formulaId())).isPresent();
        assertThat(store.currentFormula(2, created.formula().formulaId())).isEmpty();
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_formula_versions", Long.class)).isEqualTo(1L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_configuration_command_receipts", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForMap("""
                SELECT originating_action, purpose_code, receipt_status,
                       subject_principal_public_id, population_scope_digest,
                       field_policy_revision, authorization_revision,
                       correlation_id, request_digest
                  FROM pay_configuration_command_receipts
                 WHERE receipt_id = ?
                """, created.receipt().receiptId())).containsAllEntriesOf(Map.of(
                "originating_action", "PAY_FORMULA_CONFIGURE",
                "purpose_code", "PAYROLL_CONFIGURATION",
                "receipt_status", "SUCCEEDED",
                "subject_principal_public_id", created.receipt().subjectPrincipalPublicId(),
                "population_scope_digest", created.receipt().populationScopeDigest(),
                "field_policy_revision", created.receipt().fieldPolicyRevision(),
                "authorization_revision", created.receipt().authorizationRevision(),
                "correlation_id", created.receipt().correlationId(),
                "request_digest", created.receipt().requestDigest()));

        CreateFormulaVersionRequest changed = new CreateFormulaVersionRequest(
                null, null, LEGAL_ENTITY, PAYROLL_GROUP,
                "GROSS", "Changed display name", LocalDate.of(2026, 1, 1),
                GOLDEN_DIGEST, grossFormula());
        assertThatThrownBy(() -> service.createFormulaVersion(
                author, commandId, created.receipt().correlationId(), changed))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void successfulPublishIsAtomicAndReplayAppendsExactlyOneEvent() {
        var author = actor(
                1, 101,
                Action.FORMULA_VERSION_CREATE,
                Action.FORMULA_VALIDATE,
                Action.FORMULA_SIMULATE);
        var publisher = actor(1, 202, Action.FORMULA_PUBLISH);
        MutationResult created = service.createFormulaVersion(
                author, UUID.randomUUID(), UUID.randomUUID().toString(), formulaRequest());
        UUID formulaId = created.formula().formulaId();

        assertThatThrownBy(() -> service.publishFormula(
                publisher, formulaId, UUID.randomUUID(), UUID.randomUUID().toString(),
                new VersionCommand(1)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_configuration_outbox_events", Long.class)).isZero();

        service.validateFormula(
                author, formulaId, UUID.randomUUID(), UUID.randomUUID().toString(),
                new VersionCommand(1));
        service.simulateFormula(
                author, formulaId, UUID.randomUUID(), UUID.randomUUID().toString(),
                new SimulationCommand(
                        2,
                        Map.of(
                                "TIM.REGULAR_HOURS", new TypedValue(
                                        PayValueType.QUANTITY_HOUR, "100.00", null, null),
                                "HRM.HOURLY_RATE", new TypedValue(
                                        PayValueType.UNIT_PRICE_PER_HOUR,
                                        "0.125", null, "USD")),
                        new RoundingPolicy(2, RoundingMode.HALF_UP, "0.01"),
                        2_000));
        UUID publishCommand = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        MutationResult published = service.publishFormula(
                publisher, formulaId, publishCommand, correlationId,
                new VersionCommand(3));
        MutationResult replay = service.publishFormula(
                publisher, formulaId, publishCommand, correlationId,
                new VersionCommand(3));

        assertThat(replay.receipt()).isEqualTo(published.receipt());
        assertThat(replay.publicationEvent()).isEqualTo(published.publicationEvent());
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_formula_versions", Long.class)).isEqualTo(4L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_configuration_outbox_events", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_configuration_outbox_events event
                  JOIN pay_configuration_command_receipts receipt
                    ON receipt.tenant_id = event.tenant_id
                   AND receipt.receipt_id = event.receipt_id
                 WHERE event.event_type = 'PayrollConfigurationPublished'
                   AND event.schema_version = 1
                   AND event.aggregate_sequence = receipt.result_version
                """, Long.class)).isEqualTo(1L);
    }

    private CreateFormulaVersionRequest formulaRequest() {
        return new CreateFormulaVersionRequest(
                null, null, LEGAL_ENTITY, PAYROLL_GROUP,
                "GROSS", "Gross pay", LocalDate.of(2026, 1, 1),
                GOLDEN_DIGEST, grossFormula());
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class DatabaseTestConfiguration {

        @Bean
        DataSource dataSource() {
            return JdbcPayrollConfigurationStoreTest.dataSource(
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
        PayrollConfigurationStore payrollConfigurationStore(
                NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
            return new JdbcPayrollConfigurationStore(jdbc, objectMapper);
        }
    }
}
