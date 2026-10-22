package com.dwp.services.payroll.statutory.pay004;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.dwp.services.payroll.statutory.pay004.Pay004TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Pay004RetirementCaseStateTest {

    @Test
    void stateAndCountryPackSetsAreExact() {
        assertThat(Arrays.stream(RetirementYearEndCase.State.values())
                .map(Enum::name)).containsExactly(
                "DRAFT", "VALIDATED", "BLOCKED_PACK_REQUIRED", "CALCULATED",
                "APPROVED", "FINALIZED", "CORRECTED", "CANCELLED");
        assertThat(Arrays.stream(RetirementYearEndCase.CountryPackState.values())
                .map(Enum::name)).containsExactly(
                "NOT_REQUIRED", "NOT_INSTALLED", "DISABLED", "ACTIVE");
    }

    @Test
    void happyPathIsAppendOnlyAndMakerCheckerProtected() {
        RetirementYearEndCase draft = Pay004TestFixtures.draft(
                RetirementYearEndCase.CountryPackState.NOT_REQUIRED);
        RetirementYearEndCase validated = draft.validate(
                RetirementYearEndCase.CountryPackState.NOT_REQUIRED,
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

        assertThat(draft.state()).isEqualTo(RetirementYearEndCase.State.DRAFT);
        assertThat(validated.state())
                .isEqualTo(RetirementYearEndCase.State.VALIDATED);
        assertThat(calculated.state())
                .isEqualTo(RetirementYearEndCase.State.CALCULATED);
        assertThat(approved.state())
                .isEqualTo(RetirementYearEndCase.State.APPROVED);
        assertThat(finalized.state())
                .isEqualTo(RetirementYearEndCase.State.FINALIZED);
        assertThat(corrected.state())
                .isEqualTo(RetirementYearEndCase.State.CORRECTED);
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
        for (RetirementYearEndCase.CountryPackState packState :
                java.util.List.of(
                        RetirementYearEndCase.CountryPackState.NOT_INSTALLED,
                        RetirementYearEndCase.CountryPackState.DISABLED)) {
            RetirementYearEndCase blocked = Pay004TestFixtures.draft(packState)
                    .validate(packState, Pay004TestFixtures.SUBJECT,
                            NOW.plusSeconds(1));
            assertThat(blocked.state()).isEqualTo(
                    RetirementYearEndCase.State.BLOCKED_PACK_REQUIRED);
            RetirementYearEndCase cancelled = blocked.cancel(
                    Pay004TestFixtures.SUBJECT, NOW.plusSeconds(2));
            assertThat(cancelled.state())
                    .isEqualTo(RetirementYearEndCase.State.CANCELLED);
            assertThat(cancelled.terminal()).isTrue();
        }
    }

    @Test
    void illegalTransitionsAndNonMonotonicTimeFailClosed() {
        RetirementYearEndCase draft = Pay004TestFixtures.draft(
                RetirementYearEndCase.CountryPackState.ACTIVE);
        assertThatThrownBy(() -> draft.approve(
                Pay004TestFixtures.CHECKER, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft.validate(
                RetirementYearEndCase.CountryPackState.ACTIVE,
                Pay004TestFixtures.SUBJECT,
                NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void caseRevisionDigestIsComputedFromCanonicalImmutableFacts() {
        RetirementYearEndCase first = Pay004TestFixtures.draft(
                RetirementYearEndCase.CountryPackState.NOT_REQUIRED);
        RetirementYearEndCase same = Pay004TestFixtures.draft(
                RetirementYearEndCase.CountryPackState.NOT_REQUIRED);
        RetirementYearEndCase changedState = first.validate(
                RetirementYearEndCase.CountryPackState.NOT_REQUIRED,
                Pay004TestFixtures.SUBJECT, NOW.plusSeconds(1));

        assertThat(first.revisionDigest())
                .matches("[0-9a-f]{64}")
                .isEqualTo(same.revisionDigest())
                .isNotEqualTo(changedState.revisionDigest());
        assertThat(changedState.predecessorDigest())
                .isEqualTo(first.revisionDigest());
    }
}
