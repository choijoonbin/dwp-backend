package com.dwp.services.payroll.statutory.pay004;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Pay004RetirementResultLineageTest {

    @Test
    void activeAndNotRequiredLineageAreMutuallyExclusive() {
        assertThat(Pay004TestFixtures.activeLineage().countryPackState())
                .isEqualTo(RetirementYearEndCaseContract.CountryPackState.ACTIVE);
        assertThat(Pay004TestFixtures.nonPackLineage().countryPackState())
                .isEqualTo(RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED);

        assertThatThrownBy(() -> lineage(
                RetirementYearEndCaseContract.CountryPackState.ACTIVE,
                Pay004TestFixtures.PACK, 0, 4,
                Pay004TestFixtures.POLICY, 7, Pay004TestFixtures.A,
                Pay004TestFixtures.WORKER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lineage(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                Pay004TestFixtures.PACK, 0, 0,
                Pay004TestFixtures.POLICY, 7, Pay004TestFixtures.A,
                Pay004TestFixtures.WORKER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockedPackAndCrossWorkerLineageCannotProduceResult() {
        assertThatThrownBy(() -> lineage(
                RetirementYearEndCaseContract.CountryPackState.NOT_INSTALLED,
                null, 0, 0, null, 0, null, Pay004TestFixtures.WORKER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lineage(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                null, 0, 0, Pay004TestFixtures.POLICY, 7,
                Pay004TestFixtures.A, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        RetirementYearEndCase validated = Pay004TestFixtures.draft(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED)
                .validate(RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                        Pay004TestFixtures.SUBJECT,
                        Pay004TestFixtures.NOW.plusSeconds(1));
        RetirementYearEndCase.ResultLineage anotherCase =
                new RetirementYearEndCase.ResultLineage(
                        1, UUID.randomUUID(), 3, Pay004TestFixtures.WORKER,
                        Pay004TestFixtures.RUN, 3, 1,
                        Pay004TestFixtures.WORKER_RESULT,
                        Pay004TestFixtures.WORKER,
                        RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                        null, 0, 0, Pay004TestFixtures.POLICY, 7,
                        Pay004TestFixtures.A,
                        "pay.retirement.foundation", 7,
                        java.time.LocalDate.parse("2026-10-01"), null,
                        Pay004TestFixtures.A,
                        new Pay004Models.PostedMoney(
                                new BigDecimal("1.0000"), "GBP", "HALF_EVEN"),
                        "RETIREMENT_FINAL", java.util.List.of(),
                        java.util.List.of(),
                        Pay004TestFixtures.B, Pay004TestFixtures.C,
                        Pay004TestFixtures.NOW.plusSeconds(2));
        assertThatThrownBy(() -> validated.calculate(
                anotherCase, Pay004TestFixtures.MAKER,
                Pay004TestFixtures.NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        RetirementYearEndCase.ResultLineage staleCaseRevision = lineage(
                4, RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                null, 0, 0, Pay004TestFixtures.POLICY, 7,
                Pay004TestFixtures.A, Pay004TestFixtures.WORKER);
        assertThatThrownBy(() -> validated.calculate(
                staleCaseRevision, Pay004TestFixtures.MAKER,
                Pay004TestFixtures.NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validated.calculate(
                Pay004TestFixtures.nonPackLineage(),
                Pay004TestFixtures.MAKER,
                Pay004TestFixtures.NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postedMoneyIsExactCanonicalAndNeverDatabaseRounded() {
        Pay004Models.PostedMoney value = new Pay004Models.PostedMoney(
                new BigDecimal("999999999999999.9999"), "GBP", "HALF_EVEN");
        assertThat(value.amount().toPlainString())
                .isEqualTo("999999999999999.9999");
        assertThatThrownBy(() -> new Pay004Models.PostedMoney(
                new BigDecimal("1.00001"), "GBP", "HALF_EVEN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay004Models.MoneyValue(
                "1E+2", "GBP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay004Models.PostedMoney(
                new BigDecimal("1.0000"), "gbp", "HALF_EVEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultDigestIsVersionedDeterministicAndCannotBeCallerSupplied() {
        RetirementYearEndCase.ResultLineage first =
                Pay004TestFixtures.nonPackLineage();
        RetirementYearEndCase.ResultLineage same =
                Pay004TestFixtures.nonPackLineage();
        RetirementYearEndCase.ResultLineage changedRun = lineageWith(
                4, new BigDecimal("1234.5000"));
        RetirementYearEndCase.ResultLineage changedAmount = lineageWith(
                3, new BigDecimal("1234.5001"));
        RetirementYearEndCase.ResultLineage changedPolicy = nonPackWith(
                "pay.retirement.changed", null, Pay004TestFixtures.B,
                "RETIREMENT_FINAL", List.of());
        RetirementYearEndCase.ResultLineage changedWindow = nonPackWith(
                "pay.retirement.foundation", LocalDate.parse("2026-12-31"),
                Pay004TestFixtures.B, "RETIREMENT_FINAL", List.of());
        RetirementYearEndCase.BasisFact basis = new RetirementYearEndCase.BasisFact(
                UUID.fromString("00000000-0000-0000-0000-000000000901"),
                "RETIREMENT_BASIS", "HRM",
                "HRM.EmploymentTermination.v1",
                Pay004TestFixtures.EMPLOYMENT, 3, Pay004TestFixtures.A,
                Pay004TestFixtures.NOW.plusSeconds(2));
        RetirementYearEndCase.ResultLineage changedInputs = nonPackWith(
                "pay.retirement.foundation", null, Pay004TestFixtures.B,
                "RETIREMENT_FINAL", List.of(basis));

        assertThat(first.resultDigest())
                .matches("[0-9a-f]{64}")
                .isEqualTo(same.resultDigest())
                .isNotEqualTo(changedRun.resultDigest())
                .isNotEqualTo(changedAmount.resultDigest())
                .isNotEqualTo(changedPolicy.resultDigest())
                .isNotEqualTo(changedWindow.resultDigest())
                .isNotEqualTo(changedInputs.resultDigest());
        assertThatThrownBy(() -> nonPackWith(
                "pay.retirement.foundation", null, Pay004TestFixtures.B,
                "LEGAL_FINAL", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roundingStage");
        assertThat(Arrays.stream(
                RetirementYearEndCase.ResultLineage.class
                        .getRecordComponents()).map(component ->
                component.getName()).toList())
                .contains("policyDigest", "roundingStage",
                        "basisFacts", "evidenceFacts")
                .doesNotContain("resultDigest", "caseInputsDigest");
    }

    private RetirementYearEndCase.ResultLineage lineage(
            RetirementYearEndCaseContract.CountryPackState packState,
            UUID pack,
            long packSnapshotRevision,
            long activationRevision,
            UUID policy,
            long policyRevision,
            String policyDigest,
            UUID workerResultWorker) {
        return lineage(3, packState, pack, packSnapshotRevision,
                activationRevision, policy, policyRevision, policyDigest,
                workerResultWorker);
    }

    private RetirementYearEndCase.ResultLineage lineage(
            long caseRevision,
            RetirementYearEndCaseContract.CountryPackState packState,
            UUID pack,
            long packSnapshotRevision,
            long activationRevision,
            UUID policy,
            long policyRevision,
            String policyDigest,
            UUID workerResultWorker) {
        return new RetirementYearEndCase.ResultLineage(
                1, Pay004TestFixtures.CASE, caseRevision,
                Pay004TestFixtures.WORKER,
                Pay004TestFixtures.RUN, 3, 1, Pay004TestFixtures.WORKER_RESULT,
                workerResultWorker, packState, pack, packSnapshotRevision,
                activationRevision, policy, policyRevision, policyDigest,
                packState == RetirementYearEndCaseContract.CountryPackState.ACTIVE
                        ? "gb.retirement" : "pay.retirement.foundation",
                packState == RetirementYearEndCaseContract.CountryPackState.ACTIVE
                        ? activationRevision : policyRevision,
                java.time.LocalDate.parse("2026-10-01"), null,
                packState == RetirementYearEndCaseContract.CountryPackState.ACTIVE
                        ? Pay004TestFixtures.A : policyDigest,
                new Pay004Models.PostedMoney(
                        new BigDecimal("1.0000"), "GBP", "HALF_EVEN"),
                "RETIREMENT_FINAL", java.util.List.of(),
                java.util.List.of(),
                Pay004TestFixtures.B, Pay004TestFixtures.C,
                Pay004TestFixtures.NOW.plusSeconds(2));
    }

    private RetirementYearEndCase.ResultLineage lineageWith(
            long runRevision, BigDecimal amount) {
        return new RetirementYearEndCase.ResultLineage(
                1, Pay004TestFixtures.CASE, 3,
                Pay004TestFixtures.WORKER,
                Pay004TestFixtures.RUN, runRevision, 1,
                Pay004TestFixtures.WORKER_RESULT,
                Pay004TestFixtures.WORKER,
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                null, 0, 0, Pay004TestFixtures.POLICY, 7,
                Pay004TestFixtures.B,
                "pay.retirement.foundation", 7,
                java.time.LocalDate.parse("2026-10-01"), null,
                Pay004TestFixtures.B,
                new Pay004Models.PostedMoney(amount, "GBP", "HALF_EVEN"),
                "RETIREMENT_FINAL", java.util.List.of(),
                java.util.List.of(),
                Pay004TestFixtures.B, Pay004TestFixtures.C,
                Pay004TestFixtures.NOW.plusSeconds(2));
    }

    private RetirementYearEndCase.ResultLineage nonPackWith(
            String policyKey,
            LocalDate effectiveTo,
            String policyDigest,
            String roundingStage,
            List<RetirementYearEndCase.BasisFact> basisFacts) {
        return new RetirementYearEndCase.ResultLineage(
                1, Pay004TestFixtures.CASE, 3,
                Pay004TestFixtures.WORKER,
                Pay004TestFixtures.RUN, 3, 1,
                Pay004TestFixtures.WORKER_RESULT,
                Pay004TestFixtures.WORKER,
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                null, 0, 0, Pay004TestFixtures.POLICY, 7,
                policyDigest, policyKey, 7,
                LocalDate.parse("2026-10-01"), effectiveTo, policyDigest,
                new Pay004Models.PostedMoney(
                        new BigDecimal("1234.5000"), "GBP", "HALF_EVEN"),
                roundingStage, basisFacts, List.of(),
                Pay004TestFixtures.B, Pay004TestFixtures.C,
                Pay004TestFixtures.NOW.plusSeconds(2));
    }
}
