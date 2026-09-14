package com.dwp.services.approval.security;

import com.dwp.services.approval.domain.ApprovalWorkDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalWorkInputContractTest {
    @Test void missingVersionAndRevisionCannotSilentlyBecomeZero() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ApprovalWorkDtos.DraftCommand(null, "key", "reason")))
                    .extracting(violation -> violation.getPropertyPath().toString()).contains("expectedVersion");
            assertThat(validator.validate(new ApprovalWorkDtos.RecoverDraft(null, null, "key", "reason")))
                    .extracting(violation -> violation.getPropertyPath().toString()).contains("revision", "expectedVersion");
        }
    }

    @Test void invalidKeysAndRiskThresholdsAreNotAccepted() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ApprovalWorkDtos.DraftCommand(0L, "space key", "reason"))).isNotEmpty();
            assertThat(validator.validate(new ApprovalWorkDtos.SearchFilter("", "", "", null,
                    ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.PRIORITY, 101))).isNotEmpty();
        }
    }
}
