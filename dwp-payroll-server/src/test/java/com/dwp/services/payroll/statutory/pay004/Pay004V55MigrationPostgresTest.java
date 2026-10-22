package com.dwp.services.payroll.statutory.pay004;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

@Testcontainers
class Pay004V55MigrationPostgresTest {

    private static final String RUNTIME = "pay004_runtime";
    private static final String RUNTIME_PASSWORD = "pay004-runtime-password";
    private static final String PUBLISHER = "pay004_projection_publisher";
    private static final String PUBLISHER_PASSWORD =
            "pay004-projection-publisher-password";
    private static final String UPGRADE_DATABASE = "pay004_v54_upgrade";
    private static final List<String> TABLES = List.of(
            "pay_statutory_cases",
            "pay_case_basis_refs",
            "pay_case_evidence",
            "pay_case_results",
            "pay_case_corrections");
    private static final Instant RECORDED =
            Instant.parse("2026-10-22T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource adminDataSource;
    private static DataSource runtimeDataSource;
    private static DataSource upgradeDataSource;
    private static JdbcTemplate admin;
    private static JdbcTemplate upgrade;

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

    @Test
    void cleanAndV54UpgradeCreateExactlyFiveHardenedTablesWithoutDataLoss() {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '55' AND success
                """, Long.class)).isEqualTo(1L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('54', '55') AND success
                """, Long.class)).isEqualTo(2L);
        assertThat(upgrade.queryForObject(
                "SELECT COUNT(*) FROM pay004_upgrade_sentinel",
                Long.class)).isEqualTo(1L);
        assertThat(admin.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relkind = 'r' AND relname IN (%s)
                 ORDER BY relname
                """.formatted(sqlNames(TABLES)), String.class))
                .containsExactlyElementsOf(TABLES.stream().sorted().toList());
        assertThat(admin.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class WHERE relname IN (%s)
                """.formatted(sqlNames(TABLES)), Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = current_schema()
                   AND tablename IN (%s)
                   AND qual LIKE '%%dwp.payroll_tenant_id%%'
                   AND with_check LIKE '%%dwp.payroll_tenant_id%%'
                """.formatted(sqlNames(TABLES)), Long.class)).isEqualTo(5L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal
                   AND tgname LIKE 'pay_%%_immutable'
                   AND tgrelid::regclass::text IN (%s)
                """.formatted(sqlNames(TABLES)), Long.class)).isEqualTo(5L);
    }

    @Test
    void runtimeRoleHasOnlySelectInsertAndCannotBypassRls() {
        for (String table : TABLES) {
            assertThat(privilege(RUNTIME, table, "SELECT")).isTrue();
            assertThat(privilege(RUNTIME, table, "INSERT")).isTrue();
            assertThat(privilege(RUNTIME, table, "UPDATE")).isFalse();
            assertThat(privilege(RUNTIME, table, "DELETE")).isFalse();
            assertThat(privilege("PUBLIC", table, "SELECT")).isFalse();
        }
        assertThat(admin.queryForObject("""
                SELECT rolsuper OR rolcreaterole OR rolcreatedb
                    OR rolreplication OR rolbypassrls
                  FROM pg_roles WHERE rolname = ?
                """, Boolean.class, RUNTIME)).isFalse();

        completeCorrectedFixture(Fixture.fresh(1));
        completeCorrectedFixture(Fixture.fresh(2));

        inTransaction(runtimeDataSource, jdbc -> {
            for (String table : TABLES) {
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Long.class)).isZero();
            }
        });
        inTransaction(runtimeDataSource, jdbc -> {
            bind(jdbc, 1);
            for (String table : TABLES) {
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Long.class))
                        .as(table).isPositive();
            }
        });
        assertThatThrownBy(() -> inTransaction(runtimeDataSource, jdbc -> {
            bind(jdbc, 1);
            insertDraft(jdbc, 2, UUID.randomUUID());
        })).hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void closedStateAndLowercaseDigestRulesFailForIndependentReasons() {
        Fixture unknown = Fixture.fresh(1);
        assertThatThrownBy(() -> insertFirstCase(
                admin, unknown, "UNKNOWN", "NOT_REQUIRED",
                Pay004TestFixtures.A, Pay004TestFixtures.B))
                .hasRootCauseInstanceOf(PSQLException.class);

        Fixture uppercaseDigest = Fixture.fresh(1);
        assertThatThrownBy(() -> insertFirstCase(
                admin, uppercaseDigest, "DRAFT", "NOT_REQUIRED",
                "A".repeat(64), Pay004TestFixtures.B))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "pay_statutory_case_digest_shape");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_statutory_cases", Long.class))
                .isZero();
    }

    @Test
    void exactRunRevisionWorkerCurrencyAndBothV50DigestsAreRequired() {
        Fixture accepted = Fixture.fresh(1);
        seedV50(accepted);
        ResultInput acceptedInput = ResultInput.nonPack(accepted);
        persistCalculated(accepted, acceptedInput);
        Map<String, Object> stored = admin.queryForMap("""
                SELECT run_revision, worker_id, currency,
                       worker_result_digest, calculation_result_digest,
                       policy_digest, case_inputs_digest, result_digest
                  FROM pay_case_results
                 WHERE tenant_id = ? AND result_id = ?
                """, accepted.tenant(), acceptedInput.resultId);
        assertThat(((Number) stored.get("run_revision")).longValue())
                .isEqualTo(3L);
        assertThat(stored.get("worker_id")).isEqualTo(accepted.worker());
        assertThat(stored.get("currency")).isEqualTo("GBP");
        assertThat(stored.get("worker_result_digest"))
                .isEqualTo(Pay004TestFixtures.A);
        assertThat(stored.get("calculation_result_digest"))
                .isEqualTo(Pay004TestFixtures.B);
        assertThat(stored.get("policy_digest"))
                .isEqualTo(Pay004TestFixtures.B);
        assertThat(stored.get("case_inputs_digest")).asString()
                .matches("[0-9a-f]{64}");
        assertThat(stored.get("result_digest")).asString()
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(Pay004TestFixtures.C);

        Fixture wrongRevision = Fixture.fresh(1);
        seedV50(wrongRevision);
        assertResultRejected(wrongRevision,
                ResultInput.nonPack(wrongRevision).runRevision(4),
                "Exact case and V50 worker-result lineage differs");

        Fixture wrongWorker = Fixture.fresh(1);
        seedV50(wrongWorker);
        assertResultRejected(wrongWorker,
                ResultInput.nonPack(wrongWorker).worker(UUID.randomUUID()),
                "Exact case and V50 worker-result lineage differs");

        Fixture crossCurrency = Fixture.fresh(1);
        seedV50(crossCurrency);
        assertResultRejected(crossCurrency,
                ResultInput.nonPack(crossCurrency).currency("USD"),
                "Exact case and V50 worker-result lineage differs");

        Fixture wrongWorkerDigest = Fixture.fresh(1);
        seedV50(wrongWorkerDigest);
        assertResultRejected(wrongWorkerDigest,
                ResultInput.nonPack(wrongWorkerDigest)
                        .workerDigest(Pay004TestFixtures.C),
                "Exact case and V50 worker-result lineage differs");

        Fixture wrongCalculationDigest = Fixture.fresh(1);
        seedV50(wrongCalculationDigest);
        assertResultRejected(wrongCalculationDigest,
                ResultInput.nonPack(wrongCalculationDigest)
                        .calculationDigest(Pay004TestFixtures.C),
                "Exact case and V50 worker-result lineage differs");

        Fixture divergentRunDigest = Fixture.fresh(1);
        seedV50(divergentRunDigest, 3, "GBP", "GBP",
                Pay004TestFixtures.A, Pay004TestFixtures.B,
                Pay004TestFixtures.C, RECORDED, RECORDED);
        assertResultRejected(divergentRunDigest,
                ResultInput.nonPack(divergentRunDigest),
                "Exact case and V50 worker-result lineage differs");
    }

    @Test
    void calculationRejectsRunRevisionThatWasAlreadySupersededAtThatInstant() {
        Fixture fixture = Fixture.fresh(1);
        seedV50(fixture);
        seedRunRevision(fixture, 4, RECORDED.plusSeconds(90));

        assertResultRejected(fixture, ResultInput.nonPack(fixture),
                "Exact case and V50 worker-result lineage differs");
    }

    @Test
    void activePackRequiresExactScopeEffectiveWindowAndAsOfTime() {
        Fixture accepted = Fixture.fresh(1);
        seedV50(accepted);
        seedActivePack(accepted, accepted.legalEntity(),
                accepted.payrollGroup(), LocalDate.parse("2026-10-01"), null,
                RECORDED.plusSeconds(1), RECORDED.plusSeconds(4));
        ResultInput acceptedInput = ResultInput.active(accepted);
        persistCalculated(accepted, acceptedInput);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_case_results
                 WHERE tenant_id = ? AND result_id = ?
                   AND pack_id = ? AND pack_snapshot_revision = 0
                   AND activation_revision = 4
                """, Long.class, accepted.tenant(), acceptedInput.resultId,
                accepted.pack())).isEqualTo(1L);

        Fixture wrongScope = Fixture.fresh(1);
        seedV50(wrongScope);
        seedActivePack(wrongScope, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-10-01"), null,
                RECORDED.plusSeconds(1), RECORDED.plusSeconds(4));
        assertResultRejected(wrongScope, ResultInput.active(wrongScope),
                "Exact ACTIVE signed-pack lineage differs");

        Fixture wrongActivationWindow = Fixture.fresh(1);
        seedV50(wrongActivationWindow);
        seedActivePack(wrongActivationWindow,
                wrongActivationWindow.legalEntity(),
                wrongActivationWindow.payrollGroup(),
                LocalDate.parse("2026-10-02"), null,
                RECORDED.plusSeconds(1), RECORDED.plusSeconds(4));
        assertResultRejected(wrongActivationWindow,
                ResultInput.active(wrongActivationWindow),
                "Exact ACTIVE signed-pack lineage differs");

        Fixture futureSnapshot = Fixture.fresh(1);
        seedV50(futureSnapshot);
        seedActivePack(futureSnapshot, futureSnapshot.legalEntity(),
                futureSnapshot.payrollGroup(), LocalDate.parse("2026-10-01"),
                null, RECORDED.plusSeconds(101), RECORDED.plusSeconds(4));
        assertResultRejected(futureSnapshot,
                ResultInput.active(futureSnapshot),
                "Exact ACTIVE signed-pack lineage differs");

        Fixture futureActivation = Fixture.fresh(1);
        seedV50(futureActivation);
        seedActivePack(futureActivation, futureActivation.legalEntity(),
                futureActivation.payrollGroup(), LocalDate.parse("2026-10-01"),
                null, RECORDED.plusSeconds(1), RECORDED.plusSeconds(101));
        assertResultRejected(futureActivation,
                ResultInput.active(futureActivation),
                "Exact ACTIVE signed-pack lineage differs");
    }

    @Test
    void activePackRevisionMustBeTheHeadAsOfCalculationTime() {
        Fixture fixture = Fixture.fresh(1);
        seedV50(fixture);
        seedActivePack(fixture, fixture.legalEntity(), fixture.payrollGroup(),
                LocalDate.parse("2026-10-01"), null,
                RECORDED.plusSeconds(1), RECORDED.plusSeconds(4));
        seedLaterActivation(fixture, RECORDED.plusSeconds(90));

        assertResultRejected(fixture, ResultInput.active(fixture),
                "Exact ACTIVE signed-pack lineage differs");
    }

    @Test
    void digitLeadingPackKeyUsesTheExactPay003CanonicalFallback() {
        Fixture fixture = Fixture.fresh(1);
        seedV50(fixture);
        String rawPackKey = "1GB.RETIREMENT";
        seedActivePack(fixture, fixture.legalEntity(), fixture.payrollGroup(),
                LocalDate.parse("2026-10-01"), null,
                RECORDED.plusSeconds(1), RECORDED.plusSeconds(4), rawPackKey);
        String expectedPolicyKey = fallbackPolicyKey(rawPackKey);
        ResultInput input = ResultInput.active(fixture)
                .policyKey(expectedPolicyKey);

        persistCalculated(fixture, input);

        assertThat(admin.queryForObject("""
                SELECT policy_key FROM pay_case_results
                 WHERE tenant_id = ? AND result_id = ?
                """, String.class, fixture.tenant(), input.resultId))
                .isEqualTo(expectedPolicyKey);
    }

    @Test
    void nonPackPolicyIsExclusiveAndBlockedPackStatesNeverProduceResults() {
        Fixture accepted = Fixture.fresh(1);
        seedV50(accepted);
        persistCalculated(accepted, ResultInput.nonPack(accepted));

        Fixture missingPolicy = Fixture.fresh(1);
        seedV50(missingPolicy);
        assertResultRejected(missingPolicy,
                ResultInput.nonPack(missingPolicy).withoutNonPackPolicy(),
                "Explicit non-pack policy lineage is required");

        Fixture mixedLineage = Fixture.fresh(1);
        seedV50(mixedLineage);
        seedActivePack(mixedLineage, mixedLineage.legalEntity(),
                mixedLineage.payrollGroup(), LocalDate.parse("2026-10-01"),
                null, RECORDED.plusSeconds(1), RECORDED.plusSeconds(4));
        assertResultRejected(mixedLineage,
                ResultInput.nonPack(mixedLineage)
                        .alsoReferencesPack(mixedLineage.pack()),
                "pay_case_result_policy_xor");

        for (String blockedState : List.of("NOT_INSTALLED", "DISABLED")) {
            Fixture blocked = Fixture.fresh(1);
            seedV50(blocked);
            insertCaseRevision(admin, blocked, 1, "DRAFT", blockedState,
                    null, blocked.maker());
            insertCaseRevision(admin, blocked, 2, "BLOCKED_PACK_REQUIRED",
                    blockedState, null, blocked.maker());
            assertThatThrownBy(() -> insertResult(admin,
                    ResultInput.nonPack(blocked)
                            .caseRevision(2).countryPackState(blockedState)))
                    .hasRootCauseInstanceOf(PSQLException.class);
            assertThat(admin.queryForObject("""
                    SELECT COUNT(*) FROM pay_case_results
                     WHERE tenant_id = ? AND case_id = ?
                    """, Long.class, blocked.tenant(), blocked.caseId()))
                    .isZero();
        }
    }

    @Test
    void notRequiredUsesExactImmutablePublishedFoundationVersion() {
        Fixture accepted = Fixture.fresh(1);
        seedV50(accepted);
        ResultInput acceptedInput = ResultInput.nonPack(accepted);
        persistCalculated(accepted, acceptedInput);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_case_results result
                  JOIN pay_foundation_versions version
                    ON version.tenant_id = result.tenant_id
                   AND version.configuration_id =
                        result.non_pack_policy_public_id
                   AND version.version = result.non_pack_policy_revision
                 WHERE result.tenant_id = ? AND result.result_id = ?
                   AND version.lifecycle_state = 'PUBLISHED'
                   AND version.definition_digest =
                        result.non_pack_policy_digest
                   AND version.definition #>> '{legalEntity,id}' = ?
                   AND version.definition #>> '{payrollGroup,id}' = ?
                   AND version.definition #>> '{effectivePeriod,startsOn}' =
                        '2026-10-01'
                   AND version.definition #>> '{effectivePeriod,endsOn}' IS NULL
                """, Long.class, accepted.tenant(), acceptedInput.resultId,
                accepted.legalEntity().toString(),
                accepted.payrollGroup().toString()))
                .isEqualTo(1L);

        Fixture missingVersion = Fixture.fresh(1);
        seedV50WithoutFoundation(missingVersion);
        seedFoundation(FoundationInput.exact(missingVersion).withoutVersion());
        assertResultRejected(missingVersion,
                ResultInput.nonPack(missingVersion),
                "Explicit non-pack policy lineage is required");

        Fixture wrongDigest = Fixture.fresh(1);
        seedV50WithoutFoundation(wrongDigest);
        seedFoundation(FoundationInput.exact(wrongDigest)
                .definitionDigest(Pay004TestFixtures.C));
        assertResultRejected(wrongDigest, ResultInput.nonPack(wrongDigest),
                "Explicit non-pack policy lineage is required");

        Fixture wrongDefinitionScope = Fixture.fresh(1);
        seedV50WithoutFoundation(wrongDefinitionScope);
        seedFoundation(FoundationInput.exact(wrongDefinitionScope)
                .definitionScope(UUID.randomUUID(), UUID.randomUUID()));
        assertResultRejected(wrongDefinitionScope,
                ResultInput.nonPack(wrongDefinitionScope),
                "Explicit non-pack policy lineage is required");

        Fixture wrongDefinitionWindow = Fixture.fresh(1);
        seedV50WithoutFoundation(wrongDefinitionWindow);
        seedFoundation(FoundationInput.exact(wrongDefinitionWindow)
                .definitionEffectiveFrom(LocalDate.parse("2026-10-02")));
        assertResultRejected(wrongDefinitionWindow,
                ResultInput.nonPack(wrongDefinitionWindow),
                "Explicit non-pack policy lineage is required");

        Fixture unpublished = Fixture.fresh(1);
        seedV50WithoutFoundation(unpublished);
        seedFoundation(FoundationInput.exact(unpublished)
                .versionState("DRAFT"));
        assertResultRejected(unpublished, ResultInput.nonPack(unpublished),
                "Explicit non-pack policy lineage is required");

        Fixture futureVersion = Fixture.fresh(1);
        seedV50WithoutFoundation(futureVersion);
        seedFoundation(FoundationInput.exact(futureVersion)
                .versionRecordedAt(RECORDED.plusSeconds(101)));
        assertResultRejected(futureVersion,
                ResultInput.nonPack(futureVersion),
                "Explicit non-pack policy lineage is required");
    }

    @Test
    void policyInputAndResultDigestsAreDerivedAndMutationProof() {
        Fixture wrongPolicy = Fixture.fresh(1);
        seedV50(wrongPolicy);
        assertResultRejected(wrongPolicy, ResultInput.nonPack(wrongPolicy)
                        .suppliedPolicyDigest(Pay004TestFixtures.C),
                "Retirement result policy digest differs");

        Fixture wrongInputs = Fixture.fresh(1);
        seedV50(wrongInputs);
        assertResultRejected(wrongInputs, ResultInput.nonPack(wrongInputs)
                        .suppliedCaseInputsDigest(Pay004TestFixtures.C),
                "Retirement result input digest differs");

        Fixture wrongResult = Fixture.fresh(1);
        seedV50(wrongResult);
        assertResultRejected(wrongResult, ResultInput.nonPack(wrongResult)
                        .suppliedResultDigest(Pay004TestFixtures.C),
                "Retirement result canonical digest differs");

        Fixture wrongRounding = Fixture.fresh(1);
        seedV50(wrongRounding);
        assertResultRejected(wrongRounding,
                ResultInput.nonPack(wrongRounding).roundingMode("UP"),
                "Explicit non-pack policy lineage is required");
    }

    @Test
    void moneyMustBeCanonicalWithoutDatabaseRounding() {
        Fixture accepted = Fixture.fresh(1);
        seedV50(accepted);
        ResultInput exact = ResultInput.nonPack(accepted)
                .amountText("1234.5678");
        persistCalculated(accepted, exact);
        assertThat(admin.queryForObject("""
                SELECT retirement_amount::TEXT FROM pay_case_results
                 WHERE tenant_id = ? AND result_id = ?
                """, String.class, accepted.tenant(), exact.resultId))
                .isEqualTo("1234.5678");

        Fixture wouldRound = Fixture.fresh(1);
        seedV50(wouldRound);
        assertResultRejected(wouldRound,
                ResultInput.nonPack(wouldRound)
                        .amountText("1.23456"),
                "pay_case_result_money_shape");

        Fixture generatedOverride = Fixture.fresh(1);
        seedV50(generatedOverride);
        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            insertCalculatedRevisions(jdbc, generatedOverride,
                    "NOT_REQUIRED");
            insertResultOverridingGeneratedAmount(jdbc);
        })).hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining("generated column");
        assertCaseRevisionCount(generatedOverride, 0L);

        Fixture nonCanonicalText = Fixture.fresh(1);
        seedV50(nonCanonicalText);
        assertResultRejected(nonCanonicalText,
                ResultInput.nonPack(nonCanonicalText)
                        .amountText("010.0000"),
                "pay_case_result_money_shape");
    }

    @Test
    void transitionsAndMakerCheckerRulesAreAtomic() {
        Fixture illegalTransition = Fixture.fresh(1);
        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            insertCaseRevision(jdbc, illegalTransition, 1, "DRAFT",
                    "NOT_REQUIRED", null, illegalTransition.maker());
            insertCaseRevision(jdbc, illegalTransition, 2, "APPROVED",
                    "NOT_REQUIRED", illegalTransition.maker(),
                    illegalTransition.checker());
        })).hasRootCauseInstanceOf(PSQLException.class);
        assertCaseRevisionCount(illegalTransition, 0L);

        Fixture wrongCalculationActor = Fixture.fresh(1);
        seedV50(wrongCalculationActor);
        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            insertCaseRevision(jdbc, wrongCalculationActor, 1, "DRAFT",
                    "NOT_REQUIRED", null, wrongCalculationActor.maker());
            insertCaseRevision(jdbc, wrongCalculationActor, 2, "VALIDATED",
                    "NOT_REQUIRED", wrongCalculationActor.maker(),
                    wrongCalculationActor.maker());
            insertCaseRevision(jdbc, wrongCalculationActor, 3, "CALCULATED",
                    "NOT_REQUIRED", wrongCalculationActor.maker(),
                    wrongCalculationActor.checker());
        })).hasRootCauseInstanceOf(PSQLException.class);
        assertCaseRevisionCount(wrongCalculationActor, 0L);

        Fixture approval = Fixture.fresh(1);
        seedV50(approval);
        persistCalculated(approval, ResultInput.nonPack(approval));
        assertThatThrownBy(() -> insertCaseRevision(admin, approval, 4,
                "APPROVED", "NOT_REQUIRED", approval.maker(),
                approval.maker()))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertCaseRevisionCount(approval, 3L);

        insertCaseRevision(admin, approval, 4, "APPROVED", "NOT_REQUIRED",
                approval.maker(), approval.checker(),
                RECORDED.plusSeconds(102));
        insertCaseRevision(admin, approval, 5, "FINALIZED", "NOT_REQUIRED",
                approval.maker(), approval.checker(),
                RECORDED.plusSeconds(103));
        assertCaseRevisionCount(approval, 5L);
    }

    @Test
    void caseRevisionAndCalculationTimesAreStrictlyMonotonic() {
        Fixture outOfOrder = Fixture.fresh(1);
        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            insertCaseRevision(jdbc, outOfOrder, 1, "DRAFT",
                    "NOT_REQUIRED", null, outOfOrder.maker(),
                    RECORDED.plusSeconds(10));
            insertCaseRevision(jdbc, outOfOrder, 2, "VALIDATED",
                    "NOT_REQUIRED", outOfOrder.maker(), outOfOrder.maker(),
                    RECORDED.plusSeconds(10));
        })).hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "Retirement case revision time must increase");
        assertCaseRevisionCount(outOfOrder, 0L);

        Fixture futureCase = Fixture.fresh(1);
        seedV50(futureCase);
        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            insertCaseRevision(jdbc, futureCase, 1, "DRAFT",
                    "NOT_REQUIRED", null, futureCase.maker(),
                    RECORDED.plusSeconds(1));
            insertCaseRevision(jdbc, futureCase, 2, "VALIDATED",
                    "NOT_REQUIRED", futureCase.maker(), futureCase.maker(),
                    RECORDED.plusSeconds(2));
            insertCaseRevision(jdbc, futureCase, 3, "CALCULATED",
                    "NOT_REQUIRED", futureCase.maker(), futureCase.maker(),
                    RECORDED.plusSeconds(101));
            insertResult(jdbc, ResultInput.nonPack(futureCase));
        })).hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "Exact case and V50 worker-result lineage differs");
        assertCaseRevisionCount(futureCase, 0L);
    }

    @Test
    void calculationCannotUseAnInputRecordedAfterItsCalculationInstant() {
        Fixture fixture = Fixture.fresh(1);
        seedV50(fixture);
        ResultInput input = ResultInput.nonPack(fixture);

        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            insertCalculatedRevisions(jdbc, fixture, "NOT_REQUIRED",
                    input.calculatedAt);
            insertBasisRef(jdbc, fixture, 3,
                    input.calculatedAt.plusSeconds(1));
            insertResult(jdbc, input);
        })).hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "Calculated retirement inputs must share "
                                + "the calculation instant");
        assertCaseRevisionCount(fixture, 0L);
    }

    @Test
    void resultCannotBeBackfilledAfterTheCaseAdvancedPastCalculated() {
        Fixture fixture = Fixture.fresh(1);
        seedV50(fixture);
        ResultInput input = ResultInput.nonPack(fixture);

        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            insertCalculatedRevisions(jdbc, fixture, "NOT_REQUIRED",
                    input.calculatedAt);
            insertAttachments(jdbc, fixture);
            insertCaseRevision(jdbc, fixture, 4, "APPROVED",
                    "NOT_REQUIRED", fixture.maker(), fixture.checker(),
                    RECORDED.plusSeconds(102));
            insertResult(jdbc, input);
        })).hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "Exact case and V50 worker-result lineage differs");
        assertCaseRevisionCount(fixture, 0L);
    }

    @Test
    void attachmentsRejectPastRevisionAndTerminalAggregateHeads() {
        Fixture advanced = Fixture.fresh(1);
        seedV50(advanced);
        persistCalculated(advanced, ResultInput.nonPack(advanced));
        assertThatThrownBy(() -> insertBasisRef(admin, advanced, 2))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "current pre-result case head");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_case_basis_refs
                 WHERE tenant_id = ? AND case_id = ?
                """, Long.class, advanced.tenant(), advanced.caseId()))
                .isEqualTo(1L);

        Fixture terminal = Fixture.fresh(1);
        completeFinalizedFixture(terminal);
        assertThatThrownBy(() -> insertBasisRef(admin, terminal, 3))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause().hasMessageContaining(
                        "current pre-result case head");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_case_basis_refs
                 WHERE tenant_id = ? AND case_id = ?
                """, Long.class, terminal.tenant(), terminal.caseId()))
                .isEqualTo(1L);
    }

    @Test
    void correctionClosureRollsBackIncompleteFactsAndAllFactsAreAppendOnly() {
        Fixture fixture = Fixture.fresh(1);
        ResultInput result = completeFinalizedFixture(fixture);

        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc ->
                insertCaseRevision(jdbc, fixture, 6, "CORRECTED",
                        "NOT_REQUIRED", fixture.maker(), fixture.checker(),
                        RECORDED.plusSeconds(104))))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertCaseRevisionCount(fixture, 5L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_case_corrections", Long.class))
                .isZero();

        inTransaction(adminDataSource, jdbc -> {
            insertCaseRevision(jdbc, fixture, 6, "CORRECTED",
                    "NOT_REQUIRED", fixture.maker(), fixture.checker(),
                    RECORDED.plusSeconds(104));
            insertCorrection(jdbc, fixture, result.resultId);
        });
        assertCaseRevisionCount(fixture, 6L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_case_corrections", Long.class))
                .isEqualTo(1L);

        for (String table : TABLES) {
            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM " + table, Long.class))
                    .as(table).isPositive();
            assertThatThrownBy(() -> admin.update(
                    "UPDATE " + table
                            + " SET recorded_at = recorded_at + INTERVAL '1 second'"))
                    .hasRootCauseInstanceOf(PSQLException.class);
            assertThatThrownBy(() -> admin.update("DELETE FROM " + table))
                    .hasRootCauseInstanceOf(PSQLException.class);
        }
    }

    private boolean privilege(String role, String table, String permission) {
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

    private static void insertDraft(
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

    private static void insertFirstCase(
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

    private static void insertCaseRevision(
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

    private static void insertCaseRevision(
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

    private static void insertCalculatedRevisions(
            JdbcTemplate jdbc, Fixture fixture, String countryPackState) {
        insertCalculatedRevisions(jdbc, fixture, countryPackState,
                RECORDED.plusSeconds(100));
    }

    private static void insertCalculatedRevisions(
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

    private static void insertResult(JdbcTemplate jdbc, ResultInput input) {
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

    private static void insertResultOverridingGeneratedAmount(
            JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO pay_case_results (retirement_amount)
                VALUES (999.0000)
                """);
    }

    private static void persistCalculated(
            Fixture fixture, ResultInput input) {
        inTransaction(adminDataSource, jdbc -> {
            insertCalculatedRevisions(
                    jdbc, fixture, input.countryPackState,
                    input.calculatedAt);
            insertAttachments(jdbc, fixture);
            insertResult(jdbc, input);
        });
    }

    private static void assertResultRejected(
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

    private static void assertCaseRevisionCount(
            Fixture fixture, long expected) {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_statutory_cases
                 WHERE tenant_id = ? AND case_id = ?
                """, Long.class, fixture.tenant(), fixture.caseId()))
                .isEqualTo(expected);
    }

    private static ResultInput completeFinalizedFixture(Fixture fixture) {
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

    private static void completeCorrectedFixture(Fixture fixture) {
        ResultInput result = completeFinalizedFixture(fixture);
        inTransaction(adminDataSource, jdbc -> {
            insertCaseRevision(jdbc, fixture, 6, "CORRECTED",
                    "NOT_REQUIRED", fixture.maker(), fixture.checker(),
                    RECORDED.plusSeconds(104));
            insertCorrection(jdbc, fixture, result.resultId);
        });
    }

    private static void insertAttachments(
            JdbcTemplate jdbc, Fixture fixture) {
        insertBasisRef(jdbc, fixture, 3);
        insertEvidence(jdbc, fixture, 3);
    }

    private static void insertBasisRef(
            JdbcTemplate jdbc, Fixture fixture, long caseRevision) {
        insertBasisRef(jdbc, fixture, caseRevision,
                RECORDED.plusSeconds(100));
    }

    private static void insertBasisRef(
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

    private static void insertEvidence(
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

    private static void insertCorrection(
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

    private static void seedV50(Fixture fixture) {
        seedV50Facts(fixture, 3, "GBP", "GBP", Pay004TestFixtures.A,
                Pay004TestFixtures.B, Pay004TestFixtures.B,
                RECORDED, RECORDED);
        seedFoundation(FoundationInput.exact(fixture));
    }

    private static void seedV50WithoutFoundation(Fixture fixture) {
        seedV50Facts(fixture, 3, "GBP", "GBP", Pay004TestFixtures.A,
                Pay004TestFixtures.B, Pay004TestFixtures.B,
                RECORDED, RECORDED);
    }

    private static void seedV50(
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

    private static void seedV50Facts(
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

    private static void seedFoundation(FoundationInput input) {
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

    private static void insertFoundationVersion(
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

    private static void seedRunRevision(
            Fixture fixture, long revision, Instant recordedAt) {
        inTransaction(adminDataSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertRunRevision(jdbc, fixture, revision, "GBP",
                    Pay004TestFixtures.B, recordedAt);
        });
    }

    private static void insertRunRevision(
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

    private static void seedActivePack(
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

    private static void seedActivePack(
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

    private static void seedLaterActivation(
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

    private static String digestFor(long revision) {
        return switch ((int) (revision % 3)) {
            case 0 -> Pay004TestFixtures.A;
            case 1 -> Pay004TestFixtures.B;
            default -> Pay004TestFixtures.C;
        };
    }

    private static String fallbackPolicyKey(String rawPackKey) {
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

    private record Fixture(
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

        private static Fixture fresh(long tenant) {
            return new Fixture(
                    tenant, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private static final class FoundationInput {
        private final Fixture fixture;
        private boolean includeVersion = true;
        private String versionState = "PUBLISHED";
        private String definitionDigest = Pay004TestFixtures.B;
        private Instant versionRecordedAt = RECORDED;
        private UUID definitionLegalEntity;
        private UUID definitionPayrollGroup;
        private LocalDate definitionEffectiveFrom =
                LocalDate.parse("2026-10-01");
        private LocalDate definitionEffectiveTo;

        private FoundationInput(Fixture fixture) {
            this.fixture = fixture;
            definitionLegalEntity = fixture.legalEntity();
            definitionPayrollGroup = fixture.payrollGroup();
        }

        private static FoundationInput exact(Fixture fixture) {
            return new FoundationInput(fixture);
        }

        private FoundationInput withoutVersion() {
            includeVersion = false;
            return this;
        }

        private FoundationInput versionState(String value) {
            versionState = value;
            return this;
        }

        private FoundationInput definitionDigest(String value) {
            definitionDigest = value;
            return this;
        }

        private FoundationInput definitionScope(
                UUID legalEntity, UUID payrollGroup) {
            definitionLegalEntity = legalEntity;
            definitionPayrollGroup = payrollGroup;
            return this;
        }

        private FoundationInput definitionEffectiveFrom(LocalDate value) {
            definitionEffectiveFrom = value;
            return this;
        }

        private FoundationInput versionRecordedAt(Instant value) {
            versionRecordedAt = value;
            return this;
        }
    }

    private static final class ResultInput {
        private final Fixture fixture;
        private final UUID resultId = UUID.randomUUID();
        private long caseRevision = 3;
        private UUID worker;
        private long runRevision = 3;
        private UUID workerResult;
        private String countryPackState;
        private UUID packId;
        private Long packSnapshotRevision;
        private Long activationRevision;
        private UUID nonPackPolicyId;
        private Long nonPackPolicyRevision;
        private String nonPackPolicyDigest;
        private String policyKey;
        private long policyRevision;
        private LocalDate policyEffectiveFrom =
                LocalDate.parse("2026-10-01");
        private LocalDate policyEffectiveTo;
        private String amountText = "1234.5000";
        private String currency = "GBP";
        private String roundingMode = "HALF_EVEN";
        private String roundingStage = "RETIREMENT_FINAL";
        private String workerDigest = Pay004TestFixtures.A;
        private String calculationDigest = Pay004TestFixtures.B;
        private String suppliedPolicyDigest;
        private String suppliedCaseInputsDigest;
        private String suppliedResultDigest;
        private Instant calculatedAt = RECORDED.plusSeconds(100);

        private ResultInput(Fixture fixture) {
            this.fixture = fixture;
            worker = fixture.worker();
            workerResult = fixture.workerResult();
        }

        private static ResultInput nonPack(Fixture fixture) {
            ResultInput input = new ResultInput(fixture);
            input.countryPackState = "NOT_REQUIRED";
            input.nonPackPolicyId = fixture.nonPackPolicy();
            input.nonPackPolicyRevision = 7L;
            input.nonPackPolicyDigest = Pay004TestFixtures.B;
            input.policyKey = "pay.retirement.foundation";
            input.policyRevision = 7;
            return input;
        }

        private static ResultInput active(Fixture fixture) {
            ResultInput input = new ResultInput(fixture);
            input.countryPackState = "ACTIVE";
            input.packId = fixture.pack();
            input.packSnapshotRevision = 0L;
            input.activationRevision = 4L;
            input.policyKey = "gb.retirement";
            input.policyRevision = 4;
            return input;
        }

        private ResultInput caseRevision(long value) {
            caseRevision = value;
            return this;
        }

        private ResultInput runRevision(long value) {
            runRevision = value;
            return this;
        }

        private ResultInput worker(UUID value) {
            worker = value;
            return this;
        }

        private ResultInput currency(String value) {
            currency = value;
            return this;
        }

        private ResultInput workerDigest(String value) {
            workerDigest = value;
            return this;
        }

        private ResultInput calculationDigest(String value) {
            calculationDigest = value;
            return this;
        }

        private ResultInput countryPackState(String value) {
            countryPackState = value;
            return this;
        }

        private ResultInput withoutNonPackPolicy() {
            nonPackPolicyId = null;
            nonPackPolicyRevision = null;
            nonPackPolicyDigest = null;
            return this;
        }

        private ResultInput alsoReferencesPack(UUID value) {
            packId = value;
            packSnapshotRevision = 0L;
            activationRevision = 4L;
            return this;
        }

        private ResultInput amountText(String value) {
            amountText = value;
            return this;
        }

        private ResultInput policyKey(String value) {
            policyKey = value;
            return this;
        }

        private ResultInput roundingMode(String value) {
            roundingMode = value;
            return this;
        }

        private ResultInput suppliedPolicyDigest(String value) {
            suppliedPolicyDigest = value;
            return this;
        }

        private ResultInput suppliedCaseInputsDigest(String value) {
            suppliedCaseInputsDigest = value;
            return this;
        }

        private ResultInput suppliedResultDigest(String value) {
            suppliedResultDigest = value;
            return this;
        }
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

    private static void migrate(DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME,
                        "payrollProjectionPublisherRole", PUBLISHER));
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    private static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    private static String sqlNames(List<String> names) {
        return names.stream().map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }
}
