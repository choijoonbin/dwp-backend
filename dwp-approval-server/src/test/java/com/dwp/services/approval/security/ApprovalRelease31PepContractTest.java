package com.dwp.services.approval.security;

import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRelease31PepContractTest {
    private static final Set<String> ADDED_ROUTES = Set.of(
            "route.approvals.admin.audit-export-verifications.data",
            "route.approvals.admin.audit-export-verify.action",
            "route.approvals.admin.deployment-canary.data",
            "route.approvals.admin.deployment-canary-control.action",
            "route.approvals.admin.deployment-canary-telemetry.action",
            "route.approvals.admin.deployment-ledger.data",
            "route.approvals.admin.form-studio-field-update.action",
            "route.approvals.admin.form-studio-version-diff.data",
            "route.approvals.admin.incident-dead-letters.data",
            "route.approvals.admin.incident-report-create.action",
            "route.approvals.admin.incident-report.data",
            "route.approvals.admin.policy-delegation-audit.data",
            "route.approvals.admin.policy-delegation-kill-switch.action",
            "route.approvals.admin.policy-delegation-update.action",
            "route.approvals.admin.policy-governance-publish.action",
            "route.approvals.admin.policy-governance.data",
            "route.approvals.admin.policy-simulation.action",
            "route.approvals.admin.template-package-import.action",
            "route.approvals.admin.template-version-preview.data",
            "route.approvals.admin.workflow-studio-retire.action",
            "route.approvals.admin.workflow-studio-update.action",
            "route.approvals.admin.workflow-studio.data",
            "route.approvals.work.workflow-template.data");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void release31IsTheExactAppendOnlyPublicApprovalClosure() throws Exception {
        JsonNode previous = resource(ApprovalPilotPepRegistry.V19_RESOURCE);
        JsonNode current = resource(ApprovalPilotPepRegistry.V31_RESOURCE);
        Set<String> previousKeys = routeKeys(previous);
        Set<String> added = routeKeys(current);
        added.removeAll(previousKeys);

        assertThat(added).isEqualTo(ADDED_ROUTES);
        assertThat(current.path("registryRef").path("version").asInt()).isEqualTo(31);
        assertThat(current.path("registryRef").path("sha256").asText()).isEqualTo(
                "be4e1b6db3d3f0b5100182a3c80066a39c64479f9ba88d908fee661efd3335b8");
        assertThat(current.path("projectedRouteContractCount").asInt()).isEqualTo(216);
        assertThat(current.path("bindingPairCount").asInt()).isEqualTo(285);
        assertThat(current.path("projectionChecksum").asText()).isEqualTo(
                "db4ae3868edc04a540d134311f593621b9ce740d68d8e705d5beafb4e96bff2c");

        ApprovalPilotPepRegistry active = new ApprovalPilotPepRegistry(json);
        ApprovalPilotPepRegistry prior = new ApprovalPilotPepRegistry(
                json, Clock.systemUTC(), 19);
        assertThat(active.bindingContracts()).hasSize(285)
                .containsAll(prior.bindingContracts());
    }

    @Test
    void newReadAndHighRiskBindingsRequireTheirExactScopedCapabilities() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(json);
        String readRoute = "route.approvals.admin.deployment-canary.data";
        String readPath = "/v1/admin/operations/deployments/promotions/"
                + "d2ead147-86ce-44de-bdc4-25f1c392b183/canary";
        assertThat(authorize(registry, "GET", readPath, readRoute,
                "approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW")
                .allowed()).isTrue();

        String actionRoute = "route.approvals.admin.deployment-canary-control.action";
        String actionPath = readPath + "/control";
        var allowed = authorize(registry, "POST", actionPath, actionRoute,
                "approvals.operations.execute", "ADMIN.APPROVAL_OPERATIONS:EXECUTE");
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.authorities()).singleElement().satisfies(authority -> {
            assertThat(authority.highRisk()).isTrue();
            assertThat(authority.activationPolicy()).isEqualTo("STEPUP-MGMT-HIGH-V1");
            assertThat(authority.predicatePolicyKeys())
                    .containsExactly("predicate.approval.object-version.v1");
        });
        assertThat(authorize(registry, "POST", actionPath, actionRoute,
                "approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW")
                .allowed()).isFalse();
    }

    private ApprovalPilotPepRegistry.Decision authorize(
            ApprovalPilotPepRegistry registry,
            String method,
            String path,
            String route,
            String capability,
            String permission) {
        return registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                method,
                path,
                Set.of(permission),
                "APP_CONFIG_ADMIN@RS_APPROVALS," +
                        ScopedAuthorityToken.wireToken(capability, permission, "RS_APPROVALS"),
                Set.of(),
                route,
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
    }

    private JsonNode resource(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return json.readTree(input);
        }
    }

    private Set<String> routeKeys(JsonNode projection) {
        Set<String> result = new HashSet<>();
        projection.path("routes").forEach(route ->
                result.add(route.path("routeContractKey").asText()));
        return result;
    }
}
