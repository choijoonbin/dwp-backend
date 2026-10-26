package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAuthorizationV60ContractTest {
    private static final String CHECKSUM =
            "405ac7c88d30a5730faa40c730968fffc873bd1a3679e4997532f0ea9ff1c47c";
    private static final Set<String> ROUTES = Set.of(
            "route.hcm.configuration.settings-integrations.page",
            "route.hcm.configuration.dwp-integrations.data",
            "route.hcm.configuration.connector-catalog.data",
            "route.hcm.configuration.connector-definition-create.action",
            "route.hcm.configuration.connector-test.action",
            "route.hcm.configuration.connector-mapping-dry-run.action",
            "route.hcm.configuration.connector-execution-create.action",
            "route.hcm.configuration.connector-execution-detail.data",
            "route.hcm.configuration.connector-definition-validate.action",
            "route.hcm.configuration.connector-definition-submit.action",
            "route.hcm.configuration.connector-definition-approve.action",
            "route.hcm.configuration.connector-definition-activate.action",
            "route.hcm.configuration.connector-mapping-version-create.action",
            "route.hcm.configuration.connector-mapping-version-validate.action",
            "route.hcm.configuration.connector-mapping-version-submit.action",
            "route.hcm.configuration.connector-mapping-version-approve.action",
            "route.hcm.configuration.connector-mapping-version-activate.action",
            "route.hcm.configuration.connector-execution-reconcile.action",
            "route.hcm.configuration.connector-execution-retry.action",
            "route.hcm.configuration.connector-execution-cancel.action");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(objectMapper);

    @Test
    void sealsTheExactSys008CapabilityRouteAndCollisionDelta() throws Exception {
        var previous = load("product-surfaces-v1.bundle-v59.generated.json");
        var contract = load("product-surfaces-v1.bundle-v60.generated.json");

        assertThat(contract.version()).isEqualTo(60);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(CHECKSUM);
        assertThat(contract.capabilities()).hasSize(278);
        assertThat(contract.accessPolicies()).hasSize(24);
        assertThat(contract.entitlementExpressions()).hasSize(17);
        assertThat(contract.predicatePolicies()).hasSize(86);
        assertThat(contract.routes()).hasSize(1154);

        Map<String, ProductAuthorizationContractDtos.CapabilityContract> before =
                indexCapabilities(previous);
        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities =
                indexCapabilities(contract);
        assertCapability(capabilities.get("hcm.integration.definition.update"),
                "CONFIG.HRIS_INTEGRATION:UPDATE", "CONFIG.HRIS_INTEGRATION",
                "UPDATE", "HIGH", null, "HRIS_DUTY_INTEGRATION_AUTHOR", 9);
        assertCapability(capabilities.get("hcm.integration.control-plane.execute"),
                "ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE",
                "ACTION.HRIS_INTEGRATION_CONTROL_PLANE", "EXECUTE", "CRITICAL",
                "STEPUP-MGMT-HIGH-V1",
                "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR", 9);
        assertThat(capabilities.get("hcm.integration.execute"))
                .isEqualTo(before.get("hcm.integration.execute"));
        assertThat(capabilities.get("hcm.integration.workforce-ingestion.execute"))
                .isEqualTo(before.get("hcm.integration.workforce-ingestion.execute"));
        assertThat(capabilities.get("hcm.integration.execute").routeContractKeys())
                .doesNotContainAnyElementsOf(ROUTES);
        assertThat(capabilities.get("hcm.integration.workforce-ingestion.execute")
                .routeContractKeys()).doesNotContainAnyElementsOf(ROUTES);

        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        assertThat(routes.keySet()).containsAll(ROUTES);
        assertThat(ROUTES).filteredOn(key -> "PAGE".equals(routes.get(key).routeKind()))
                .hasSize(1);
        assertThat(ROUTES).filteredOn(key -> "DATA".equals(routes.get(key).routeKind()))
                .hasSize(3);
        assertThat(ROUTES).filteredOn(key -> "ACTION".equals(routes.get(key).routeKind()))
                .hasSize(16);
        assertThat(routes.get("route.hcm.configuration.connector-execution-create.action")
                .stepUpCommandBindings()).isNullOrEmpty();
        assertThat(routes.get("route.hcm.configuration.connector-execution-detail.data")
                .stepUpCommandBindings()).isNullOrEmpty();
        assertThat(ROUTES.stream()
                .map(routes::get)
                .flatMap(route -> route.stepUpCommandBindings() == null
                        ? java.util.stream.Stream.empty()
                        : route.stepUpCommandBindings().stream()))
                .hasSize(7)
                .allSatisfy(binding -> {
                    assertThat(binding.ownerServiceKey()).isEqualTo("platform");
                    assertThat(binding.audience()).isEqualTo("dwp-platform-server");
                    assertThat(binding.expectedObjectVersionSource())
                            .isEqualTo("COMMAND_HEADER");
                    assertThat(binding.expectedObjectVersionName()).isEqualTo("If-Match");
                });
    }

    @Test
    void genericSessionExceptionIsV60OnlyAndClosedOverTheExactProfile() throws Exception {
        var contract = load("product-surfaces-v1.bundle-v60.generated.json");
        var capabilities = indexCapabilities(contract);
        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        var create = routes.get(
                "route.hcm.configuration.connector-execution-create.action");
        var detail = routes.get(
                "route.hcm.configuration.connector-execution-detail.data");

        assertThatNoException().isThrownBy(() ->
                ProductAuthorizationGateTopologyValidator.validateRoute(
                        create, capabilities, 60));
        assertThatNoException().isThrownBy(() ->
                ProductAuthorizationGateTopologyValidator.validateRoute(
                        detail, capabilities, 60));
        assertThatThrownBy(() -> ProductAuthorizationGateTopologyValidator.validateRoute(
                create, capabilities, 59))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete step-up command bindings");

        var profile = create.accessProfiles().getFirst();
        var widenedProfile = new ProductAuthorizationContractDtos.AccessProfile(
                profile.profileKey(), profile.precedence(),
                List.of("NORMAL", "ELEVATED"), profile.requiredAccess(),
                profile.targetBindingKinds(), profile.predicatePolicyKeys(),
                profile.responseProjectionBindings(), profile.readOnly());
        var widenedRoute = new ProductAuthorizationContractDtos.GovernedRoute(
                create.routeContractKey(), create.navigationContextId(), create.subject(),
                create.routeKind(), create.bindingMode(), create.purposeCode(),
                create.sideEffectFree(), create.uiRouteId(), create.uiRoutePattern(),
                List.of(widenedProfile), create.gatewayApiBindings(),
                create.servicePepBindings(), create.authorizationEquivalenceKey(),
                create.stepUpCommandBindings(), create.consumedApiRouteContractKeys(),
                create.owner(), create.policyVersion(), create.lifecycleState());
        assertThatThrownBy(() -> ProductAuthorizationGateTopologyValidator.validateRoute(
                widenedRoute, capabilities, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete step-up command bindings");
    }

    private ProductAuthorizationContractDtos.BundleContract load(String artifact)
            throws Exception {
        try (var input = new ClassPathResource(
                "product-authorization/" + artifact).getInputStream()) {
            return validator.validateDocument(input);
        }
    }

    private Map<String, ProductAuthorizationContractDtos.CapabilityContract>
            indexCapabilities(ProductAuthorizationContractDtos.BundleContract contract) {
        return contract.capabilities().stream().collect(Collectors.toMap(
                ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                Function.identity()));
    }

    private void assertCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability,
            String code,
            String resource,
            String action,
            String risk,
            String activation,
            String responsibility,
            int routeCount) {
        assertThat(capability).isNotNull();
        assertThat(capability.resolvedCapabilityCode()).isEqualTo(code);
        assertThat(capability.resourceKey()).isEqualTo(resource);
        assertThat(capability.action()).isEqualTo(action);
        assertThat(capability.riskTier()).isEqualTo(risk);
        assertThat(capability.activationPolicy()).isEqualTo(activation);
        assertThat(capability.requiredResponsibilityCode()).isEqualTo(responsibility);
        assertThat(capability.responsibilityRequirement()).isEqualTo("REQUIRED");
        assertThat(capability.authorityMode()).isEqualTo("PERMISSION_AND_RELATIONSHIP");
        assertThat(capability.scopeResolver()).isEqualTo("CONNECTOR_OR_LEGAL_ENTITY_SCOPE");
        assertThat(capability.requiresProductEntitlement()).isTrue();
        assertThat(capability.routeContractKeys()).hasSize(routeCount);
    }
}
