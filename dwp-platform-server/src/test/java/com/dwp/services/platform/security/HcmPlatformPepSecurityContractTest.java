package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HcmPlatformPepSecurityContractTest {

    private static final String DECISION_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String OTHER_DECISION_REVISION =
            "psr-" + "fedcba9876543210".repeat(4);
    private static final String SUBJECT =
            "10000000-0000-4000-8000-000000000012";
    private static final String OTHER_SUBJECT =
            "10000000-0000-4000-8000-000000000013";
    private static final String EXPLORER_CONTEXT =
            "psc-" + "0123456789abcdef".repeat(4);
    private static final String OTHER_EXPLORER_CONTEXT =
            "psc-" + "fedcba9876543210".repeat(4);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HcmPlatformPepRegistry registry =
            new HcmPlatformPepRegistry(objectMapper);
    private final PlatformSecurityFilter filter = new PlatformSecurityFilter(
            "trusted", "runtime", false, objectMapper,
            new PlatformCanaryPepRegistry(objectMapper),
            new PlatformApprovalsPepRegistry(objectMapper),
            registry);

    @Test
    void loadsOnlyTheFortyThreeExactSysCarrierBindings() {
        assertThat(registry.bindingContracts()).hasSize(43)
                .extracting(HcmPlatformPepRegistry.BindingContract::routeContractKey)
                .contains(
                        "route.hcm.configuration.settings-reference.page",
                        "route.hcm.configuration.configurations.data",
                        "route.hcm.configuration.configuration-version-publish.action",
                        "route.hcm.configuration.navigation-policy-workspace.page",
                        "route.hcm.configuration.navigation-policy-revision-publish.action",
                        "route.hcm.operations.navigation-projection.data",
                        "route.hcm.audit.evidence.page",
                        "route.hcm.audit.evidence-detail.data",
                        "route.hcm.utility.explorer.page",
                        "route.hcm.utility.explorer-preferences-update.action",
                        "route.hcm.utility.explorer-recent-create.action",
                        "route.hcm.utility.explorer-command-receipt.data",
                        "route.hcm.management.controlled-transfer.data",
                        "route.hcm.management.controlled-transfer-create.action",
                        "route.hcm.shell.navigation-aggregate.data",
                        "route.hcm.shell.navigation-deep-link-decision.data",
                        "route.hcm.shell.navigation-aggregate-legal-entity.data",
                        "route.hcm.shell.navigation-deep-link-decision-legal-entity.data",
                        "route.hcm.shell.navigation-aggregate-v3.data",
                        "route.hcm.shell.navigation-deep-link-decision-v3.data",
                        "route.hcm.configuration.navigation-policy-v2-workspace.data",
                        "route.hcm.configuration.navigation-policy-v2-revision-publish.action")
                .doesNotContain(
                        "route.hcm.shell.navigation-aggregate-v3-legal-entity.data",
                        "route.hcm.shell.navigation-deep-link-decision-v3-legal-entity.data");
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/configurations/c1/versions/2/publish")).isTrue();
        assertThat(registry.governsPathFamily("/v1/admin/users")).isFalse();
        assertThat(registry.governsPathFamily("/v1/admin/hris/other")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v1/hris/workbench/settings-reference/extra")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/configurations-admin")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/configurations%2fc1")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/navigation-policies/workspace")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/hris/navigation/projection")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/audit-control/events")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/audit-control/events/10000000-0000-4000-8000-000000000001"))
                .isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/audit-control/cases")).isFalse();
        assertThat(registry.governsPathFamily("/v1/hris/explorer")).isTrue();
        assertThat(registry.governsPathFamily("/v1/hris/explorer/preferences")).isTrue();
        assertThat(registry.governsPathFamily("/v1/hris/explorer/other")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/command-receipts/10000000-0000-4000-8000-000000000043"))
                .isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/file-transfers")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/file-transfers/nearby")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v2/hris/navigation/projection")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v2/hris/navigation/deep-link-decision")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v3/hris/navigation/projection")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v3/hris/navigation/deep-link-decision")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v3/hris/navigation/nearby")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v2/hris/navigation/nearby")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v2/admin/hris/navigation-policies/workspace")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v2/admin/hris/navigation-policies-admin")).isFalse();
    }

    @Test
    void navigationV2AndV3RuntimeRequireExactShellCapabilityEntitlementAndDuty()
            throws Exception {
        for (String route : Set.of(
                "route.hcm.shell.navigation-aggregate.data",
                "route.hcm.shell.navigation-deep-link-decision.data",
                "route.hcm.shell.navigation-aggregate-v3.data",
                "route.hcm.shell.navigation-deep-link-decision-v3.data")) {
            String version = route.contains("-v3.") ? "/v3" : "/v2";
            String path = version + (route.contains("navigation-aggregate")
                    ? "/hris/navigation/projection"
                    : "/hris/navigation/deep-link-decision");
            HcmPlatformPepRegistry.RequestEvidence allowed = navigationShellRequest(
                    path, route,
                    Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                    Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"));
            assertThat(registry.authorize(allowed).allowed()).as(route).isTrue();
            assertThat(registry.authorize(navigationShellRequest(
                    path, route, Set.of("DATA.HRIS_APP_SHELL:VIEW"),
                    Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"))).allowed())
                    .as(route + " missing product entitlement").isFalse();
            assertThat(registry.authorize(navigationShellRequest(
                    path, route, Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                    Set.of())).allowed()).as(route + " missing duty").isFalse();
            assertThat(registry.authorize(navigationShellRequest(
                    path, "route.hcm.operations.navigation-projection.data",
                    Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                    Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"))).allowed())
                    .as(route + " wrong trusted route").isFalse();
            assertThat(invokeNavigationShell(path, route, true, true).getStatus())
                    .as(route + " through service PEP").isEqualTo(200);
            assertThat(invokeNavigationShell(path, route, false, true).getStatus())
                    .as(route + " without APP.HCM").isEqualTo(403);
            assertThat(invokeNavigationShell(path, route, true, false).getStatus())
                    .as(route + " without shell duty").isEqualTo(403);
        }
    }

    @Test
    void navigationV2LegalEntityRuntimeRequiresExactOperationsTargetPopulation()
            throws Exception {
        for (String route : Set.of(
                "route.hcm.shell.navigation-aggregate-legal-entity.data",
                "route.hcm.shell.navigation-deep-link-decision-legal-entity.data")) {
            String path = route.contains("aggregate")
                    ? "/v2/hris/navigation/projection"
                    : "/v2/hris/navigation/deep-link-decision";
            Set<String> permissions = Set.of(
                    "APP.HCM:VIEW", "DATA.WORKFORCE:VIEW");
            assertThat(registry.authorize(navigationRuntimeRequest(
                    path, route, permissions, Set.of(),
                    "TARGET_POPULATION")).allowed()).as(route).isTrue();
            assertThat(registry.authorize(navigationRuntimeRequest(
                    path, route, permissions, Set.of(), "SELF")).allowed())
                    .as(route + " tenant scope cannot select legal profile").isFalse();
            assertThat(registry.authorize(navigationRuntimeRequest(
                    path, route, permissions, Set.of(), "RESOURCE_SET")).allowed())
                    .as(route + " management scope cannot select legal profile").isFalse();
            assertThat(registry.authorize(navigationRuntimeRequest(
                    path, route, Set.of("DATA.WORKFORCE:VIEW"), Set.of(),
                    "TARGET_POPULATION")).allowed())
                    .as(route + " missing product entitlement").isFalse();
            assertThat(registry.authorize(navigationRuntimeRequest(
                    path, route, Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                    Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                    "TARGET_POPULATION")).allowed())
                    .as(route + " shell-only authority cannot replace operations").isFalse();
            assertThat(invokeNavigationRuntime(
                    path, route, "TARGET_POPULATION", true).getStatus())
                    .as(route + " through service PEP").isEqualTo(200);
            assertThat(invokeNavigationRuntime(
                    path, route, "SELF", true).getStatus())
                    .as(route + " mismatched trusted population").isEqualTo(403);
            assertThat(invokeNavigationRuntime(
                    path, route, "TARGET_POPULATION", false).getStatus())
                    .as(route + " missing operations authority").isEqualTo(403);
        }
    }

    @Test
    void navigationV2AdminSeparatesViewUpdateAndStepUpPublish() throws Exception {
        assertThat(invoke(
                "GET", "/v2/admin/hris/navigation-policies/workspace",
                "route.hcm.configuration.navigation-policy-v2-workspace.data",
                "CONFIG.HRIS_NAVIGATION_POLICY:VIEW", false, false).getStatus())
                .isEqualTo(200);
        assertThat(invoke(
                "GET", "/v2/admin/hris/navigation-policies/workspace",
                "route.hcm.configuration.navigation-policy-v2-workspace.data",
                "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE", false, false).getStatus())
                .isEqualTo(403);
        assertThat(invoke(
                "POST", "/v2/admin/hris/navigation-policies/drafts",
                "route.hcm.configuration.navigation-policy-v2-draft-create.action",
                "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE", true, false).getStatus())
                .isEqualTo(200);

        String revision = "10000000-0000-4000-8000-000000000045";
        String path = "/v2/admin/hris/navigation-policies/revisions/"
                + revision + "/publish";
        String route =
                "route.hcm.configuration.navigation-policy-v2-revision-publish.action";
        assertThat(invoke(
                "POST", path, route,
                "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE", true, true).getStatus())
                .isEqualTo(403);
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH", true, false).getStatus())
                .isEqualTo(403);
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH", true, true).getStatus())
                .isEqualTo(200);
    }

    @Test
    void controlledTransferRegistryRequiresExactPackageDutyPurposeAndCapability() {
        HcmPlatformPepRegistry.Decision read = registry.authorize(transferRequest(
                "GET", Set.of("APP.HCM:VIEW", "DATA.HRIS_CONTROLLED_TRANSFER:VIEW"),
                Set.of("HRIS_INTEGRATION_AUTHOR"),
                Set.of("HRIS_DUTY_CONTROLLED_TRANSFER_READER@RS_HCM_CONFIG"),
                "HRIS_INTEGRATION_ADMIN",
                "route.hcm.management.controlled-transfer.data"));
        assertThat(read.allowed()).isTrue();
        assertThat(read.authority().capabilityContractKey())
                .isEqualTo("hcm.integration.transfer.view");
        assertThat(read.authority().highRisk()).isFalse();

        HcmPlatformPepRegistry.Decision create = registry.authorize(transferRequest(
                "POST", Set.of("APP.HCM:VIEW", "ACTION.HRIS_CONTROLLED_TRANSFER:CREATE"),
                Set.of("HRIS_INTEGRATION_EXECUTOR"),
                Set.of("HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR@RS_HCM_CONFIG"),
                "HRIS_INTEGRATION_ADMIN",
                "route.hcm.management.controlled-transfer-create.action"));
        assertThat(create.allowed()).isTrue();
        assertThat(create.authority().capabilityContractKey())
                .isEqualTo("hcm.integration.transfer.create");
        assertThat(create.authority().highRisk()).isTrue();
        assertThat(create.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(create.authority().stepUpCommandBinding())
                .satisfies(binding -> {
                    assertThat(binding.targetType())
                            .isEqualTo("CONTROLLED_TRANSFER_OBJECT");
                    assertThat(binding.targetIdPathParameter()).isNull();
                    assertThat(binding.targetIdBodyFields())
                            .containsExactly("objectRef", "objectVersion");
                    assertThat(binding.expectedObjectVersionSource())
                            .isEqualTo("COMMAND_BODY");
                });

        for (HcmPlatformPepRegistry.RequestEvidence denied : Set.of(
                transferRequest("GET", Set.of("DATA.HRIS_CONTROLLED_TRANSFER:VIEW"),
                        Set.of("HRIS_INTEGRATION_AUTHOR"),
                        Set.of("HRIS_DUTY_CONTROLLED_TRANSFER_READER@RS_HCM_CONFIG"),
                        "HRIS_INTEGRATION_ADMIN",
                        "route.hcm.management.controlled-transfer.data"),
                transferRequest("GET", Set.of("APP.HCM:VIEW", "DATA.HRIS_CONTROLLED_TRANSFER:VIEW"),
                        Set.of("TENANT_ADMIN"),
                        Set.of("HRIS_DUTY_CONTROLLED_TRANSFER_READER@RS_HCM_CONFIG"),
                        "HRIS_INTEGRATION_ADMIN",
                        "route.hcm.management.controlled-transfer.data"),
                transferRequest("GET", Set.of("APP.HCM:VIEW", "DATA.HRIS_CONTROLLED_TRANSFER:VIEW"),
                        Set.of("HRIS_INTEGRATION_AUTHOR"), Set.of(),
                        "HRIS_INTEGRATION_ADMIN",
                        "route.hcm.management.controlled-transfer.data"),
                transferRequest("GET", Set.of("APP.HCM:VIEW", "DATA.HRIS_CONTROLLED_TRANSFER:VIEW"),
                        Set.of("HRIS_INTEGRATION_AUTHOR"),
                        Set.of("HRIS_DUTY_CONTROLLED_TRANSFER_READER@RS_HCM_CONFIG"),
                        "ARBITRARY_PURPOSE",
                        "route.hcm.management.controlled-transfer.data"),
                transferRequest("POST", Set.of("APP.HCM:VIEW", "ACTION.HRIS_CONTROLLED_TRANSFER:CREATE"),
                        Set.of("HRIS_INTEGRATION_AUTHOR"),
                        Set.of("HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR@RS_HCM_CONFIG"),
                        "HRIS_INTEGRATION_ADMIN",
                        "route.hcm.management.controlled-transfer-create.action"))) {
            assertThat(registry.authorize(denied).allowed()).isFalse();
        }
    }

    @Test
    void controlledTransferFilterRequiresElevatedCurrentCreateAuthority() throws Exception {
        assertThat(invokeControlledTransfer("GET", true, true, true, true)
                .getStatus()).isEqualTo(200);
        assertThat(invokeControlledTransfer("POST", true, true, true, true)
                .getStatus()).isEqualTo(200);
        assertThat(invokeControlledTransfer("POST", true, true, true, false)
                .getStatus()).isEqualTo(403);
        assertThat(invokeControlledTransfer("POST", true, true, false, true)
                .getStatus()).isEqualTo(403);
        assertThat(invokeControlledTransfer("GET", false, true, true, false)
                .getStatus()).isEqualTo(403);
        assertThat(invokeControlledTransfer("GET", true, false, true, false)
                .getStatus()).isEqualTo(403);
    }

    @Test
    void explorerCarrierMatchesOnlyTheSealedCapabilityAndRouteIdentity() {
        HcmPlatformPepRegistry.Decision allowed = registry.authorize(
                explorerRequest(
                        "GET", "/v1/hris/explorer",
                        Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                        Set.of("HRIS_EMPLOYEE"),
                        Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                        "route.hcm.utility.explorer.page", ownerEvidence()));
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.authority().capabilityContractKey())
                .isEqualTo("hcm.app.shell.view");
        assertThat(allowed.authority().highRisk()).isFalse();

        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer-recent-create.action",
                ownerEvidence())).allowed()).isFalse();
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page", ownerEvidence())).allowed()).isFalse();
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"), Set.of(),
                "route.hcm.utility.explorer.page", ownerEvidence())).allowed()).isFalse();
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("TENANT_ADMIN"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page", ownerEvidence())).allowed()).isFalse();
    }

    @Test
    void explorerCarrierRequiresCurrentRevisionsAndAnAllowedDescendantPage() {
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page",
                ownerEvidence(OTHER_DECISION_REVISION, 17, 29,
                        Set.of("route.hcm.personal.me.page"), null))).allowed())
                .isFalse();
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page",
                ownerEvidence(DECISION_REVISION, 0, 29,
                        Set.of("route.hcm.personal.me.page"), null))).allowed())
                .isFalse();
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page",
                ownerEvidence(DECISION_REVISION, 17, 0,
                        Set.of("route.hcm.personal.me.page"), null))).allowed())
                .isFalse();
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page",
                ownerEvidence(DECISION_REVISION, 17, 29, Set.of(), null)))
                .allowed()).isFalse();
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page",
                ownerEvidence(DECISION_REVISION, 17, 29,
                        Set.of("route.hcm.utility.explorer.page"), null))).allowed())
                .isFalse();
    }

    @Test
    void explorerAuthorityContextRequiresOpaqueLowercaseAndTrustedEquality() {
        for (String deniedContext : new String[]{
                "hcm.utility",
                "psc-" + "A".repeat(64),
                "psc-" + "a".repeat(63),
                null,
                OTHER_EXPLORER_CONTEXT}) {
            assertThat(registry.authorize(explorerRequest(
                    "GET", "/v1/hris/explorer",
                    Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                    Set.of("HRIS_EMPLOYEE"),
                    Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                    "route.hcm.utility.explorer.page", ownerEvidence(),
                    deniedContext)).allowed()).isFalse();
        }
        assertThat(registry.authorize(explorerRequest(
                "GET", "/v1/hris/explorer",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"),
                "route.hcm.utility.explorer.page",
                ownerEvidence(OTHER_EXPLORER_CONTEXT),
                EXPLORER_CONTEXT)).allowed()).isFalse();
    }

    @Test
    void explorerReceiptRejectsSubjectOriginAndRequestContextMismatch() {
        String path = "/v1/admin/hris/command-receipts/"
                + "10000000-0000-4000-8000-000000000043";
        String route = "route.hcm.utility.explorer-command-receipt.data";
        HcmPlatformPepRegistry.ExplorerReceiptEvidence subjectMismatch =
                new HcmPlatformPepRegistry.ExplorerReceiptEvidence(
                        OTHER_SUBJECT, "EXPLORER_RECENT_RECORD",
                        EXPLORER_CONTEXT, "tenant-7");
        HcmPlatformPepRegistry.ExplorerReceiptEvidence invalidOrigin =
                new HcmPlatformPepRegistry.ExplorerReceiptEvidence(
                        SUBJECT, "CONFIGURATION_PUBLISH",
                        EXPLORER_CONTEXT, "tenant-7");
        HcmPlatformPepRegistry.ExplorerReceiptEvidence contextMismatch =
                new HcmPlatformPepRegistry.ExplorerReceiptEvidence(
                        SUBJECT, "EXPLORER_PREFERENCES_REPLACE",
                        OTHER_EXPLORER_CONTEXT, "tenant-7");
        HcmPlatformPepRegistry.ExplorerReceiptEvidence validReceipt =
                new HcmPlatformPepRegistry.ExplorerReceiptEvidence(
                        SUBJECT, "EXPLORER_RECENT_RECORD",
                        EXPLORER_CONTEXT, "tenant-7");

        HcmPlatformPepRegistry.Decision valid = registry.authorize(explorerRequest(
                "GET", path,
                Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                Set.of("HRIS_EMPLOYEE"),
                Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"), route,
                ownerEvidence(DECISION_REVISION, 17, 29,
                        Set.of("route.hcm.personal.me.page"), validReceipt)));
        assertThat(valid.allowed()).isTrue();
        assertThat(valid.authority().profileKey())
                .isEqualTo("explorer-originating-receipt-reader");

        for (HcmPlatformPepRegistry.ExplorerReceiptEvidence receipt : Set.of(
                subjectMismatch, invalidOrigin, contextMismatch)) {
            assertThat(registry.authorize(explorerRequest(
                    "GET", path,
                    Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"),
                    Set.of("HRIS_EMPLOYEE"),
                    Set.of("HRIS_DUTY_APP_SHELL_READER@SELF"), route,
                    ownerEvidence(DECISION_REVISION, 17, 29,
                            Set.of("route.hcm.personal.me.page"), receipt)))
                    .allowed()).isFalse();
        }

        assertThat(registry.authorize(explorerRequest(
                "GET", path,
                Set.of("DATA.HRIS_COMMAND_RECEIPT:VIEW"),
                Set.of("HRIS_CONFIG_AUTHOR"), Set.of(), route, null)).allowed())
                .isTrue();
    }

    @Test
    void explorerFilterAcceptsOnlyTypedCurrentOwnerEvidence() throws Exception {
        MockHttpServletRequest allowed = explorerHttpRequest();
        allowed.setAttribute(HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE,
                ownerEvidence());
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowed, allowedResponse, new MockFilterChain());
        assertThat(allowedResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest absent = explorerHttpRequest();
        MockHttpServletResponse absentResponse = new MockHttpServletResponse();
        filter.doFilter(absent, absentResponse, new MockFilterChain());
        assertThat(absentResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest invalidType = explorerHttpRequest();
        invalidType.setAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE,
                "client-asserted-owner-evidence");
        MockHttpServletResponse invalidTypeResponse = new MockHttpServletResponse();
        filter.doFilter(invalidType, invalidTypeResponse, new MockFilterChain());
        assertThat(invalidTypeResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest staleDecision = explorerHttpRequest();
        staleDecision.setAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE,
                ownerEvidence(OTHER_DECISION_REVISION, 17, 29,
                        Set.of("route.hcm.personal.me.page"), null));
        MockHttpServletResponse staleDecisionResponse = new MockHttpServletResponse();
        filter.doFilter(staleDecision, staleDecisionResponse, new MockFilterChain());
        assertThat(staleDecisionResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest staleAuthority = explorerHttpRequest();
        staleAuthority.removeHeader(
                PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER);
        staleAuthority.addHeader(
                PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                "2000-01-01T00:00:00Z");
        staleAuthority.setAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE,
                ownerEvidence());
        MockHttpServletResponse staleAuthorityResponse = new MockHttpServletResponse();
        filter.doFilter(staleAuthority, staleAuthorityResponse, new MockFilterChain());
        assertThat(staleAuthorityResponse.getStatus()).isEqualTo(503);

        MockHttpServletRequest zeroDescendant = explorerHttpRequest();
        zeroDescendant.setAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE,
                ownerEvidence(DECISION_REVISION, 17, 29, Set.of(), null));
        MockHttpServletResponse zeroDescendantResponse = new MockHttpServletResponse();
        filter.doFilter(zeroDescendant, zeroDescendantResponse, new MockFilterChain());
        assertThat(zeroDescendantResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void auditReadKeepsHighRiskActivationWithoutCommandBinding() {
        HcmPlatformPepRegistry.Decision decision = registry.authorize(
                new HcmPlatformPepRegistry.RequestEvidence(
                        "GET",
                        "/v1/admin/audit-control/events",
                        java.util.Set.of("ADMIN.AUDIT_VIEW:VIEW"),
                        "route.hcm.audit.evidence.page"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authority().routeKind()).isEqualTo("PAGE");
        assertThat(decision.authority().highRisk()).isTrue();
        assertThat(decision.authority().capabilityContractKey())
                .isEqualTo("hcm.audit.evidence.view");
        assertThat(decision.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(decision.authority().stepUpCommandBinding()).isNull();
    }

    @Test
    void sharedAuditPathKeepsGenericCompatibilityButFencesHrisAuditors() throws Exception {
        MockHttpServletRequest generic = base(
                "GET", "/v1/admin/audit-control/events");
        generic.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.AUDIT_VIEW:VIEW");
        MockHttpServletResponse genericResponse = new MockHttpServletResponse();
        filter.doFilter(generic, genericResponse, new MockFilterChain());
        assertThat(genericResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest auditor = base(
                "GET", "/v1/admin/audit-control/events");
        auditor.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.AUDIT_VIEW:VIEW");
        auditor.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_AUDITOR");
        MockHttpServletResponse auditorResponse = new MockHttpServletResponse();
        filter.doFilter(auditor, auditorResponse, new MockFilterChain());
        assertThat(auditorResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void exactHcmAuditReadRequiresTheWholeElevatedIntersection() throws Exception {
        MockHttpServletRequest allowed = hcmAuditRequest();
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowed, allowedResponse, new MockFilterChain());
        assertThat(allowedResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest productOnly = hcmAuditRequest();
        productOnly.removeHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER);
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(productOnly, deniedResponse, new MockFilterChain());
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void exactWorkbenchReadPassesWithoutGenericTenantAdminRole() throws Exception {
        MockHttpServletResponse response = invoke(
                "GET",
                "/v1/hris/workbench/settings-reference",
                "route.hcm.configuration.settings-reference.page",
                "CONFIG.HRIS_WORKBENCH:VIEW",
                false,
                false);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void genericRoleOrNearbyAdminPathCannotBypassExactRegistry() throws Exception {
        MockHttpServletResponse missing = invoke(
                "GET",
                "/v1/admin/hris/configurations",
                "route.hcm.configuration.configurations.data",
                "APP.HCM:VIEW",
                false,
                false);
        assertThat(missing.getStatus()).isEqualTo(403);

        MockHttpServletRequest broadAdmin = base(
                "GET", "/v1/admin/hris/configurations");
        broadAdmin.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN");
        broadAdmin.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.HCM:VIEW");
        trustedEvidence(broadAdmin,
                "route.hcm.configuration.configurations.data", false);
        MockHttpServletResponse broadAdminResponse = new MockHttpServletResponse();
        filter.doFilter(broadAdmin, broadAdminResponse, new MockFilterChain());
        assertThat(broadAdminResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest nearby = base(
                "GET", "/v1/admin/hris/configuration-secrets");
        nearby.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_CONFIG_AUTHOR");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(nearby, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void browserHeadersCannotReplaceTheTrustedGatewayServiceToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/hris/workbench/settings-reference");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "101");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "CONFIG.HRIS_WORKBENCH:VIEW");
        trustedEvidence(request,
                "route.hcm.configuration.settings-reference.page", false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void publishRequiresExactCapabilityRevisionAndElevatedMode() throws Exception {
        String path = "/v1/admin/hris/configurations/c1/versions/2/publish";
        String route = "route.hcm.configuration.configuration-version-publish.action";
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_POLICY_PUBLISH:PUBLISH", true, false).getStatus())
                .isEqualTo(403);
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_POLICY_PUBLISH:PUBLISH", true, true).getStatus())
                .isEqualTo(200);
    }

    @Test
    void navigationProjectionUsesHrOperationsAuthorityNotConfigurationAdmin() throws Exception {
        String path = "/v1/hris/navigation/projection";
        String route = "route.hcm.operations.navigation-projection.data";
        assertThat(invoke(
                "GET", path, route,
                "DATA.WORKFORCE:VIEW", false, false).getStatus()).isEqualTo(200);
        assertThat(invoke(
                "GET", path, route,
                "CONFIG.HRIS_NAVIGATION_POLICY:VIEW", false, false).getStatus())
                .isEqualTo(403);
    }

    @Test
    void navigationPublishExposesExactStepUpOwnerContract() throws Exception {
        String revisionId = "10000000-0000-4000-8000-000000000040";
        String path = "/v1/admin/hris/navigation-policies/revisions/"
                + revisionId + "/publish";
        String route =
                "route.hcm.configuration.navigation-policy-revision-publish.action";

        HcmPlatformPepRegistry.Decision decision = registry.authorize(
                new HcmPlatformPepRegistry.RequestEvidence(
                        "POST", path, java.util.Set.of(
                        "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH"), route));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authority().highRisk()).isTrue();
        assertThat(decision.authority().capabilityContractKey())
                .isEqualTo("hcm.configuration.navigation-policy.publish");
        assertThat(decision.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(decision.authority().stepUpCommandBinding())
                .satisfies(binding -> {
                    assertThat(binding.targetType())
                            .isEqualTo("HRIS_NAVIGATION_POLICY_REVISION");
                    assertThat(binding.targetIdPathParameter()).isEqualTo("revisionId");
                    assertThat(binding.expectedObjectVersionSource()).isEqualTo("COMMAND_BODY");
                    assertThat(binding.expectedObjectVersionName()).isEqualTo("expectedVersion");
                    assertThat(binding.ownerServiceKey()).isEqualTo("platform");
                    assertThat(binding.audience()).isEqualTo("dwp-platform-server");
                });
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH", true, false).getStatus())
                .isEqualTo(403);
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH", true, true).getStatus())
                .isEqualTo(200);
    }

    @Test
    void encodedSeparatorAndWrongTrustedRouteFailClosed() throws Exception {
        assertThat(invoke(
                "GET",
                "/v1/admin/hris/configurations%2fc1",
                "route.hcm.configuration.configurations.data",
                "CONFIG.HRIS_POLICY:UPDATE",
                false,
                false).getStatus()).isEqualTo(403);
        assertThat(invoke(
                "GET",
                "/v1/admin/hris/configurations",
                "route.hcm.configuration.settings-reference.page",
                "CONFIG.HRIS_POLICY:UPDATE",
                false,
                false).getStatus()).isEqualTo(403);
    }

    private HcmPlatformPepRegistry.RequestEvidence explorerRequest(
            String method,
            String path,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String route,
            HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence) {
        return explorerRequest(method, path, permissions, roles, resourceRoles,
                route, ownerEvidence, EXPLORER_CONTEXT);
    }

    private HcmPlatformPepRegistry.RequestEvidence explorerRequest(
            String method,
            String path,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String route,
            HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence,
            String requestContext) {
        return new HcmPlatformPepRegistry.RequestEvidence(
                method, path, permissions, roles, resourceRoles,
                DECISION_REVISION, requestContext, "tenant-7",
                ownerEvidence, route);
    }

    private HcmPlatformPepRegistry.RequestEvidence navigationShellRequest(
            String path,
            String route,
            Set<String> permissions,
            Set<String> resourceRoles) {
        return navigationRuntimeRequest(
                path, route, permissions, resourceRoles, "SELF");
    }

    private HcmPlatformPepRegistry.RequestEvidence navigationRuntimeRequest(
            String path,
            String route,
            Set<String> permissions,
            Set<String> resourceRoles,
            String populationKind) {
        return new HcmPlatformPepRegistry.RequestEvidence(
                "GET", path, permissions, Set.of(), resourceRoles,
                DECISION_REVISION, "psc-" + "1".repeat(64), "self",
                populationKind, null, null, route);
    }

    private HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence() {
        return ownerEvidence(
                DECISION_REVISION, 17, 29,
                Set.of("route.hcm.personal.me.page"), null);
    }

    private HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence(
            String contextKey) {
        return ownerEvidence(
                DECISION_REVISION, contextKey, 17, 29,
                Set.of("route.hcm.personal.me.page"), null);
    }

    private HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence(
            String decisionRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            Set<String> allowedDescendantPageRouteKeys,
            HcmPlatformPepRegistry.ExplorerReceiptEvidence receiptEvidence) {
        return ownerEvidence(decisionRevision, EXPLORER_CONTEXT,
                fieldPolicyRevision, authorizationRevision,
                allowedDescendantPageRouteKeys, receiptEvidence);
    }

    private HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence(
            String decisionRevision,
            String contextKey,
            long fieldPolicyRevision,
            long authorizationRevision,
            Set<String> allowedDescendantPageRouteKeys,
            HcmPlatformPepRegistry.ExplorerReceiptEvidence receiptEvidence) {
        return new HcmPlatformPepRegistry.ExplorerOwnerEvidence(
                decisionRevision, contextKey, "tenant-7",
                "HRIS_APP_DISCOVERY", SUBJECT,
                fieldPolicyRevision, authorizationRevision,
                allowedDescendantPageRouteKeys, receiptEvidence);
    }

    private MockHttpServletRequest explorerHttpRequest() {
        MockHttpServletRequest request = base("GET", "/v1/hris/explorer");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_EMPLOYEE");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW,DATA.HRIS_APP_SHELL:VIEW");
        request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "HRIS_DUTY_APP_SHELL_READER@SELF");
        trustedEvidence(request, "route.hcm.utility.explorer.page", false);
        request.removeHeader(PlatformSecurityFilter.CONTEXT_HEADER);
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, EXPLORER_CONTEXT);
        return request;
    }

    private HcmPlatformPepRegistry.RequestEvidence transferRequest(
            String method,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String purpose,
            String route) {
        return new HcmPlatformPepRegistry.RequestEvidence(
                method, "/v1/admin/hris/file-transfers", permissions,
                roles, resourceRoles, DECISION_REVISION,
                "psc-" + "1".repeat(64), "tenant-7", purpose,
                null, route);
    }

    private MockHttpServletResponse invokeControlledTransfer(
            String method,
            boolean appEntitlement,
            boolean scopedDuty,
            boolean purpose,
            boolean elevated) throws Exception {
        boolean create = "POST".equals(method);
        MockHttpServletRequest request = base(
                method, "/v1/admin/hris/file-transfers");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER,
                create ? "HRIS_INTEGRATION_EXECUTOR" : "HRIS_INTEGRATION_AUTHOR");
        String capability = create
                ? "ACTION.HRIS_CONTROLLED_TRANSFER:CREATE"
                : "DATA.HRIS_CONTROLLED_TRANSFER:VIEW";
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                appEntitlement ? "APP.HCM:VIEW," + capability : capability);
        if (scopedDuty) {
            request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                    (create ? "HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR"
                            : "HRIS_DUTY_CONTROLLED_TRANSFER_READER")
                            + "@RS_HCM_CONFIG");
        }
        if (purpose) request.addHeader(
                PlatformSecurityHeaders.PURPOSE, "HRIS_INTEGRATION_ADMIN");
        if (elevated) request.addHeader(
                "X-DWP-Active-Access-Mode", "ELEVATED");
        trustedEvidence(request,
                create
                        ? "route.hcm.management.controlled-transfer-create.action"
                        : "route.hcm.management.controlled-transfer.data",
                create);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletResponse invokeNavigationShell(
            String path,
            String route,
            boolean appEntitlement,
            boolean shellDuty) throws Exception {
        MockHttpServletRequest request = base("GET", path);
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                (appEntitlement ? "APP.HCM:VIEW," : "")
                        + "DATA.HRIS_APP_SHELL:VIEW");
        if (shellDuty) {
            request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                    "HRIS_DUTY_APP_SHELL_READER@SELF");
        }
        request.addHeader(PlatformSecurityHeaders.POPULATION_KIND, "SELF");
        trustedEvidence(request, route, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletResponse invokeNavigationRuntime(
            String path,
            String route,
            String populationKind,
            boolean operationsAuthority) throws Exception {
        MockHttpServletRequest request = base("GET", path);
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW"
                        + (operationsAuthority ? ",DATA.WORKFORCE:VIEW" : ""));
        request.addHeader(PlatformSecurityHeaders.POPULATION_KIND, populationKind);
        trustedEvidence(request, route, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletResponse invoke(
            String method,
            String path,
            String route,
            String permissions,
            boolean stateChanging,
            boolean elevated) throws Exception {
        MockHttpServletRequest request = base(method, path);
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_CONFIG_AUTHOR");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, permissions);
        trustedEvidence(request, route, stateChanging);
        if (elevated) request.addHeader("X-DWP-Active-Access-Mode", "ELEVATED");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest base(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "101");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        return request;
    }

    private MockHttpServletRequest hcmAuditRequest() {
        MockHttpServletRequest request = base(
                "GET", "/v1/admin/audit-control/events");
        request.setParameter("product", "HCM");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_AUDITOR");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW,ADMIN.AUDIT_VIEW:VIEW");
        request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "HRIS_DUTY_AUDITOR@RS_HCM_CONFIG");
        request.addHeader("X-DWP-Active-Access-Mode", "ELEVATED");
        request.addHeader("X-Purpose-Code", "HRIS_AUDIT_EVIDENCE");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                "10000000-0000-4000-8000-000000000001");
        request.addHeader("X-DWP-Field-Policy-Revision", "17");
        request.addHeader("X-DWP-Authorization-Revision", "29");
        trustedEvidence(request, "route.hcm.audit.evidence.page", false);
        request.removeHeader(PlatformSecurityFilter.CONTEXT_HEADER);
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "hcm.management");
        return request;
    }

    private void trustedEvidence(
            MockHttpServletRequest request,
            String route,
            boolean stateChanging) {
        request.addHeader(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, "100");
        request.addHeader(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
        request.addHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER, route);
        request.addHeader(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                DECISION_REVISION);
        request.addHeader(PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                "2030-01-01T00:00:00Z");
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "hcm.configuration");
        request.addHeader(PlatformSecurityFilter.SCOPE_HEADER, "tenant-7");
        if (stateChanging) {
            request.addHeader(
                    PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    DECISION_REVISION);
        }
    }
}
