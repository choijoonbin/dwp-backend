package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static org.assertj.core.api.Assertions.assertThat;

class NavigationPolicyRuntimeControlsTest {

    @Test
    void derivesInstalledAndPageAuthorizedNodesOnlyFromServerInputs() {
        ServerNavigationPolicyEligibilityProvider provider =
                new ServerNavigationPolicyEligibilityProvider(
                        String.join(",", NavigationPolicyCanonical.NODE_IDS));

        assertThat(provider.installedNodeIds(42))
                .containsExactlyInAnyOrderElementsOf(NavigationPolicyCanonical.NODE_IDS);
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.HRIS_WORKFORCE:VIEW")))
                .contains("BASE:hr-operations-home", "BASE:hr-people")
                .doesNotContain("BASE:hr-organization-positions",
                        "MODERN:hcm.operations.recruiting");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("APP.HCM:VIEW"))).isEmpty();
    }

    @Test
    void rolloutDefaultsOffAndRequiresExplicitTenantEnrollment() {
        ServerNavigationPolicyRolloutProvider off =
                new ServerNavigationPolicyRolloutProvider(RolloutMode.OFF, "42");
        assertThat(off.enrollment(42).enrolled()).isFalse();
        assertThat(off.enrollment(42).mode()).isEqualTo(RolloutMode.OFF);

        ServerNavigationPolicyRolloutProvider enforced =
                new ServerNavigationPolicyRolloutProvider(RolloutMode.ENFORCED, "42,43");
        assertThat(enforced.enrollment(42).enrolled()).isTrue();
        assertThat(enforced.enrollment(42).mode()).isEqualTo(RolloutMode.ENFORCED);
        assertThat(enforced.enrollment(99).enrolled()).isFalse();
        assertThat(enforced.enrollment(99).mode()).isEqualTo(RolloutMode.OFF);
    }

    @Test
    void defaultStepUpVerifierFailsClosedUntilSignatureVerifierIsIntegrated() {
        FailClosedStepUpEvidenceVerifier verifier = new FailClosedStepUpEvidenceVerifier();
        assertThat(verifier.verify(null)).isFalse();
    }
}
