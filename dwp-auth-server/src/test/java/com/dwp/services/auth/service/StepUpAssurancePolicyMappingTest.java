package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StepUpAssurancePolicyMappingTest {
    @Test
    void onlyTheTwoExistingPoliciesMapToConfiguredAcrWithIndependentReadOnly() {
        for (String policy : List.of("STEPUP-MGMT-HIGH-V1", "STEPUP-MGMT-CRITICAL-V1")) {
            for (boolean readOnly : List.of(false, true)) {
                var result = challenge(policy, "urn:dwp:acr:mfa", readOnly);
                assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
                assertThat(result.requiredAssurance()).isEqualTo("urn:dwp:acr:mfa");
                assertThat(result.requestPolicyRef()).isEqualTo(policy);
                assertThat(result.effectiveReadOnly()).isEqualTo(readOnly);
            }
        }
    }

    @Test
    void unknownPolicyAndMissingOrInvalidConfigurationRemainUnavailable() {
        for (String policy : List.of("STEPUP-MGMT-UNKNOWN-V1", "STEPUP-MGMT-HIGH-V2", "*", "")) {
            assertUnavailable(challenge(policy, "urn:dwp:acr:mfa", false));
        }
        for (String acr : java.util.Arrays.asList(null, "", " ", " urn:dwp:acr:mfa", "x".repeat(201))) {
            assertUnavailable(challenge("STEPUP-MGMT-HIGH-V1", acr, false));
            assertUnavailable(challenge("STEPUP-MGMT-CRITICAL-V1", acr, false));
        }
    }

    private static Evaluation challenge(String policy, String acr, boolean readOnly) {
        return Evaluation.challenge(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED, "STEP_UP_REQUIRED",
                policy, acr, ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT, List.of(), List.of(),
                readOnly, null, "APP.APPROVALS");
    }

    private static void assertUnavailable(Evaluation result) {
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo("STEP_UP_ASSURANCE_POLICY_UNAVAILABLE");
        assertThat(result.requiredAssurance()).isNull();
        assertThat(result.requestPolicyRef()).isNull();
    }
}
