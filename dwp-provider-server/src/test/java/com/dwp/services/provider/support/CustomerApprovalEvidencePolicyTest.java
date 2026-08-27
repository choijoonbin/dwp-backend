package com.dwp.services.provider.support;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerApprovalEvidencePolicyTest {

    @Test
    void permitsReferenceFixturesOnlyInTheLocalVerificationEnvironment() {
        CustomerApprovalEvidencePolicy policy = new CustomerApprovalEvidencePolicy("local", true);

        assertThat(policy.requireVerified("CASE-LOCAL-1001"))
                .isEqualTo(CustomerApprovalEvidencePolicy.LOCAL_REFERENCE_ONLY);
    }

    @Test
    void failsClosedInProductionAndOtherNonLocalEnvironments() {
        for (String environment : java.util.List.of("production", "prod", "staging", "dev")) {
            CustomerApprovalEvidencePolicy policy =
                    new CustomerApprovalEvidencePolicy(environment, true);

            assertThatThrownBy(() -> policy.requireVerified("CASE-NONLOCAL-1001"))
                    .as(environment)
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("disabled until authoritative, signed approval evidence");
        }
    }

    @Test
    void unknownOrUnsetEnvironmentAndMissingExplicitLocalOptInFailClosed() {
        for (CustomerApprovalEvidencePolicy policy : java.util.List.of(
                new CustomerApprovalEvidencePolicy("", true),
                new CustomerApprovalEvidencePolicy("unknown", true),
                new CustomerApprovalEvidencePolicy("local", false))) {
            assertThatThrownBy(() -> policy.requireVerified("CASE-1001"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("disabled until authoritative, signed approval evidence");
        }
    }
}
