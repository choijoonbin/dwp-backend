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
                        String.join(",", NavigationPolicyCanonical.NODE_IDS),
                        "BASE:hr-people,BASE:my-hr-profile");

        assertThat(provider.installedNodeIds(42))
                .containsExactlyInAnyOrderElementsOf(NavigationPolicyCanonical.NODE_IDS);
        assertThat(provider.deployedNodeIds(42))
                .containsExactlyInAnyOrder("BASE:hr-people", "BASE:my-hr-profile");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.WORKFORCE:VIEW")))
                .contains("BASE:hr-operations-home", "BASE:hr-people",
                        "BASE:hr-employment-assignments")
                .doesNotContain("BASE:hr-organization-positions",
                        "MODERN:hcm.operations.recruiting");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.HRIS_WORKFORCE:VIEW")))
                .contains("BASE:hr-employment-assignments",
                        "BASE:hr-employee-change-requests")
                .doesNotContain("BASE:hr-operations-home", "BASE:hr-people");
        assertThat(provider.pageAuthorizedNodeIds(Set.of(
                "ACTION.WORKFORCE_ORG_DESIGN:VIEW")))
                .containsExactlyInAnyOrder("BASE:hr-organization-positions");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("APP.HCM:VIEW"))).isEmpty();
        assertThat(provider.pageAuthorizedNodeIds(Set.of(
                "DATA.HRIS_RECRUITING_CASE:VIEW",
                "DATA.HRIS_WORKFORCE_SCENARIO:VIEW",
                "DATA.HRIS_HR_CASE:VIEW"))).isEmpty();
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.HRIS_SELF_PROFILE:VIEW")))
                .containsExactlyInAnyOrder("BASE:my-employment-documents");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.HR_TIME:VIEW")))
                .contains("BASE:hr-operations-home", "BASE:team-time",
                        "BASE:time-command-center", "BASE:time-clock-ingestion",
                        "BASE:time-interpretation", "BASE:time-exceptions")
                .doesNotContain("BASE:my-time", "BASE:team-home", "BASE:time-work-plans");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.HR_PAY:VIEW")))
                .contains("BASE:hr-operations-home", "BASE:pay-command-center")
                .doesNotContain("BASE:pay-population");
        assertThat(provider.pageAuthorizedNodeIds(Set.of(
                "DATA.HRIS_PAYROLL_STATUS:VIEW"))).isEmpty();
        assertThat(provider.pageAuthorizedNodeIds(Set.of(
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH"))).isEmpty();
        assertThat(provider.pageAuthorizedNodeIds(Set.of(
                "ACTION.APPROVAL_DELEGATION:VIEW")))
                .doesNotContain("BASE:team-delegation");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("CONFIG.HRIS_WORKBENCH:VIEW")))
                .containsExactlyInAnyOrder("BASE:settings-reference", "BASE:settings-payroll")
                .doesNotContain("BASE:settings-enterprise", "BASE:dwp-platform");

        for (String permission : Set.of(
                "DATA.WORKFORCE:VIEW", "DATA.HR_TIME:VIEW", "DATA.HR_ABSENCE:VIEW",
                "DATA.HR_BENEFITS:VIEW", "DATA.HR_PAY:VIEW", "DATA.HR_TALENT:VIEW")) {
            assertThat(provider.pageAuthorizedPath(
                    "BASE:hr-operations-home", "/hr/operations", Set.of(permission)))
                    .as(permission)
                    .isTrue();
        }
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-operations-home", "/hr/operations",
                Set.of("DATA.HRIS_WORKFORCE:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-employment-assignments",
                "/hr/operations/people/employment-assignments",
                Set.of("DATA.HRIS_WORKFORCE:VIEW"))).isTrue();
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-employment-assignments", "/hr/operations/assignments",
                Set.of("DATA.HRIS_WORKFORCE:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-employment-assignments", "/hr/operations/assignments",
                Set.of("DATA.WORKFORCE:VIEW"))).isTrue();
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-organization-positions", "/hr/design/organization",
                Set.of("ACTION.WORKFORCE_ORG_DESIGN:VIEW"))).isTrue();
        assertThat(provider.pageAuthorizedPath(
                "BASE:my-time", "/hr/time", Set.of("DATA.HR_TIME:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:my-employment-documents", "/hr/me/employment-documents",
                Set.of("DATA.HRIS_SELF_PROFILE:VIEW"))).isTrue();
        assertThat(provider.pageAuthorizedPath(
                "BASE:team-time", "/hr/team/time", Set.of("DATA.HR_TIME:VIEW"))).isTrue();
        assertThat(provider.pageAuthorizedPath(
                "BASE:pay-command-center", "/hr/operations/pay",
                Set.of("DATA.HRIS_PAYROLL_STATUS:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:pay-command-center", "/hr/operations/pay",
                Set.of("DATA.HR_PAY:VIEW"))).isTrue();
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-data-quality", "/hr/data/integrations",
                Set.of("ACTION.WORKFORCE_DATA_OPERATIONS:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:my-hr-profile", "/hr/me",
                Set.of("DATA.HRIS_SELF_PROFILE:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:team-home", "/hr/team", Set.of("DATA.HR_TIME:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:my-leave", "/hr/absence", Set.of("DATA.HR_ABSENCE:VIEW")))
                .isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:my-hr-requests", "/hr/services",
                Set.of("DATA.HRIS_SELF_PROFILE:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-people", "/hr/directory", Set.of("DATA.WORKFORCE:VIEW")))
                .isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-organization-positions", "/hr/organization",
                Set.of("ACTION.WORKFORCE_ORG_DESIGN:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:settings-operations", "/hr/manage/system",
                Set.of("CONFIG.HRIS_WORKBENCH:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:pay-population", "/hr/operations/payroll/population",
                Set.of("DATA.HRIS_PAYROLL_STATUS:VIEW"))).isFalse();
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
