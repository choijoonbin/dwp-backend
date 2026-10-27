package com.dwp.services.payroll.statutory.pay004;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V55 PostgreSQL identities, immutable facts and mutation assertions. */
abstract class Pay004V55MigrationPostgresFixture {

    static final String RUNTIME = "pay004_runtime";

    static final String RUNTIME_PASSWORD = "pay004-runtime-password";

    static final String PUBLISHER = "pay004_projection_publisher";

    static final String PUBLISHER_PASSWORD =
            "pay004-projection-publisher-password";

    static final String UPGRADE_DATABASE = "pay004_v54_upgrade";

    static final List<String> TABLES = List.of(
            "pay_statutory_cases",
            "pay_case_basis_refs",
            "pay_case_evidence",
            "pay_case_results",
            "pay_case_corrections");

    static final Instant RECORDED =
            Instant.parse("2026-10-22T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource adminDataSource;

    static DataSource runtimeDataSource;

    static DataSource upgradeDataSource;

    static JdbcTemplate admin;

    static JdbcTemplate upgrade;

    @BeforeAll
    static void migrateFreshAndV54Upgrade() throws Exception {
        adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME
                    + " LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PUBLISHER
                    + " LOGIN NOINHERIT PASSWORD '" + PUBLISHER_PASSWORD + "'");
            statement.execute("CREATE DATABASE " + UPGRADE_DATABASE);
        }

        migrate(adminDataSource, null);
        admin = new JdbcTemplate(adminDataSource);
        runtimeDataSource = dataSource(
                POSTGRES.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);

        upgradeDataSource = dataSource(
                jdbcUrl(UPGRADE_DATABASE), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        migrate(upgradeDataSource, MigrationVersion.fromVersion("54"));
        upgrade = new JdbcTemplate(upgradeDataSource);
        upgrade.execute("CREATE TABLE pay004_upgrade_sentinel (id BIGINT PRIMARY KEY)");
        upgrade.update("INSERT INTO pay004_upgrade_sentinel (id) VALUES (1)");
        migrate(upgradeDataSource, null);
    }

    @AfterAll
    static void releaseReferences() {
        upgrade = null;
        admin = null;
        runtimeDataSource = null;
        upgradeDataSource = null;
        adminDataSource = null;
    }

    @BeforeEach
    void resetPay004Facts() {
        admin.execute("TRUNCATE TABLE " + String.join(", ", TABLES)
                + " RESTART IDENTITY CASCADE");
    }

