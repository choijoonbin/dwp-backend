package com.dwp.services.payroll.statutory.pay005;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Pay005PostgresContractTest {

    private static final String RUNTIME = "pay005_runtime";
    private static final String RUNTIME_PASSWORD = "pay005-runtime-password";
    private static final String PUBLISHER = "pay005_projection_publisher";
    private static final String PUBLISHER_PASSWORD = "pay005-publisher-password";
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final Instant RECORDED =
            Instant.parse("2026-10-28T00:00:00Z");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID PAY_GROUP =
            UUID.fromString("50000000-0000-0000-0000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource adminSource;
    private static DataSource runtimeSource;
    private static DataSource publisherSource;
    private static JdbcTemplate admin;

    @BeforeAll
    static void migrate() throws Exception {
        adminSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        admin = new JdbcTemplate(adminSource);
        admin.execute("CREATE ROLE " + RUNTIME
                + " LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD + "'");
        admin.execute("CREATE ROLE " + PUBLISHER
                + " LOGIN NOINHERIT PASSWORD '" + PUBLISHER_PASSWORD + "'");
        Flyway.configure().dataSource(adminSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME,
                        "payrollProjectionPublisherRole", PUBLISHER))
                .load().migrate();
        runtimeSource = dataSource(
                POSTGRES.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
        publisherSource = dataSource(
                POSTGRES.getJdbcUrl(), PUBLISHER, PUBLISHER_PASSWORD);
    }

    @AfterAll
    static void release() {
        admin = null;
        runtimeSource = null;
        publisherSource = null;
        adminSource = null;
    }

    @BeforeEach
    void reset() {
        admin.execute("TRUNCATE TABLE pay_tax_result_facts, "
                + "pay_social_insurance_result_facts, "
                + "pay_country_pack_activation_versions, "
                + "pay_country_pack_version_snapshots, pay_result_lines, "
                + "pay_worker_results RESTART IDENTITY CASCADE");
    }

    @Test
    void publisherPersistsExactFactReplaysSameDigestAndConflictsOnChange() {
        Facts facts = seed(1, "GBP", "policy-v1");
        Pay005TaxResultOutcomePublisher publisher = publisher();
        Pay005Models.TaxResultProviderOutcome outcome = outcome(facts, RECORDED);
        Pay005Models.PreparedTaxResultFact prepared = publisher.prepare(outcome);

        publisher.publish(outcome);
        publisher.publish(outcome);

        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_tax_result_facts
                 WHERE tenant_id = 1 AND worker_result_id = ?
                   AND tax_result_fact_id = ?
                   AND provider_result_type = 'TAX_RESULT'
                   AND provider_source_semantics = 'TYPED_COUNTRY_RULE_OUTPUT'
                   AND source_line_set_digest = ?
                   AND provider_outcome_digest = ? AND result_digest = ?
                """, Long.class, facts.workerResult(),
                prepared.taxResultFactId(), prepared.sourceLineSetDigest(),
                prepared.providerOutcomeDigest(), prepared.resultDigest()))
                .isEqualTo(1L);

        assertThatThrownBy(() -> publisher.publish(
                outcome(facts, RECORDED.plusSeconds(1))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("another digest");
        assertThatThrownBy(() -> admin.update("""
                UPDATE pay_tax_result_facts SET amount = 9.0000
                 WHERE tenant_id = 1 AND worker_result_id = ?
                """, facts.workerResult()))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertThatThrownBy(() -> admin.update("""
                DELETE FROM pay_tax_result_facts
                 WHERE tenant_id = 1 AND worker_result_id = ?
                """, facts.workerResult()))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void rlsGrantsAndTenantIsolationAreExact() {
        Facts tenantOne = seed(1, "GBP", "policy-v1");
        publisher().publish(outcome(tenantOne, RECORDED));
        assertThat(privilege(RUNTIME, "SELECT")).isTrue();
        assertThat(privilege(RUNTIME, "INSERT")).isFalse();
        assertThat(privilege(PUBLISHER, "SELECT")).isTrue();
        assertThat(privilege(PUBLISHER, "INSERT")).isTrue();
        assertThat(privilege(PUBLISHER, "UPDATE")).isFalse();

        inTransaction(runtimeSource, jdbc -> {
            bind(jdbc, 1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_tax_result_facts", Long.class))
                    .isEqualTo(1L);
            bind(jdbc, 2);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_tax_result_facts", Long.class))
                    .isZero();
        });
    }

    @Test
    void sourceAndBidirectionalTaxSocialLineageFailClosed() {
        Facts facts = seed(1, "GBP", "policy-v1");
        Pay005TaxResultOutcomePublisher publisher = publisher();
        publisher.publish(outcome(facts, RECORDED));

        assertThatThrownBy(() -> directTax(
                facts, UUID.randomUUID(), facts.otherWorkerResult(),
                List.of(facts.lowLine()), new BigDecimal("4.2501"),
                facts.pack(), 4, "policy-v1", "GBP"))
                .hasRootCauseInstanceOf(PSQLException.class);

        UUID otherPack = UUID.randomUUID();
        seedPack(1, otherPack, "policy-v1");
        assertThatThrownBy(() -> directSocial(
                facts, otherPack, 4, "policy-v1"))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "Tax and social-insurance statutory lineage differs");
    }

    private boolean privilege(String role, String permission) {
        return Boolean.TRUE.equals(admin.queryForObject(
                "SELECT has_table_privilege(?, 'pay_tax_result_facts', ?)",
                Boolean.class, role, permission));
    }

    private Pay005TaxResultOutcomePublisher publisher() {
        return new Pay005TaxResultOutcomePublisher(
                new NamedParameterJdbcTemplate(publisherSource),
                new Pay005Canonical(JsonMapper.builder()
                        .findAndAddModules().build()));
    }

    private Facts seed(long tenant, String currency, String policy) {
        Facts facts = new Facts(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        inTransaction(adminSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            seedPack(jdbc, tenant, facts.pack(), policy);
            jdbc.update("""
                    INSERT INTO pay_worker_results (
                        tenant_id, run_id, attempt_no, worker_result_id,
                        worker_id, currency, gross_amount, deduction_amount,
                        net_amount, employer_cost_amount, line_count,
                        worker_result_digest, calculation_result_digest,
                        recorded_at)
                    VALUES (?, ?, 1, ?, ?, ?, 100.0000, 10.0000,
                        90.0000, 20.0000, 2, ?, ?, ?)
                    """, tenant, facts.run(), facts.workerResult(),
                    facts.worker(), currency, A, B, Timestamp.from(RECORDED));
            jdbc.update("""
                    INSERT INTO pay_worker_results (
                        tenant_id, run_id, attempt_no, worker_result_id,
                        worker_id, currency, gross_amount, deduction_amount,
                        net_amount, employer_cost_amount, line_count,
                        worker_result_digest, calculation_result_digest,
                        recorded_at)
                    VALUES (?, ?, 1, ?, ?, ?, 100.0000, 10.0000,
                        90.0000, 20.0000, 1, ?, ?, ?)
                    """, tenant, facts.run(), facts.otherWorkerResult(),
                    UUID.randomUUID(), currency, A, B, Timestamp.from(RECORDED));
            resultLine(jdbc, tenant, facts, facts.lowLine(), 501,
                    "TAX.INCOME", new BigDecimal("4.2500"), currency);
            resultLine(jdbc, tenant, facts, facts.highLine(), 502,
                    "TAX.LOCAL", new BigDecimal("5.7500"), currency);
        });
        return facts;
    }

    private void seedPack(long tenant, UUID pack, String policy) {
        inTransaction(adminSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            seedPack(jdbc, tenant, pack, policy);
        });
    }

    private void seedPack(
            JdbcTemplate jdbc, long tenant, UUID pack, String policy) {
        jdbc.update("""
                INSERT INTO pay_country_pack_version_snapshots (
                    tenant_id, pack_id, pack_key, pack_version,
                    snapshot_revision, legal_entity_id, payroll_group_id,
                    jurisdiction_code, lifecycle_state, payload_digest,
                    artifact_digest, signature_digest, signature_algorithm,
                    signing_key_id, compatibility_version,
                    policy_schema_version, official_reference_set,
                    golden_pack_digest, statutory_approval_receipt_id,
                    installer_subject_public_id,
                    statutory_approver_subject_public_id,
                    effective_from, effective_to, recorded_at)
                VALUES (?, ?, ?, '2026.10', 0, ?, ?, 'GB', 'AVAILABLE',
                    ?, ?, ?, 'ED25519', 'key-1', '1', ?, 'UK-REF-2026', ?,
                    ?, ?, ?, DATE '2026-10-01', NULL, ?)
                """, tenant, pack, "GB.TAX." + pack.toString()
                        .substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                LEGAL_ENTITY, PAY_GROUP, A, B, C, policy, A,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(RECORDED.minusSeconds(60)));
        jdbc.update("""
                INSERT INTO pay_country_pack_activation_versions (
                    tenant_id, pack_id, previous_revision,
                    activation_revision, lifecycle_state,
                    effective_from, effective_to,
                    activated_by_subject_public_id, receipt_id,
                    audit_event_id, correlation_id, occurred_at)
                VALUES (?, ?, 3, 4, 'ACTIVE', DATE '2026-10-01', NULL,
                    ?, ?, ?, ?, ?)
                """, tenant, pack, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(RECORDED.minusSeconds(30)));
    }

    private void resultLine(
            JdbcTemplate jdbc,
            long tenant,
            Facts facts,
            UUID line,
            long source,
            String component,
            BigDecimal amount,
            String currency) {
        jdbc.update("""
                INSERT INTO pay_result_lines (
                    tenant_id, run_id, attempt_no, input_snapshot_internal_id,
                    result_line_id, compensation_plan_input_id, worker_id,
                    worker_result_id, component_code, source_payload_digest,
                    semantic_binding_digest, element_id, element_version,
                    formula_id, formula_version, formula_ast_digest,
                    line_category, formula_input_digest, formula_output_digest,
                    currency, amount, terminal_trace_id, trace_digest,
                    result_line_digest, recorded_at)
                VALUES (?, ?, 1, 999, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 1, ?,
                    'DEDUCTION', ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenant, facts.run(), line, source, facts.worker(),
                facts.workerResult(), component, A, B, UUID.randomUUID(),
                UUID.randomUUID(), C, A, B, currency, amount,
                UUID.randomUUID(), C, A, Timestamp.from(RECORDED));
    }

    private Pay005Models.TaxResultProviderOutcome outcome(
            Facts facts, Instant recordedAt) {
        return new Pay005Models.TaxResultProviderOutcome(
                1, facts.run(), 1, facts.workerResult(), facts.pack(), 4,
                "policy-v1", new Pay005Models.PostedMoney(
                new BigDecimal("10.0000"), "GBP"),
                List.of(
                        new Pay005Models.SourceLine(
                                facts.highLine(), new Pay005Models.PostedMoney(
                                new BigDecimal("5.7500"), "GBP"),
                                "TYPED_COUNTRY_RULE_OUTPUT"),
                        new Pay005Models.SourceLine(
                                facts.lowLine(), new Pay005Models.PostedMoney(
                                new BigDecimal("4.2500"), "GBP"),
                                "TYPED_COUNTRY_RULE_OUTPUT")),
                C, recordedAt);
    }

    private void directTax(
            Facts facts, UUID fact, UUID workerResult, List<UUID> lines,
            BigDecimal amount, UUID pack, long revision,
            String policy, String currency) {
        String array = lines.stream().map(value -> "'" + value + "'::UUID")
                .reduce((left, right) -> left + "," + right).orElseThrow();
        admin.update("""
                INSERT INTO pay_tax_result_facts (
                    tenant_id, tax_result_fact_id, run_id, attempt_no,
                    worker_result_id, pack_id, activation_revision,
                    policy_version, provider_result_type,
                    provider_source_semantics, provider_outcome_digest,
                    amount, currency, source_result_line_ids,
                    source_line_set_digest, result_digest, recorded_at)
                VALUES (1, ?, ?, 1, ?, ?, ?, ?, 'TAX_RESULT',
                    'TYPED_COUNTRY_RULE_OUTPUT', ?, ?, ?, ARRAY[%s], ?, ?, ?)
                """.formatted(array), fact, facts.run(), workerResult, pack,
                revision, policy, A, amount, currency, B, C,
                Timestamp.from(RECORDED));
    }

    private void directSocial(
            Facts facts, UUID pack, long revision, String policy) {
        String array = List.of(facts.lowLine(), facts.highLine()).stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(value -> "'" + value + "'::UUID")
                .reduce((left, right) -> left + "," + right).orElseThrow();
        admin.update("""
                INSERT INTO pay_social_insurance_result_facts (
                    tenant_id, social_insurance_fact_id, run_id, attempt_no,
                    worker_result_id, pack_id, activation_revision,
                    policy_version, provider_result_type,
                    provider_source_semantics, provider_outcome_digest,
                    amount, currency, source_result_line_ids,
                    source_line_set_digest, result_digest, recorded_at)
                VALUES (1, ?, ?, 1, ?, ?, ?, ?, 'SOCIAL_INSURANCE',
                    'TYPED_COUNTRY_RULE_OUTPUT', ?, 10.0000, 'GBP',
                    ARRAY[%s], ?, ?, ?)
                """.formatted(array), UUID.randomUUID(), facts.run(),
                facts.workerResult(), pack, revision, policy, A, B, C,
                Timestamp.from(RECORDED.plusSeconds(1)));
    }

    private static void bind(JdbcTemplate jdbc, long tenant) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', ?, true)",
                String.class, Long.toString(tenant));
    }

    private static void inTransaction(
            DataSource source, java.util.function.Consumer<JdbcTemplate> work) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(ignored ->
                work.accept(new JdbcTemplate(source)));
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                url, username, password);
        source.setDriverClassName("org.postgresql.Driver");
        return source;
    }

    private record Facts(
            UUID run,
            UUID workerResult,
            UUID otherWorkerResult,
            UUID worker,
            UUID lowLine,
            UUID highLine,
            UUID pack) {
    }
}
