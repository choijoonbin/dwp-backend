package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationV54ContractTest {

    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void sealsPay003StatutoryAuthorizationAndFiveExactRoutes() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v54.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }
        assertThat(contract.version()).isEqualTo(54);
        assertThat(contract.checksum()).isEqualTo(
                "1a199b3ec84b6d6c16aaec57c9be0b09092c5f81e0c5566f893c4dbe0e0deed4");
        assertThat(contract.capabilities()).hasSize(270);
        assertThat(contract.predicatePolicies()).hasSize(82);
        assertThat(contract.routes()).hasSize(1100);

        var capabilities = contract.capabilities().stream()
                .filter(value -> "hcm.operations.statutory.execute"
                        .equals(value.contractKey()))
                .toList();
        assertThat(capabilities).singleElement();
        var capability = capabilities.getFirst();
        assertThat(capability.resolvedCapabilityCode())
                .isEqualTo("ACTION.HRIS_STATUTORY_FILING:EXECUTE");
        assertThat(capability.scopeResolver())
                .isEqualTo("PAYROLL_LEGAL_ENTITY_GROUP_SCOPE");
        assertThat(capability.riskTier()).isEqualTo("HIGH");
        assertThat(capability.activationPolicy()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(capability.requiredResponsibilityCode())
                .isEqualTo("HRIS_DUTY_STATUTORY_OPERATOR");
        assertThat(capability.sodPolicyId())
                .isEqualTo("PACK_INSTALLER_NE_STATUTORY_APPROVER");
        assertThat(capability.routeContractKeys())
                .containsExactly("route.hcm.operations.country-pack-activate.action");

        assertThat(contract.predicatePolicies()).filteredOn(value ->
                "predicate.hcm-payroll-country-pack-authority.v1"
                        .equals(value.predicatePolicyKey()))
                .singleElement()
                .satisfies(predicate -> {
                    assertThat(predicate.ownerServiceKey()).isEqualTo("payroll");
                    assertThat(predicate.targetBindingKinds())
                            .containsExactly("OBJECT", "TARGET_POPULATION");
                    assertThat(predicate.inputEvidenceSchemaKey())
                            .isEqualTo("HcmPayrollCountryPackAuthorityEvidenceV1");
                });

        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        assertReadPair(routes,
                "route.hcm.configuration.statutory.page",
                "route.hcm.configuration.statutory.data",
                "wire-authority.hcm.configuration.statutory.v1",
                "configuration-workbench-reader",
                "hcm.configuration.workbench.view",
                "CONFIG_SCOPE",
                "predicate.hcm-payroll-configuration-authority.v1",
                "pay003.iaq-026.fields.v1",
                "Pay003StatutoryConfigurationProjectionV2",
                "/api/payroll/v1/workbench/configuration/statutory");
        assertReadPair(routes,
                "route.hcm.operations.pay-tax-social-insurance.page",
                "route.hcm.operations.pay-tax-social-insurance.data",
                "wire-authority.hcm.operations.pay-tax-social-insurance.v1",
                "payroll-support-viewer",
                "hcm.operations.payroll-support.view",
                "TARGET_POPULATION",
                "predicate.hcm-payroll-population-authority.v1",
                "pay003.iaq-048.fields.v1",
                "Pay003TaxSocialInsuranceProjectionV1",
                "/api/payroll/v1/workbench/pay-tax-social-insurance");

        var action = routes.get("route.hcm.operations.country-pack-activate.action");
        assertThat(action.gatewayApiBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.method()).isEqualTo("POST");
            assertThat(binding.path())
                    .isEqualTo("/api/payroll/v1/country-packs/{packId}/activate");
        });
        assertThat(action.accessProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.profileKey()).isEqualTo("statutory-operator");
            assertThat(profile.activeAccessModes()).containsExactly("NORMAL", "ELEVATED");
            assertThat(profile.requiredAccess().capabilityContractKey())
                    .isEqualTo("hcm.operations.statutory.execute");
            assertThat(profile.targetBindingKinds())
                    .containsExactly("OBJECT", "TARGET_POPULATION");
            assertThat(profile.predicatePolicyKeys())
                    .containsExactly("predicate.hcm-payroll-country-pack-authority.v1");
        });
        assertThat(action.stepUpCommandBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.targetType()).isEqualTo("COUNTRY_PACK");
            assertThat(binding.targetIdPathParameter()).isEqualTo("packId");
            assertThat(binding.expectedObjectVersionName())
                    .isEqualTo("X-DWP-Expected-Object-Version");
            assertThat(binding.ownerServiceKey()).isEqualTo("payroll");
            assertThat(binding.audience()).isEqualTo("dwp-payroll-server");
        });
    }

    private void assertReadPair(
            Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes,
            String pageKey,
            String dataKey,
            String equivalence,
            String profileKey,
            String capabilityKey,
            String targetKind,
            String predicateKey,
            String fieldPolicy,
            String responseSchema,
            String browserPath) {
        var page = routes.get(pageKey);
        var data = routes.get(dataKey);
        assertThat(page.authorizationEquivalenceKey()).isEqualTo(equivalence)
                .isEqualTo(data.authorizationEquivalenceKey());
        assertThat(page.gatewayApiBindings().getFirst().path()).isEqualTo(browserPath)
                .isEqualTo(data.gatewayApiBindings().getFirst().path());
        for (var route : java.util.List.of(page, data)) {
            assertThat(route.accessProfiles()).singleElement().satisfies(profile -> {
                assertThat(profile.profileKey()).isEqualTo(profileKey);
                assertThat(profile.requiredAccess().capabilityContractKey())
                        .isEqualTo(capabilityKey);
                assertThat(profile.targetBindingKinds()).containsExactly(targetKind);
                assertThat(profile.predicatePolicyKeys()).containsExactly(predicateKey);
                assertThat(profile.responseProjectionBindings()).singleElement()
                        .satisfies(projection -> {
                            assertThat(projection.projectionPolicyKey())
                                    .isEqualTo(fieldPolicy);
                            assertThat(projection.responseSchemaKey())
                                    .isEqualTo(responseSchema);
                        });
            });
        }
    }
}
