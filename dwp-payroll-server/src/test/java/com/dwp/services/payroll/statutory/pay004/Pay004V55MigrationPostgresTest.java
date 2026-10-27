package com.dwp.services.payroll.statutory.pay004;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Pay004V55MigrationPostgresTest extends Pay004V55MigrationPostgresFixture {

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
}
