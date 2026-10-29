package com.dwp.services.payroll.statutory.pay004;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.dwp.services.payroll.statutory.pay004.Pay004TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Pay004RetirementCaseStateTest {

    @Test
    void stateAndCountryPackSetsAreExact() {
        assertThat(Arrays.stream(RetirementYearEndCaseContract.State.values())
                .map(Enum::name)).containsExactly(
                "DRAFT", "VALIDATED", "BLOCKED_PACK_REQUIRED", "CALCULATED",
                "APPROVED", "FINALIZED", "CORRECTED", "CANCELLED");
        assertThat(Arrays.stream(RetirementYearEndCaseContract.CountryPackState.values())
                .map(Enum::name)).containsExactly(
                "NOT_REQUIRED", "NOT_INSTALLED", "DISABLED", "ACTIVE");
    }

    @Test
    void happyPathIsAppendOnlyAndMakerCheckerProtected() {
        RetirementYearEndCase draft = Pay004TestFixtures.draft(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED);
        RetirementYearEndCase validated = draft.validate(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                Pay004TestFixtures.SUBJECT, NOW.plusSeconds(1));
        RetirementYearEndCase calculated = validated.calculate(
                Pay004TestFixtures.nonPackLineage(), Pay004TestFixtures.MAKER,
                NOW.plusSeconds(2));
        RetirementYearEndCase approved = calculated.approve(
                Pay004TestFixtures.CHECKER, NOW.plusSeconds(3));
        RetirementYearEndCase finalized = approved.finalizeCase(
                Pay004TestFixtures.CHECKER, NOW.plusSeconds(4));
        RetirementYearEndCase corrected = finalized.correct(
                Pay004TestFixtures.CHECKER, NOW.plusSeconds(5));

        assertThat(draft.state()).isEqualTo(RetirementYearEndCaseContract.State.DRAFT);
        assertThat(validated.state())
                .isEqualTo(RetirementYearEndCaseContract.State.VALIDATED);
        assertThat(calculated.state())
                .isEqualTo(RetirementYearEndCaseContract.State.CALCULATED);
        assertThat(approved.state())
                .isEqualTo(RetirementYearEndCaseContract.State.APPROVED);
        assertThat(finalized.state())
                .isEqualTo(RetirementYearEndCaseContract.State.FINALIZED);
        assertThat(corrected.state())
                .isEqualTo(RetirementYearEndCaseContract.State.CORRECTED);
        assertThat(corrected.revision()).isEqualTo(6);
        assertThat(corrected.predecessorDigest())
                .isEqualTo(finalized.revisionDigest());
        assertThat(draft.transitionSubjectPublicId())
                .isEqualTo(Pay004TestFixtures.SUBJECT);
        assertThat(validated.transitionSubjectPublicId())
                .isEqualTo(Pay004TestFixtures.SUBJECT);
        assertThat(calculated.transitionSubjectPublicId())
                .isEqualTo(Pay004TestFixtures.MAKER);
        assertThat(approved.transitionSubjectPublicId())
                .isEqualTo(Pay004TestFixtures.CHECKER);
        assertThat(finalized.transitionSubjectPublicId())
                .isEqualTo(Pay004TestFixtures.CHECKER);
        assertThat(corrected.transitionSubjectPublicId())
                .isEqualTo(Pay004TestFixtures.CHECKER);
        assertThat(finalized.terminal()).isTrue();
        assertThat(corrected.terminal()).isTrue();

        assertThatThrownBy(() -> calculated.approve(
                Pay004TestFixtures.MAKER, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finalized.cancel(
                Pay004TestFixtures.SUBJECT, NOW.plusSeconds(6)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingOrDisabledRequiredPackBlocksAndOnlyPreCalculationCanCancel() {
        for (RetirementYearEndCaseContract.CountryPackState packState :
                java.util.List.of(
                        RetirementYearEndCaseContract.CountryPackState.NOT_INSTALLED,
                        RetirementYearEndCaseContract.CountryPackState.DISABLED)) {
            RetirementYearEndCase blocked = Pay004TestFixtures.draft(packState)
                    .validate(packState, Pay004TestFixtures.SUBJECT,
                            NOW.plusSeconds(1));
            assertThat(blocked.state()).isEqualTo(
                    RetirementYearEndCaseContract.State.BLOCKED_PACK_REQUIRED);
            RetirementYearEndCase cancelled = blocked.cancel(
                    Pay004TestFixtures.SUBJECT, NOW.plusSeconds(2));
            assertThat(cancelled.state())
                    .isEqualTo(RetirementYearEndCaseContract.State.CANCELLED);
            assertThat(cancelled.terminal()).isTrue();
        }
    }

    @Test
    void illegalTransitionsAndNonMonotonicTimeFailClosed() {
        RetirementYearEndCase draft = Pay004TestFixtures.draft(
                RetirementYearEndCaseContract.CountryPackState.ACTIVE);
        assertThatThrownBy(() -> draft.approve(
                Pay004TestFixtures.CHECKER, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft.validate(
                RetirementYearEndCaseContract.CountryPackState.ACTIVE,
                Pay004TestFixtures.SUBJECT,
                NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void caseRevisionDigestIsComputedFromCanonicalImmutableFacts() {
        RetirementYearEndCase first = Pay004TestFixtures.draft(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED);
        RetirementYearEndCase same = Pay004TestFixtures.draft(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED);
        RetirementYearEndCase changedState = first.validate(
                RetirementYearEndCaseContract.CountryPackState.NOT_REQUIRED,
                Pay004TestFixtures.SUBJECT, NOW.plusSeconds(1));

        assertThat(first.revisionDigest())
                .matches("[0-9a-f]{64}")
                .isEqualTo(same.revisionDigest())
                .isNotEqualTo(changedState.revisionDigest());
        assertThat(changedState.predecessorDigest())
                .isEqualTo(first.revisionDigest());
    }
}
