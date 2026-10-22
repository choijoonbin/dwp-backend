package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationV58ContractTest {
    private static final String CAPABILITY = "hcm.operations.payroll-support.view";
    private static final String PREDICATE =
            "predicate.hcm-payroll-population-authority.v1";
    private static final String PAGE = "route.hcm.operations.pay-retirement.page";
    private static final String DATA = "route.hcm.operations.pay-retirement.data";

    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void sealsThePay004RetirementReadPairWithoutAddingACommand() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v58.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }

        assertThat(contract.version()).isEqualTo(58);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(
                "556f2cc1554f0aaab6f88e50c34d64ca5cdd95af9b2d55072f6e6745f4652cc2");
        assertThat(contract.capabilities()).hasSize(271);
        assertThat(contract.accessPolicies()).hasSize(24);
        assertThat(contract.entitlementExpressions()).hasSize(17);
        assertThat(contract.predicatePolicies()).hasSize(83);
        assertThat(contract.routes()).hasSize(1125);

        assertThat(contract.capabilities()).filteredOn(value ->
                CAPABILITY.equals(value.contractKey())).singleElement()
                .satisfies(capability -> {
                    assertThat(capability.resolvedCapabilityCode())
                            .isEqualTo("DATA.HRIS_PAYROLL_STATUS:VIEW");
                    assertThat(capability.routeContractKeys()).containsExactlyInAnyOrder(
                            "route.hcm.operations.payroll-population.page",
                            "route.hcm.operations.payroll-run.data",
                            "route.hcm.operations.payroll-support.data",
                            "route.hcm.operations.pay-tax-social-insurance.page",
                            "route.hcm.operations.pay-tax-social-insurance.data",
                            PAGE, DATA);
                });
        assertThat(contract.predicatePolicies()).filteredOn(value ->
                PREDICATE.equals(value.predicatePolicyKey())).singleElement()
                .satisfies(predicate -> {
                    assertThat(predicate.ownerServiceKey()).isEqualTo("payroll");
                    assertThat(predicate.targetBindingKinds())
                            .containsExactly("TARGET_POPULATION");
                    assertThat(predicate.routeContractKeys()).containsExactlyInAnyOrder(
                            "route.hcm.operations.payroll-population.page",
                            "route.hcm.operations.payroll-support.data",
                            "route.hcm.operations.pay-tax-social-insurance.page",
                            "route.hcm.operations.pay-tax-social-insurance.data",
                            PAGE, DATA);
                });

        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        var page = routes.get(PAGE);
        var data = routes.get(DATA);
        assertThat(page.authorizationEquivalenceKey())
                .isEqualTo("wire-authority.hcm.operations.pay-retirement.v1")
                .isEqualTo(data.authorizationEquivalenceKey());
        assertThat(page.uiRouteId()).isEqualTo("hcm.operations.pay-retirement");
        assertThat(page.uiRoutePattern()).isEqualTo("/hr/operations/payroll/retirement");
        assertThat(data.uiRouteId()).isNull();
        assertThat(data.uiRoutePattern()).isNull();
        assertThat(page.routeKind()).isEqualTo("PAGE");
        assertThat(data.routeKind()).isEqualTo("DATA");
        assertThat(page.sideEffectFree()).isNull();
        assertThat(data.sideEffectFree()).isTrue();

        for (var read : List.of(page, data)) {
            assertThat(read.gatewayApiBindings()).singleElement().satisfies(binding -> {
                assertThat(binding.method()).isEqualTo("GET");
                assertThat(binding.path())
                        .isEqualTo("/api/payroll/v1/workbench/pay-retirement");
            });
            assertThat(read.servicePepBindings()).singleElement().satisfies(binding -> {
                assertThat(binding.serviceKey()).isEqualTo("payroll");
                assertThat(binding.method()).isEqualTo("GET");
                assertThat(binding.path()).isEqualTo("/v1/workbench/pay-retirement");
            });
            assertThat(read.accessProfiles()).singleElement().satisfies(profile -> {
                assertThat(profile.profileKey()).isEqualTo("payroll-support-viewer");
                assertThat(profile.activeAccessModes())
                        .containsExactly("NORMAL", "ELEVATED");
                assertThat(profile.requiredAccess().capabilityContractKey())
                        .isEqualTo(CAPABILITY);
                assertThat(profile.targetBindingKinds())
                        .containsExactly("TARGET_POPULATION");
                assertThat(profile.predicatePolicyKeys()).containsExactly(PREDICATE);
                assertThat(profile.readOnly()).isTrue();
                assertThat(profile.responseProjectionBindings()).singleElement()
                        .satisfies(projection -> {
                            assertThat(projection.projectionPolicyKey())
                                    .isEqualTo("pay004.iaq-049.fields.v1");
                            assertThat(projection.responseSchemaKey())
                                    .isEqualTo("Pay004RetirementProjectionV1");
                        });
            });
            assertThat(read.stepUpCommandBindings()).isNullOrEmpty();
        }

        assertThat(routes.keySet()).doesNotContain(
                "route.hcm.operations.pay-retirement.action");
        assertThat(Set.of(page.routeContractKey(), data.routeContractKey()))
                .containsExactlyInAnyOrder(PAGE, DATA);
    }
}
