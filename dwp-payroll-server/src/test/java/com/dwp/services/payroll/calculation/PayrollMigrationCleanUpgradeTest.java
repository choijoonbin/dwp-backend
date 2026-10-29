package com.dwp.services.payroll.calculation;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.database.OwnerTriggerExecutionBoundaryGuard;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers
class PayrollMigrationCleanUpgradeTest extends PayrollMigrationPostgresFixture {

    @Test
    void cleanV1ThroughV50CreatesOnlyLeasedTablesWithForcedRls() {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('1', '49', '50') AND success
                """, Long.class)).isEqualTo(3L);
        assertThat(admin.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relkind = 'r' AND relname IN (%s)
                 ORDER BY relname
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                String.class)).containsExactlyElementsOf(V50_TABLES.stream().sorted().toList());
        assertThat(admin.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class WHERE relname IN (%s)
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = current_schema()
                   AND tablename IN (%s)
                   AND qual LIKE '%%dwp.payroll_tenant_id%%'
                   AND with_check LIKE '%%dwp.payroll_tenant_id%%'
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Long.class)).isEqualTo(10L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal
                   AND tgname LIKE 'pay_%%_immutable'
                   AND tgrelid::regclass::text IN (%s)
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Long.class)).isEqualTo(10L);
        assertThat(admin.queryForObject(
                "SELECT prosecdef FROM pg_proc WHERE proname = 'pay_v50_claim_attempts'",
                Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1
                      FROM pg_proc function
                      CROSS JOIN LATERAL aclexplode(
                          COALESCE(function.proacl,
                              acldefault('f', function.proowner))) grant_entry
                     WHERE function.oid =
                           'pay_v50_claim_attempts(bigint,text,integer,bigint)'::regprocedure
                       AND grant_entry.grantee = 0
                       AND grant_entry.privilege_type = 'EXECUTE')
                """, Boolean.class)).isTrue();
        assertThat(admin.queryForObject(
                "SELECT has_function_privilege(?, "
                        + "'pay_v50_claim_attempts(bigint,text,integer,bigint)', 'EXECUTE')",
                Boolean.class, RUNTIME_USER)).isTrue();
    }

    @Test
    void v51ToV52PreservesExistingFactsAndHardensPreparationTables() {
        assertThat(v51Upgrade.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('51', '52') AND success
                """, Long.class)).isEqualTo(2L);
        assertThat(v51Upgrade.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs",
                Long.class)).isEqualTo(1L);
        assertThat(v51Upgrade.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_class
                 WHERE relkind = 'r'
                   AND relname IN (%s)
                   AND relrowsecurity AND relforcerowsecurity
                """.formatted(sqlNames(V52_TABLES)), Long.class))
                .isEqualTo(9L);
        assertThat(v51Upgrade.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_proc
                 WHERE proname LIKE 'pay_v52_%'
                   AND prosecdef
                   AND proconfig =
                       ARRAY['search_path=pg_catalog, public, pg_temp']::text[]
                """, Long.class)).isEqualTo(8L);
        assertThat(v51Upgrade.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1
                      FROM pg_proc function
                      CROSS JOIN LATERAL aclexplode(
                          COALESCE(function.proacl,
                              acldefault('f', function.proowner))) grant_entry
                     WHERE function.proname LIKE 'pay_v52_%'
                       AND grant_entry.grantee = 0
                       AND grant_entry.privilege_type = 'EXECUTE')
                """, Boolean.class)).isTrue();
        assertThat(v51Upgrade.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_trigger
                     WHERE NOT tgisinternal
                       AND tgrelid =
                           'pay_run_preparation_step_up_uses'::regclass
                       AND tgname =
                           'pay_run_preparation_step_up_uses_immutable')
                """, Boolean.class)).isTrue();
        assertThat(v51Upgrade.queryForObject("""
                SELECT has_table_privilege(?,
                    'pay_run_preparation_step_up_uses', 'SELECT,INSERT')
                   AND NOT has_table_privilege(?,
                    'pay_run_preparation_step_up_uses', 'UPDATE,DELETE,TRUNCATE')
                """, Boolean.class, RUNTIME_USER, RUNTIME_USER)).isTrue();
    }

    @Test
    void cleanV1ThroughV52CreatesPreparationTablesAndDeferredClosure() {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '52' AND success
                """, Long.class)).isEqualTo(1L);
        assertThat(admin.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE relkind = 'r'
                   AND relname IN (%s)
                 ORDER BY relname
                """.formatted(sqlNames(V52_PREPARATION_TABLES)), String.class))
                .containsExactly(
                "pay_run_preparation_decisions",
                "pay_run_preparation_formula_refs",
                "pay_run_preparation_lines",
                "pay_run_preparation_sources",
                "pay_run_preparation_step_up_uses",
                "pay_run_preparations");
        assertThat(admin.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class
                 WHERE relname LIKE 'pay_run_preparation%%'
                   AND relkind = 'r'
                """, Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_trigger
                 WHERE NOT tgisinternal
                   AND tgname IN (
                       'pay_v52_prepared_commit_guard',
                       'pay_v52_step_up_use_binding_guard',
                       'pay_run_preparation_state_guard',
                       'pay_run_preparation_source_insert_guard',
                       'pay_run_preparation_decision_insert_guard',
                       'pay_run_preparation_line_insert_guard',
                       'pay_run_preparation_formula_insert_guard')
                """, Long.class)).isEqualTo(7L);
        assertThat(admin.queryForObject("""
                SELECT bool_and(tgdeferrable AND tginitdeferred)
                  FROM pg_trigger
                 WHERE tgname IN (
                    'pay_v52_prepared_commit_guard',
                    'pay_v52_step_up_use_binding_guard')
                """, Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT has_table_privilege(?,
                    'pay_run_preparation_step_up_uses', 'SELECT,INSERT')
                   AND NOT has_table_privilege(?,
                    'pay_run_preparation_step_up_uses', 'UPDATE,DELETE,TRUNCATE')
                """, Boolean.class, RUNTIME_USER, RUNTIME_USER)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_trigger
                     WHERE NOT tgisinternal
                       AND tgrelid =
                           'pay_run_preparation_step_up_uses'::regclass
                       AND tgname =
                           'pay_run_preparation_step_up_uses_immutable')
                """, Boolean.class)).isTrue();
    }

    @Test
    void v51HardensEveryV49AndV50OwnerTriggerFunction() {
        OwnerTriggerExecutionBoundaryGuard.verify(
                "Payroll", runtimeDataSource, List.of("public"), POSTGRES.getUsername());
        assertThat(admin.queryForObject("""
                SELECT COUNT(DISTINCT routine.oid)
                  FROM pg_trigger trigger_object
                  JOIN pg_class target ON target.oid=trigger_object.tgrelid
                  JOIN pg_namespace target_namespace
                    ON target_namespace.oid=target.relnamespace
                  JOIN pg_proc routine ON routine.oid=trigger_object.tgfoid
                 WHERE NOT trigger_object.tgisinternal
                   AND target_namespace.nspname='public'
                   AND routine.proname IN (
                       'pay_compensation_plan_input_reject_mutation',
                       'pay_v50_reject_mutation',
                       'pay_v50_guard_run_revision',
                       'pay_v50_guard_attempt_transition',
                       'pay_v50_guard_snapshot_insert',
                       'pay_v50_guard_snapshot_ref_insert',
                       'pay_v50_guard_worker_record',
                       'pay_v50_guard_receipt_record',
                       'pay_v50_guard_calculation_fact_insert',
                       'pay_v50_guard_outbox_event')
                   AND routine.prosecdef
                   AND routine.proconfig =
                       ARRAY['search_path=pg_catalog, public, pg_temp']::text[]
                   AND NOT has_function_privilege(?, routine.oid, 'EXECUTE')
                """, Long.class, RUNTIME_USER)).isEqualTo(10L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version='51' AND success
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void postgresJsonbPreservesCanonicalQuantizationIncrementScale()
            throws Exception {
        PayrollCalculationModels.QuantizationBinding binding =
                new PayrollCalculationModels.QuantizationBinding(
                        1, null,
                        PayrollCalculationSemanticContract.RoundingStage.ELEMENT,
                        null, 4, java.math.RoundingMode.HALF_EVEN,
                        new BigDecimal("10.0000"), "a".repeat(64));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        String persisted = admin.queryForObject(
                "SELECT CAST(? AS jsonb)::text", String.class,
                mapper.writeValueAsString(binding));
        PayrollCalculationModels.QuantizationBinding restored = mapper.readValue(
                persisted, PayrollCalculationModels.QuantizationBinding.class);

        assertThat(restored).isEqualTo(binding);
        assertThat(restored.increment().scale()).isEqualTo(4);
        assertThat(restored.increment().toPlainString()).isEqualTo("10.0000");
    }

    @Test
    void v49ToV50PreservesCompensationRowsAndAddsFreezeBoundary() {
        assertThat(upgrade.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class))
                .isEqualTo(1L);
        assertThat(upgrade.queryForObject(
                "SELECT snapshot_revision FROM pay_compensation_plan_inputs",
                Long.class)).isEqualTo(9L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('49', '50') AND success
                """, Long.class)).isEqualTo(2L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name = 'pay_input_snapshots'
                """, Long.class)).isEqualTo(1L);
        assertThat(upgrade.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class WHERE relname IN (%s)
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Boolean.class)).isTrue();
        assertThat(upgrade.queryForObject(
                "SELECT has_table_privilege(?, 'pay_payroll_runs', 'SELECT,INSERT')",
                Boolean.class, RUNTIME_USER)).isTrue();
    }

    @Test
    void jdbcFreezeBindsExactPreparedRunSnapshotAndPopulationNotTenantLatest() {
        UUID exactSnapshot = UUID.randomUUID();
        UUID unrelatedLatestSnapshot = UUID.randomUUID();
        UUID exactPublicLine = UUID.randomUUID();
        UUID unrelatedPublicLine = UUID.randomUUID();
        UUID exactFormula = UUID.fromString(
                "00000000-0000-0000-0000-000000001108");
        UUID exactLegalEntity = UUID.randomUUID();
        UUID exactPayGroup = UUID.randomUUID();
        UUID unrelatedLegalEntity = UUID.randomUUID();
        UUID unrelatedPayGroup = UUID.randomUUID();
        seedFoundationAndFormula(
                exactLegalEntity, exactPayGroup, exactFormula, "b".repeat(64));
        seedFoundationAndFormula(
                unrelatedLegalEntity, unrelatedPayGroup, UUID.randomUUID(),
                "f".repeat(64));
        seedV49Line(
                exactSnapshot, 9, PayrollCalculationTestSupport.DIGEST,
                exactPublicLine, PayrollCalculationTestSupport.PAY_PERIOD,
                "BASE", "1".repeat(64));
        seedV49Line(
                unrelatedLatestSnapshot, 99, "e".repeat(64), unrelatedPublicLine,
                PayrollCalculationTestSupport.PAY_PERIOD,
                "UNRELATED", "2".repeat(64));
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource), canonical);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        UUID runId = UUID.randomUUID();
        PayrollCalculationModels.RunRevision prepared = preparedRun(
                runId, exactSnapshot, 9, PayrollCalculationTestSupport.DIGEST,
                exactLegalEntity, exactPayGroup);

        transaction.executeWithoutResult(status -> store.appendRunRevision(prepared));
        PayrollCalculationStore.Preparation preparation = transaction.execute(status ->
                store.prepare(
                        prepared, 1, PayrollCalculationTestSupport.TARGET_DIGEST,
                        Instant.parse("2026-10-14T02:00:00Z")));
        assertThat(preparation).isNotNull();
        assertThat(preparation.snapshot().sourceSnapshotId()).isEqualTo(exactSnapshot);
        assertThat(preparation.snapshot().inputs())
                .extracting(PayrollCalculationModels.SourceInput::sourcePublicId)
                .containsExactly(exactPublicLine)
                .doesNotContain(unrelatedPublicLine);
        assertThat(preparation.snapshot().formulas())
                .extracting(PayrollCalculationSemanticContract.FormulaBinding::formulaId)
                .containsExactly(exactFormula);

        PayrollCalculationStore.Preparation boundPreparation =
                new PayrollCalculationStore.Preparation(
                        preparation.payPeriodId(), preparation.legalEntityId(),
                        preparation.payrollGroupId(), preparation.currency(),
                        new PayrollCalculationSemanticBinder(
                                PayrollCalculationTestSupport.exactSemanticMappings(),
                                canonical).bind(preparation.snapshot()));
        transaction.executeWithoutResult(status -> store.freeze(boundPreparation));
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_input_snapshots snapshot
                  JOIN pay_input_snapshot_compensation_refs ref
                    ON ref.tenant_id = snapshot.tenant_id
                   AND ref.input_snapshot_internal_id = snapshot.input_snapshot_internal_id
                 WHERE snapshot.tenant_id = 1 AND snapshot.run_id = ?
                   AND snapshot.source_snapshot_id = ?
                """, Long.class, runId, exactSnapshot)).isEqualTo(1L);
    }

    @Test
    void jdbcFreezeRejectsMissingOrMismatchedAppliedInputReceipt() {
        UUID legalEntityId = UUID.randomUUID();
        UUID payrollGroupId = UUID.randomUUID();
        seedFoundationAndFormula(
                legalEntityId, payrollGroupId, UUID.randomUUID(), "b".repeat(64));
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource),
                new PayrollCalculationCanonical(
                        JsonMapper.builder().findAndAddModules().build()));
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));

        UUID missingSnapshot = UUID.randomUUID();
        String missingDigest = "9".repeat(64);
        seedV49Line(
                missingSnapshot, 9, missingDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                "a".repeat(64), null);
        assertThatThrownBy(() -> transaction.execute(status -> store.prepare(
                preparedRun(
                        UUID.randomUUID(), missingSnapshot, 9, missingDigest,
                        legalEntityId, payrollGroupId),
                1, PayrollCalculationTestSupport.TARGET_DIGEST, Instant.now())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        UUID mismatchedSnapshot = UUID.randomUUID();
        String mismatchedDigest = "8".repeat(64);
        seedV49Line(
                mismatchedSnapshot, 9, mismatchedDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                "b".repeat(64), 2);
        assertThatThrownBy(() -> transaction.execute(status -> store.prepare(
                preparedRun(
                        UUID.randomUUID(), mismatchedSnapshot, 9,
                        mismatchedDigest, legalEntityId, payrollGroupId),
                1, PayrollCalculationTestSupport.TARGET_DIGEST, Instant.now())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void runtimeRoleCannotBypassRlsOrMutateAppendOnlyFacts() {
        assertThat(admin.queryForMap("""
                SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
                  FROM pg_roles WHERE rolname = ?
                """, RUNTIME_USER)).containsOnly(
                org.assertj.core.data.MapEntry.entry("rolsuper", false),
                org.assertj.core.data.MapEntry.entry("rolcreaterole", false),
                org.assertj.core.data.MapEntry.entry("rolcreatedb", false),
                org.assertj.core.data.MapEntry.entry("rolreplication", false),
                org.assertj.core.data.MapEntry.entry("rolbypassrls", false));
        for (String table : V50_TABLES) {
            assertThat(admin.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'SELECT,INSERT')",
                    Boolean.class, RUNTIME_USER, table)).isTrue();
            assertThat(admin.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'UPDATE,DELETE,TRUNCATE')",
                    Boolean.class, RUNTIME_USER, table)).isFalse();
        }
        UUID tenantOneRun = UUID.randomUUID();
        UUID tenantTwoRun = UUID.randomUUID();
        UUID tenantOneSnapshot = UUID.randomUUID();
        UUID tenantTwoSnapshot = UUID.randomUUID();
        insertTenantPreparingRun(admin, 1, tenantOneRun, tenantOneSnapshot);
        insertTenantPreparingRun(admin, 2, tenantTwoRun, tenantTwoSnapshot);
        insertSnapshot(admin, 1, tenantOneRun, tenantOneSnapshot);
        insertSnapshot(admin, 2, tenantTwoRun, tenantTwoSnapshot);
        assertThat(runtimeSnapshotCount(1)).isEqualTo(1L);
        assertThat(runtimeSnapshotCount(2)).isEqualTo(1L);
        assertThat(runtimeSnapshotCount(3)).isZero();
        assertThatThrownBy(() -> admin.update(
                "UPDATE pay_input_snapshots SET source_line_count = 2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> admin.update("DELETE FROM pay_input_snapshots"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void receiptSealAndOneTimeStepUpIndexesRejectReuse() {
        UUID runId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID firstSubject = UUID.randomUUID();
        insertReceipt(receiptId, runId, firstSubject, "idem-1", "challenge-1");
        assertThatThrownBy(() -> insertReceipt(
                UUID.randomUUID(), runId, UUID.randomUUID(), "idem-2", "challenge-1"))
                .isInstanceOf(DataAccessException.class);
        insertReceipt(
                UUID.randomUUID(), runId, UUID.randomUUID(),
                "idem-1", "challenge-2");
        assertThatThrownBy(() -> admin.update("""
                UPDATE pay_command_receipts SET purpose_code = 'OTHER'
                 WHERE receipt_id = ?
                """, receiptId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_command_receipts
                 WHERE receipt_id = ? AND status_sequence = 1
                """, Long.class, receiptId)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_command_receipts
                 WHERE idempotency_key = 'idem-1' AND status_sequence = 1
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    void securityDefinerClaimCommitsLeaseBeforeWorkerHandoff() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        UUID runId = calculateReadyRun(transactionalService(
                mock(PayrollHighRiskCommandGuard.class)), "3", "2");
        UUID attemptId = admin.queryForObject("""
                SELECT attempt_id FROM pay_payroll_run_attempts
                 WHERE tenant_id = 1 AND run_id = ? AND attempt_no = 1
                   AND status_sequence = 1
                """, UUID.class, runId);

        PayrollCalculationStore store = transactionalStore();
        var claimed = store.claimable(
                1, 1, "dispatcher-proof", Instant.now(), Duration.ofSeconds(30));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().lease().attemptId()).isEqualTo(attemptId);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_workers
                 WHERE tenant_id = 1 AND run_id = ? AND attempt_no = 1
                   AND record_kind = 'LEASE' AND lease_token = ?
                """, Long.class, runId, claimed.getFirst().lease().leaseToken()))
                .isEqualTo(1L);
        assertThat(store.claimable(
                1, 1, "dispatcher-proof-2", Instant.now(), Duration.ofSeconds(30)))
                .isEmpty();
    }
}