    boolean privilege(String role, String table, String permission) {
        if ("PUBLIC".equals(role)) {
            return Boolean.TRUE.equals(admin.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM information_schema.table_privileges
                         WHERE grantee = 'PUBLIC'
                           AND table_schema = current_schema()
                           AND table_name = ?
                           AND privilege_type = ?)
                    """, Boolean.class, table, permission));
        }
        return Boolean.TRUE.equals(admin.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)",
                Boolean.class, role, table, permission));
    }

    static void insertDraft(
            JdbcTemplate jdbc, long tenant, UUID caseId) {
        jdbc.update("""
                INSERT INTO pay_statutory_cases (
                    tenant_id, case_id, case_revision, worker_id,
                    employment_owner, employment_schema_id,
                    employment_public_id, employment_revision,
                    employment_digest, legal_entity_id, payroll_group_id,
                    effective_on, lifecycle_state, country_pack_state,
                    maker_subject_public_id, transition_subject_public_id,
                    predecessor_digest,
                    case_digest, recorded_at)
                VALUES (?, ?, 1, ?, 'HRM', 'HRM.EmploymentTermination.v1',
                    ?, 1, ?, ?, ?, ?, 'DRAFT', 'NOT_REQUIRED',
                    NULL, ?, NULL, ?, ?)
                """, tenant, caseId, UUID.randomUUID(), UUID.randomUUID(),
                Pay004TestFixtures.A, UUID.randomUUID(), UUID.randomUUID(),
                java.sql.Date.valueOf(LocalDate.parse("2026-10-22")),
                UUID.randomUUID(), Pay004TestFixtures.B,
                Timestamp.from(RECORDED));
    }

    static void insertFirstCase(
            JdbcTemplate jdbc,
            Fixture fixture,
            String lifecycleState,
            String countryPackState,
            String employmentDigest,
            String caseDigest) {
        jdbc.update("""
                INSERT INTO pay_statutory_cases (
                    tenant_id, case_id, case_revision, worker_id,
                    employment_owner, employment_schema_id,
                    employment_public_id, employment_revision,
                    employment_digest, legal_entity_id, payroll_group_id,
                    effective_on, lifecycle_state, country_pack_state,
                    maker_subject_public_id, transition_subject_public_id,
                    predecessor_digest, case_digest, recorded_at)
                VALUES (?, ?, 1, ?, 'HRM', 'HRM.EmploymentTermination.v1',
                    ?, 3, ?, ?, ?, DATE '2026-10-22', ?, ?, NULL, ?, NULL,
                    ?, ?)
                """, fixture.tenant(), fixture.caseId(), fixture.worker(),
                fixture.employment(), employmentDigest, fixture.legalEntity(),
                fixture.payrollGroup(), lifecycleState, countryPackState,
                fixture.maker(), caseDigest, Timestamp.from(RECORDED));
    }

    static void insertCaseRevision(
            JdbcTemplate jdbc,
            Fixture fixture,
            long revision,
            String lifecycleState,
            String countryPackState,
            UUID maker,
            UUID transitionActor) {
        insertCaseRevision(jdbc, fixture, revision, lifecycleState,
                countryPackState, maker, transitionActor,
                RECORDED.plusSeconds(revision));
    }

    static void insertCaseRevision(
            JdbcTemplate jdbc,
            Fixture fixture,
            long revision,
            String lifecycleState,
            String countryPackState,
            UUID maker,
            UUID transitionActor,
            Instant recordedAt) {
        jdbc.update("""
                INSERT INTO pay_statutory_cases (
                    tenant_id, case_id, case_revision, worker_id,
                    employment_owner, employment_schema_id,
                    employment_public_id, employment_revision,
                    employment_digest, legal_entity_id, payroll_group_id,
                    effective_on, lifecycle_state, country_pack_state,
                    maker_subject_public_id, transition_subject_public_id,
                    predecessor_digest, case_digest, recorded_at)
                VALUES (?, ?, ?, ?, 'HRM', 'HRM.EmploymentTermination.v1',
                    ?, 3, ?, ?, ?, DATE '2026-10-22', ?, ?, ?, ?, ?, ?, ?)
                """, fixture.tenant(), fixture.caseId(), revision,
                fixture.worker(), fixture.employment(), Pay004TestFixtures.A,
                fixture.legalEntity(), fixture.payrollGroup(), lifecycleState,
                countryPackState, maker, transitionActor,
                revision == 1 ? null : digestFor(revision - 1),
                digestFor(revision),
                Timestamp.from(recordedAt));
    }

    static void insertCalculatedRevisions(
            JdbcTemplate jdbc, Fixture fixture, String countryPackState) {
        insertCalculatedRevisions(jdbc, fixture, countryPackState,
                RECORDED.plusSeconds(100));
    }

    static void insertCalculatedRevisions(
            JdbcTemplate jdbc,
            Fixture fixture,
            String countryPackState,
            Instant calculatedAt) {
        insertCaseRevision(jdbc, fixture, 1, "DRAFT", countryPackState,
                null, fixture.maker());
        insertCaseRevision(jdbc, fixture, 2, "VALIDATED", countryPackState,
                fixture.maker(), fixture.maker());
        insertCaseRevision(jdbc, fixture, 3, "CALCULATED", countryPackState,
                fixture.maker(), fixture.maker(), calculatedAt);
    }

    static void insertResult(JdbcTemplate jdbc, ResultInput input) {
        Fixture fixture = input.fixture;
        jdbc.update("""
                INSERT INTO pay_case_results (
                    tenant_id, result_id, case_id, case_revision,
                    worker_id, run_id, run_revision, attempt_no,
                    worker_result_id, country_pack_state,
                    pack_id, pack_snapshot_revision, activation_revision,
                    non_pack_policy_public_id, non_pack_policy_revision,
                    non_pack_policy_digest, policy_digest,
                    policy_key, policy_revision,
                    policy_effective_from, policy_effective_to,
                    retirement_amount_text, currency,
                    rounding_mode, rounding_stage,
                    worker_result_digest, calculation_result_digest,
                    case_inputs_digest, result_digest,
                    calculated_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?)
                """, fixture.tenant(), input.resultId, fixture.caseId(),
                input.caseRevision, input.worker, fixture.run(),
                input.runRevision, input.workerResult,
                input.countryPackState, input.packId,
                input.packSnapshotRevision, input.activationRevision,
                input.nonPackPolicyId, input.nonPackPolicyRevision,
                input.nonPackPolicyDigest, input.suppliedPolicyDigest,
                input.policyKey,
                input.policyRevision,
                java.sql.Date.valueOf(input.policyEffectiveFrom),
                input.policyEffectiveTo == null ? null
                        : java.sql.Date.valueOf(input.policyEffectiveTo),
                input.amountText, input.currency, input.roundingMode,
                input.roundingStage, input.workerDigest,
                input.calculationDigest, input.suppliedCaseInputsDigest,
                input.suppliedResultDigest,
                Timestamp.from(input.calculatedAt),
                Timestamp.from(input.calculatedAt.plusSeconds(1)));
    }

    static void insertResultOverridingGeneratedAmount(
            JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO pay_case_results (retirement_amount)
                VALUES (999.0000)
                """);
    }

