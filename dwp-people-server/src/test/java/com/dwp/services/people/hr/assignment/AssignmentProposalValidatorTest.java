package com.dwp.services.people.hr.assignment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentProposalValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private final AssignmentProposalValidator validator = new AssignmentProposalValidator(
            Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC));
    private final AssignmentProposalValidator.CurrentAssignment current =
            new AssignmentProposalValidator.CurrentAssignment(
                    LocalDate.of(2025, 1, 1), null,
                    Map.of("jobProfileKey", "ENGINEER",
                            "locationKey", "SEOUL",
                            "businessTitle", "Engineer"));

    @Test
    void allowsHistoricalCorrectionInsideTheTargetSlice() {
        var findings = validator.validate(
                "CORRECTION", LocalDate.of(2025, 7, 1),
                Map.of("businessTitle", "Software Engineer"), current,
                new AssignmentProposalValidator.ReferenceValidation(true, Set.of()));

        assertThat(findings).isEmpty();
    }

    @Test
    void prospectiveChangesRejectBackdatingAndRequireTypeSpecificFields() {
        var findings = validator.validate(
                "CHANGE_LOCATION", TODAY.minusDays(1),
                Map.of("businessTitle", "Senior Engineer"), current,
                new AssignmentProposalValidator.ReferenceValidation(true, Set.of()));

        assertThat(findings).extracting(AssignmentProposalDtos.ValidationFinding::code)
                .containsExactlyInAnyOrder("REQUIRED_CHANGE_FIELD", "PAST_EFFECTIVE_DATE");
    }

    @Test
    void failsClosedOnNoOpInactiveReasonAndMissingReference() {
        var findings = validator.validate(
                "CORRECTION", TODAY,
                Map.of("locationKey", "SEOUL"), current,
                new AssignmentProposalValidator.ReferenceValidation(
                        false, Set.of("locationKey")));

        assertThat(findings).extracting(AssignmentProposalDtos.ValidationFinding::code)
                .containsExactlyInAnyOrder(
                        "NO_EFFECTIVE_CHANGE", "INACTIVE_REASON", "REFERENCE_NOT_FOUND");
    }
}
