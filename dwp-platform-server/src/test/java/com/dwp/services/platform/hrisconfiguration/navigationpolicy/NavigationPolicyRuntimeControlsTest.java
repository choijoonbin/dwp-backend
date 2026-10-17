package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static org.assertj.core.api.Assertions.assertThat;

class NavigationPolicyRuntimeControlsTest {

    @Test
    void exposesOnlyAuditedImplementationNodesAndExactPagePaths() {
        ServerNavigationPolicyEligibilityProvider provider =
                new ServerNavigationPolicyEligibilityProvider(
                        String.join(",", ServerNavigationPolicyEligibilityProvider
                                .IMPLEMENTATION_NODE_IDS)
                                + ",BASE:my-time,BASE:team-time,BASE:pay-command-center",
                        "BASE:my-employment-documents,BASE:hr-people,"
                                + "BASE:hr-employment-assignments,"
                                + "BASE:hr-organization-positions,BASE:my-time");

        assertThat(provider.installedNodeIds(42))
                .containsExactlyInAnyOrderElementsOf(
                        ServerNavigationPolicyEligibilityProvider.IMPLEMENTATION_NODE_IDS)
                .doesNotContain("BASE:pay-command-center");
        assertThat(provider.deployedNodeIds(42))
                .containsExactlyInAnyOrder(
                        "BASE:my-employment-documents", "BASE:hr-people",
                        "BASE:hr-employment-assignments", "BASE:hr-organization-positions",
                        "BASE:my-time");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.WORKFORCE:VIEW")))
                .containsExactlyInAnyOrder(
                        "BASE:hr-people", "BASE:hr-employment-assignments")
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
                .containsExactlyInAnyOrder("BASE:time-clock-ingestion",
                        "BASE:time-interpretation", "BASE:time-exceptions")
                .doesNotContain("BASE:my-time", "BASE:team-home", "BASE:team-time",
                        "BASE:time-command-center", "BASE:time-work-plans");
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.HR_PAY:VIEW"))).isEmpty();
        assertThat(provider.pageAuthorizedNodeIds(Set.of("DATA.HR_TALENT:VIEW")))
                .containsExactly("BASE:performance-review-operations");
        assertThat(provider.pageAuthorizedNodeIds(Set.of(
                "DATA.HRIS_COMPENSATION_PLAN:VIEW")))
                .containsExactly("MODERN:hcm.operations.compensation-planning");
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

        assertThat(provider.pageAuthorizedPath(
                "BASE:hr-operations-home", "/hr/operations",
                Set.of("DATA.WORKFORCE:VIEW"))).isFalse();
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
                "BASE:team-time", "/hr/team/time", Set.of("DATA.HR_TIME:VIEW"))).isFalse();
        assertThat(provider.pageAuthorizedPath(
                "BASE:pay-command-center", "/hr/operations/pay",
                Set.of("DATA.HR_PAY:VIEW"))).isFalse();
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

        Map<String, List<String>> assignmentPaths = provider.pageAuthorizedPaths(Set.of(
                "DATA.HRIS_WORKFORCE:VIEW", "DATA.WORKFORCE:VIEW"));
        assertThat(assignmentPaths.get("BASE:hr-employment-assignments"))
                .containsExactly(
                        "/hr/operations/people/employment-assignments",
                        "/hr/operations/assignments");
        assertThat(assignmentPaths.keySet())
                .containsExactlyInAnyOrder(
                        "BASE:hr-people", "BASE:hr-employment-assignments",
                        "BASE:hr-employee-change-requests");

        Map<String, List<String>> v47TimePaths = provider.v47PageAuthorizedPaths(
                Set.of("DATA.HR_TIME:VIEW"));
        assertThat(v47TimePaths).containsOnlyKeys(
                "BASE:team-time", "BASE:hr-operations-home", "BASE:time-work-plans");
        assertThat(v47TimePaths.get("BASE:team-time"))
                .containsExactly("/hr/team/time");
        assertThat(v47TimePaths.get("BASE:hr-operations-home"))
                .containsExactly("/hr/operations/home", "/hr/operations");
        assertThat(v47TimePaths.get("BASE:time-work-plans"))
                .containsExactly("/hr/operations/time/work-plans");

        Map<String, List<String>> v47AbsencePaths = provider.v47PageAuthorizedPaths(
                Set.of("DATA.HR_ABSENCE:VIEW"));
        assertThat(v47AbsencePaths.get("BASE:team-leave"))
                .containsExactly("/hr/team/leave", "/hr/team/absence");
        assertThat(v47AbsencePaths.get("BASE:time-leave-operations"))
                .containsExactly(
                        "/hr/operations/time/leave-operations",
                        "/hr/operations/absence");
        assertThat(v47AbsencePaths.values().stream().flatMap(List::stream).toList())
                .doesNotContain(
                        "/hr/operations/time", "/hr/operations/pay",
                        "/hr/operations/talent", "/hr/data/exports");
        assertThat(provider.v47PageAuthorizedPath(
                "BASE:team-time", "/hr/team/time", Set.of("DATA.HR_TIME:VIEW")))
                .isTrue();
        assertThat(provider.v47PageAuthorizedPath(
                "BASE:team-leave", "/hr/team/absence",
                Set.of("DATA.HR_ABSENCE:VIEW"))).isTrue();
        assertThat(provider.v47PageAuthorizedPath(
                "BASE:pay-command-center", "/hr/operations/pay",
                Set.of("DATA.HR_PAY:VIEW"))).isFalse();

        Map<String, List<String>> controlledExportPaths =
                provider.v47PageAuthorizedPaths(Set.of(
                        "ACTION.WORKFORCE_CONTROLLED_EXPORT:VIEW"));
        assertThat(controlledExportPaths).containsOnlyKeys(
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID);
        assertThat(controlledExportPaths.get(
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID))
                .containsExactly("/hr/data/exports");
        assertThat(provider.v47PageAuthorizedPath(
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID,
                "/hr/data/exports",
                Set.of("ACTION.WORKFORCE_CONTROLLED_EXPORT:VIEW"))).isTrue();
        assertThat(provider.v47PageAuthorizedPaths(Set.of("APP.HCM:VIEW")))
                .doesNotContainKey(
                        NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID);
        assertThat(provider.v47PageAuthorizedPaths(Set.of(
                "DATA_GOVERNANCE_READ", "ADMIN.AUDIT_VIEW:VIEW")))
                .doesNotContainKey(
                        NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID);
    }

    @Test
    void applicationDefaultsInstallEighteenDeployThreeAndKeepV3ActivationOff()
            throws Exception {
        String application = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(application)
                .contains("enabled: ${DWP_HRIS_NAVIGATION_POLICY_ENABLED:false}")
                .contains("mode: ${DWP_HRIS_NAVIGATION_POLICY_MODE:OFF}")
                .contains("enrolled-tenants: ${DWP_HRIS_NAVIGATION_POLICY_ENROLLED_TENANTS:}")
                .contains("DWP_HRIS_NAVIGATION_POLICY_INSTALLED_NODES:")
                .contains("BASE:my-hr-profile,BASE:my-time,BASE:my-leave,"
                        + "BASE:my-pay-tax,BASE:my-goals-performance,BASE:my-hr-requests")
                .contains("DWP_HRIS_NAVIGATION_POLICY_DEPLOYED_NODES:"
                        + "BASE:my-employment-documents,"
                        + "BASE:hr-employment-assignments,"
                        + "BASE:hr-organization-positions}")
                .contains("v3-enabled: ${DWP_HRIS_NAVIGATION_POLICY_V3_ENABLED:false}")
                .contains("enabled: ${DWP_HRIS_NAVIGATION_AUTHORITY_ENABLED:false}")
                .doesNotContain(NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID)
                .doesNotContain("DWP_HRIS_NAVIGATION_POLICY_DEPLOYED_NODES:"
                        + "BASE:my-employment-documents,BASE:hr-people");
        ServerNavigationPolicyEligibilityProvider defaults =
                new ServerNavigationPolicyEligibilityProvider(
                        "BASE:my-employment-documents,BASE:my-hr-profile,BASE:my-time,"
                                + "BASE:my-leave,BASE:my-pay-tax,"
                                + "BASE:my-goals-performance,BASE:my-hr-requests,"
                                + "BASE:hr-people,"
                                + "BASE:hr-employment-assignments,"
                                + "BASE:hr-organization-positions,"
                                + "BASE:hr-employee-change-requests,BASE:time-clock-ingestion,"
                                + "BASE:time-interpretation,BASE:time-exceptions,"
                                + "BASE:performance-review-operations,"
                                + "MODERN:hcm.operations.compensation-planning,"
                                + "BASE:settings-reference,BASE:settings-payroll",
                        "BASE:my-employment-documents,BASE:hr-employment-assignments,"
                                + "BASE:hr-organization-positions");
        assertThat(defaults.installedNodeIds(42)).hasSize(18);
        assertThat(defaults.deployedNodeIds(42))
                .containsExactlyInAnyOrder(
                        "BASE:my-employment-documents",
                        "BASE:hr-employment-assignments",
                        "BASE:hr-organization-positions")
                .doesNotContain("BASE:hr-people");
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