    static void persistCalculated(
            Fixture fixture, ResultInput input) {
        inTransaction(adminDataSource, jdbc -> {
            insertCalculatedRevisions(
                    jdbc, fixture, input.countryPackState,
                    input.calculatedAt);
            insertAttachments(jdbc, fixture);
            insertResult(jdbc, input);
        });
    }

    static void assertResultRejected(
            Fixture fixture, ResultInput input, String expectedMessage) {
        var failure = assertThatThrownBy(
                () -> persistCalculated(fixture, input))
                .hasRootCauseInstanceOf(PSQLException.class);
        failure.rootCause().hasMessageContaining(expectedMessage);
        assertCaseRevisionCount(fixture, 0L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_case_results
                 WHERE tenant_id = ? AND case_id = ?
                """, Long.class, fixture.tenant(), fixture.caseId()))
                .isZero();
    }

    static void assertCaseRevisionCount(
            Fixture fixture, long expected) {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_statutory_cases
                 WHERE tenant_id = ? AND case_id = ?
                """, Long.class, fixture.tenant(), fixture.caseId()))
                .isEqualTo(expected);
    }

    static ResultInput completeFinalizedFixture(Fixture fixture) {
        seedV50(fixture);
        ResultInput result = ResultInput.nonPack(fixture);
        inTransaction(adminDataSource, jdbc -> {
            insertCalculatedRevisions(jdbc, fixture, "NOT_REQUIRED",
                    result.calculatedAt);
            insertAttachments(jdbc, fixture);
            insertResult(jdbc, result);
        });
        insertCaseRevision(admin, fixture, 4, "APPROVED", "NOT_REQUIRED",
                fixture.maker(), fixture.checker(),
                RECORDED.plusSeconds(102));
        insertCaseRevision(admin, fixture, 5, "FINALIZED", "NOT_REQUIRED",
                fixture.maker(), fixture.checker(),
                RECORDED.plusSeconds(103));
        return result;
    }

    static void completeCorrectedFixture(Fixture fixture) {
        ResultInput result = completeFinalizedFixture(fixture);
        inTransaction(adminDataSource, jdbc -> {
            insertCaseRevision(jdbc, fixture, 6, "CORRECTED",
                    "NOT_REQUIRED", fixture.maker(), fixture.checker(),
                    RECORDED.plusSeconds(104));
            insertCorrection(jdbc, fixture, result.resultId);
        });
    }

    static void insertAttachments(
            JdbcTemplate jdbc, Fixture fixture) {
        insertBasisRef(jdbc, fixture, 3);
        insertEvidence(jdbc, fixture, 3);
    }

    static void insertBasisRef(
            JdbcTemplate jdbc, Fixture fixture, long caseRevision) {
        insertBasisRef(jdbc, fixture, caseRevision,
                RECORDED.plusSeconds(100));
    }

    static void insertBasisRef(
            JdbcTemplate jdbc,
            Fixture fixture,
            long caseRevision,
            Instant recordedAt) {
        jdbc.update("""
                INSERT INTO pay_case_basis_refs (
                    tenant_id, basis_ref_id, case_id, case_revision,
                    basis_role, owner_service_key, schema_id,
                    resource_public_id, resource_revision, resource_digest,
                    recorded_at)
                VALUES (?, ?, ?, ?, 'RETIREMENT_BASIS', 'HRM',
                    'HRM.EmploymentTermination.v1', ?, 3, ?, ?)
                """, fixture.tenant(), UUID.randomUUID(), fixture.caseId(),
                caseRevision, fixture.employment(), Pay004TestFixtures.A,
                Timestamp.from(recordedAt));
    }

    static void insertEvidence(
            JdbcTemplate jdbc, Fixture fixture, long caseRevision) {
        jdbc.update("""
                INSERT INTO pay_case_evidence (
                    tenant_id, evidence_id, case_id, case_revision,
                    evidence_type, evidence_owner, evidence_schema_id,
                    evidence_public_id, evidence_revision, evidence_digest,
                    recorded_at)
                VALUES (?, ?, ?, ?, 'TERMINATION_NOTICE', 'HRM',
                    'HRM.TerminationNotice.v1', ?, 1, ?, ?)
                """, fixture.tenant(), UUID.randomUUID(), fixture.caseId(),
                caseRevision, UUID.randomUUID(), Pay004TestFixtures.B,
                Timestamp.from(RECORDED.plusSeconds(100)));
    }

    static void insertCorrection(
            JdbcTemplate jdbc, Fixture fixture, UUID predecessorResultId) {
        jdbc.update("""
                INSERT INTO pay_case_corrections (
                    tenant_id, correction_id, case_id,
                    finalized_case_revision, corrected_case_revision,
                    predecessor_result_id, correction_reason_code,
                    correction_evidence_digest,
                    correction_maker_subject_public_id, recorded_at)
                VALUES (?, ?, ?, 5, 6, ?, 'FINAL_PAY_REVIEW', ?, ?, ?)
                """, fixture.tenant(), UUID.randomUUID(), fixture.caseId(),
                predecessorResultId, Pay004TestFixtures.C,
                fixture.checker(), Timestamp.from(RECORDED.plusSeconds(106)));
    }

    static void seedV50(Fixture fixture) {
        seedV50Facts(fixture, 3, "GBP", "GBP", Pay004TestFixtures.A,
                Pay004TestFixtures.B, Pay004TestFixtures.B,
                RECORDED, RECORDED);
        seedFoundation(FoundationInput.exact(fixture));
    }

    static void seedV50WithoutFoundation(Fixture fixture) {
        seedV50Facts(fixture, 3, "GBP", "GBP", Pay004TestFixtures.A,
                Pay004TestFixtures.B, Pay004TestFixtures.B,
                RECORDED, RECORDED);
    }

    static void seedV50(
            Fixture fixture,
            long runRevision,
            String runCurrency,
            String workerCurrency,
            String workerDigest,
            String calculationDigest,
            String runResultDigest,
            Instant runRecordedAt,
            Instant workerRecordedAt) {
        seedV50Facts(fixture, runRevision, runCurrency, workerCurrency,
                workerDigest, calculationDigest, runResultDigest,
                runRecordedAt, workerRecordedAt);
        seedFoundation(FoundationInput.exact(fixture));
    }

    static void seedV50Facts(
            Fixture fixture,
            long runRevision,
            String runCurrency,
            String workerCurrency,
            String workerDigest,
            String calculationDigest,
            String runResultDigest,
            Instant runRecordedAt,
            Instant workerRecordedAt) {
        inTransaction(adminDataSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRunRevision(jdbc, fixture, runRevision, runCurrency,
                    runResultDigest, runRecordedAt);
            jdbc.update("""
                    INSERT INTO pay_worker_results (
                        tenant_id, run_id, attempt_no, worker_result_id,
                        worker_id, currency, gross_amount, deduction_amount,
                        net_amount, employer_cost_amount, line_count,
                        worker_result_digest, calculation_result_digest,
                        recorded_at)
                    VALUES (?, ?, 1, ?, ?, ?, 100.0000, 10.0000,
                        90.0000, 20.0000, 1, ?, ?, ?)
                    """, fixture.tenant(), fixture.run(),
                    fixture.workerResult(), fixture.worker(), workerCurrency,
                    workerDigest, calculationDigest,
                    Timestamp.from(workerRecordedAt));
        });
    }

    static void seedFoundation(FoundationInput input) {
        Fixture fixture = input.fixture;
        admin.update("""
                INSERT INTO pay_foundation_configurations (
                    tenant_id, configuration_id, current_version,
                    lifecycle_state, legal_entity_id, payroll_group_id,
                    effective_from, effective_to, author_id, publisher_id,
                    simulation_report, last_command_id, created_at, updated_at)
                VALUES (?, ?, 7, 'PUBLISHED', ?, ?, DATE '2026-10-01', NULL,
                    101, 202, '{}'::JSONB, ?, ?, ?)
                """, fixture.tenant(), fixture.nonPackPolicy(),
                fixture.legalEntity(), fixture.payrollGroup(),
                UUID.randomUUID(), Timestamp.from(RECORDED),
                Timestamp.from(RECORDED));
        if (input.includeVersion) {
            insertFoundationVersion(admin, input);
        }
    }

    static void insertFoundationVersion(
            JdbcTemplate jdbc, FoundationInput input) {
        Fixture fixture = input.fixture;
        String definition = """
                {"legalEntity":{"id":"%s"},
                 "payrollGroup":{"id":"%s"},
                 "effectivePeriod":{"startsOn":"%s","endsOn":%s},
                 "roundingPolicies":{"GBP":{"scale":4,
                     "mode":"HALF_EVEN","increment":0.0001}}}
                """.formatted(
                input.definitionLegalEntity,
                input.definitionPayrollGroup,
                input.definitionEffectiveFrom,
                input.definitionEffectiveTo == null
                        ? "null"
                        : "\"" + input.definitionEffectiveTo + "\"");
        jdbc.update("""
                INSERT INTO pay_foundation_versions (
                    tenant_id, configuration_id, version, definition,
                    definition_digest, dependency_digest, lifecycle_state,
                    simulation_report, authored_by, published_by,
                    command_id, recorded_at)
                VALUES (?, ?, 7, CAST(? AS JSONB), ?, ?, ?, '{}'::JSONB,
                    101, 202, ?, ?)
                """, fixture.tenant(), fixture.nonPackPolicy(), definition,
                input.definitionDigest, Pay004TestFixtures.A,
                input.versionState, UUID.randomUUID(),
                Timestamp.from(input.versionRecordedAt));
    }

    static void seedRunRevision(
            Fixture fixture, long revision, Instant recordedAt) {
        inTransaction(adminDataSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRunRevision(jdbc, fixture, revision, "GBP",
                    Pay004TestFixtures.B, recordedAt);
        });
    }

    static void insertRunRevision(
            JdbcTemplate jdbc,
            Fixture fixture,
            long revision,
            String currency,
            String resultDigest,
            Instant recordedAt) {
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type,
                    pay_period_id, source_snapshot_id,
                    source_snapshot_revision, source_payload_digest,
                    legal_entity_id, payroll_group_id, population_kind,
                    population_scope_digest, state, maker_actor_id,
                    maker_subject_public_id, latest_attempt_no, worker_count,
                    gross_amount, net_amount, currency, result_digest,
                    recorded_at)
                VALUES (?, ?, ?, 'REGULAR', ?, ?, 1, ?, ?, ?, 'PAY_GROUP',
                    ?, 'VALIDATED', 91, ?, 1, 1, 100.0000, 90.0000,
                    ?, ?, ?)
                """, fixture.tenant(), fixture.run(), revision,
                UUID.randomUUID(), UUID.randomUUID(), Pay004TestFixtures.A,
                fixture.legalEntity(), fixture.payrollGroup(),
                Pay004TestFixtures.B, fixture.maker(), currency, resultDigest,
                Timestamp.from(recordedAt));
    }

    static void seedActivePack(
            Fixture fixture,
            UUID legalEntity,
            UUID payrollGroup,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant snapshotRecordedAt,
            Instant activationOccurredAt) {
        seedActivePack(fixture, legalEntity, payrollGroup, effectiveFrom,
                effectiveTo, snapshotRecordedAt, activationOccurredAt,
                "GB.RETIREMENT");
    }

    static void seedActivePack(
            Fixture fixture,
            UUID legalEntity,
            UUID payrollGroup,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant snapshotRecordedAt,
            Instant activationOccurredAt,
            String packKey) {
        inTransaction(adminDataSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            UUID installer = UUID.randomUUID();
            UUID approver = UUID.randomUUID();
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
                    VALUES (?, ?, ?, '2026.1', 0, ?, ?, 'GB',
                        'AVAILABLE', ?, ?, ?, 'ED25519', 'pay004-key', '1',
                        'retirement-v1', 'UK-RETIREMENT-2026', ?, ?, ?, ?,
                        ?, ?, ?)
                    """, fixture.tenant(), fixture.pack(), packKey, legalEntity,
                    payrollGroup, Pay004TestFixtures.A, Pay004TestFixtures.B,
                    Pay004TestFixtures.C, Pay004TestFixtures.A,
                    UUID.randomUUID(), installer, approver,
                    java.sql.Date.valueOf(effectiveFrom),
                    effectiveTo == null ? null
                            : java.sql.Date.valueOf(effectiveTo),
                    Timestamp.from(snapshotRecordedAt));
            jdbc.update("""
                    INSERT INTO pay_country_pack_activation_versions (
                        tenant_id, pack_id, previous_revision,
                        activation_revision, lifecycle_state,
                        effective_from, effective_to,
                        activated_by_subject_public_id, receipt_id,
                        audit_event_id, correlation_id, occurred_at)
                    VALUES (?, ?, 3, 4, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                    """, fixture.tenant(), fixture.pack(),
                    java.sql.Date.valueOf(effectiveFrom),
                    effectiveTo == null ? null
                            : java.sql.Date.valueOf(effectiveTo),
                    fixture.checker(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Timestamp.from(activationOccurredAt));
        });
    }

    static void seedLaterActivation(
            Fixture fixture, Instant occurredAt) {
        inTransaction(adminDataSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO pay_country_pack_activation_versions (
                        tenant_id, pack_id, previous_revision,
                        activation_revision, lifecycle_state,
                        effective_from, effective_to,
                        activated_by_subject_public_id, receipt_id,
                        audit_event_id, correlation_id, occurred_at)
                    VALUES (?, ?, 4, 5, 'SUSPENDED', DATE '2026-10-01', NULL,
                        ?, NULL, NULL, NULL, ?)
                    """, fixture.tenant(), fixture.pack(), fixture.checker(),
                    Timestamp.from(occurredAt));
        });
    }

    static String digestFor(long revision) {
        return switch ((int) (revision % 3)) {
            case 0 -> Pay004TestFixtures.A;
            case 1 -> Pay004TestFixtures.B;
            default -> Pay004TestFixtures.C;
        };
    }

    static String fallbackPolicyKey(String rawPackKey) {
        try {
            byte[] jsonString = ('"' + rawPackKey + '"').getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            String digest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(jsonString));
            return "statutory." + digest.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Fixture(
            long tenant,
            UUID caseId,
            UUID worker,
            UUID employment,
            UUID legalEntity,
            UUID payrollGroup,
            UUID maker,
            UUID checker,
            UUID run,
            UUID workerResult,
            UUID pack,
            UUID nonPackPolicy) {

        static Fixture fresh(long tenant) {
            return new Fixture(
                    tenant, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }
    }

    static final class FoundationInput {
        final Fixture fixture;
        boolean includeVersion = true;
        String versionState = "PUBLISHED";
        String definitionDigest = Pay004TestFixtures.B;
        Instant versionRecordedAt = RECORDED;
        UUID definitionLegalEntity;
        UUID definitionPayrollGroup;
        LocalDate definitionEffectiveFrom =
                LocalDate.parse("2026-10-01");
        LocalDate definitionEffectiveTo;

        FoundationInput(Fixture fixture) {
            this.fixture = fixture;
            definitionLegalEntity = fixture.legalEntity();
            definitionPayrollGroup = fixture.payrollGroup();
        }

        static FoundationInput exact(Fixture fixture) {
            return new FoundationInput(fixture);
        }

        FoundationInput withoutVersion() {
            includeVersion = false;
            return this;
        }

        FoundationInput versionState(String value) {
            versionState = value;
            return this;
        }

        FoundationInput definitionDigest(String value) {
            definitionDigest = value;
            return this;
        }

        FoundationInput definitionScope(
                UUID legalEntity, UUID payrollGroup) {
            definitionLegalEntity = legalEntity;
            definitionPayrollGroup = payrollGroup;
            return this;
        }

        FoundationInput definitionEffectiveFrom(LocalDate value) {
            definitionEffectiveFrom = value;
            return this;
        }

        FoundationInput versionRecordedAt(Instant value) {
            versionRecordedAt = value;
            return this;
        }
    }

    static final class ResultInput {
        final Fixture fixture;
        final UUID resultId = UUID.randomUUID();
        long caseRevision = 3;
        UUID worker;
        long runRevision = 3;
        UUID workerResult;
        String countryPackState;
        UUID packId;
        Long packSnapshotRevision;
        Long activationRevision;
        UUID nonPackPolicyId;
        Long nonPackPolicyRevision;
        String nonPackPolicyDigest;
        String policyKey;
        long policyRevision;
        LocalDate policyEffectiveFrom =
                LocalDate.parse("2026-10-01");
        LocalDate policyEffectiveTo;
        String amountText = "1234.5000";
        String currency = "GBP";
        String roundingMode = "HALF_EVEN";
        String roundingStage = "RETIREMENT_FINAL";
        String workerDigest = Pay004TestFixtures.A;
        String calculationDigest = Pay004TestFixtures.B;
        String suppliedPolicyDigest;
        String suppliedCaseInputsDigest;
        String suppliedResultDigest;
        Instant calculatedAt = RECORDED.plusSeconds(100);

        ResultInput(Fixture fixture) {
            this.fixture = fixture;
            worker = fixture.worker();
            workerResult = fixture.workerResult();
        }

        static ResultInput nonPack(Fixture fixture) {
            ResultInput input = new ResultInput(fixture);
            input.countryPackState = "NOT_REQUIRED";
            input.nonPackPolicyId = fixture.nonPackPolicy();
            input.nonPackPolicyRevision = 7L;
            input.nonPackPolicyDigest = Pay004TestFixtures.B;
            input.policyKey = "pay.retirement.foundation";
            input.policyRevision = 7;
            return input;
        }

        static ResultInput active(Fixture fixture) {
            ResultInput input = new ResultInput(fixture);
            input.countryPackState = "ACTIVE";
            input.packId = fixture.pack();
            input.packSnapshotRevision = 0L;
            input.activationRevision = 4L;
            input.policyKey = "gb.retirement";
            input.policyRevision = 4;
            return input;
        }

        ResultInput caseRevision(long value) {
            caseRevision = value;
            return this;
        }

        ResultInput runRevision(long value) {
            runRevision = value;
            return this;
        }

        ResultInput worker(UUID value) {
            worker = value;
            return this;
        }

        ResultInput currency(String value) {
            currency = value;
            return this;
        }

        ResultInput workerDigest(String value) {
            workerDigest = value;
            return this;
        }

        ResultInput calculationDigest(String value) {
            calculationDigest = value;
            return this;
        }

        ResultInput countryPackState(String value) {
            countryPackState = value;
            return this;
        }

        ResultInput withoutNonPackPolicy() {
            nonPackPolicyId = null;
            nonPackPolicyRevision = null;
            nonPackPolicyDigest = null;
            return this;
        }

        ResultInput alsoReferencesPack(UUID value) {
            packId = value;
            packSnapshotRevision = 0L;
            activationRevision = 4L;
            return this;
        }

        ResultInput amountText(String value) {
            amountText = value;
            return this;
        }

        ResultInput policyKey(String value) {
            policyKey = value;
            return this;
        }

        ResultInput roundingMode(String value) {
            roundingMode = value;
            return this;
        }

        ResultInput suppliedPolicyDigest(String value) {
            suppliedPolicyDigest = value;
            return this;
        }

        ResultInput suppliedCaseInputsDigest(String value) {
            suppliedCaseInputsDigest = value;
            return this;
        }

        ResultInput suppliedResultDigest(String value) {
            suppliedResultDigest = value;
            return this;
        }
    }

    static void bind(JdbcTemplate jdbc, long tenant) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', ?, true)",
                String.class, Long.toString(tenant));
    }

    static void inTransaction(
            DataSource source, java.util.function.Consumer<JdbcTemplate> work) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(ignored ->
                work.accept(new JdbcTemplate(source)));
    }

    static void migrate(DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME,
                        "payrollProjectionPublisherRole", PUBLISHER));
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    static DataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    static String sqlNames(List<String> names) {
        return names.stream().map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }
}
