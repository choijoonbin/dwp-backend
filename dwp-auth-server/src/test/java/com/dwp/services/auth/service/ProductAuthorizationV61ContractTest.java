package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationV61ContractTest {
    private static final String CAPABILITY = "hcm.operations.separation.manage";
    private static final String PREDICATE = "predicate.hcm-separation-case-scope.v1";
    private static final String PAGE = "route.hcm.operations.exit-return.page";
    private static final String DATA = "route.hcm.operations.exit-return.data";
    private static final String ACTION = "route.hcm.operations.separation-case.action";

    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void sealsTheNarrowHrm013CapabilityPredicateAndThreeExactPeopleRoutes()
            throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v61.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }
        assertThat(contract.version()).isEqualTo(61);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(
                "f7cf92a97f2fabf86f257010064a3183acf58f86cc2c9d7692a1555cab55aa24");
        assertThat(contract.capabilities()).hasSize(279);
        assertThat(contract.accessPolicies()).hasSize(24);
        assertThat(contract.entitlementExpressions()).hasSize(17);
        assertThat(contract.predicatePolicies()).hasSize(87);
        assertThat(contract.routes()).hasSize(1157);

        assertThat(contract.capabilities()).filteredOn(value ->
                CAPABILITY.equals(value.contractKey())).singleElement()
                .satisfies(capability -> {
                    assertThat(capability.resolvedCapabilityCode())
                            .isEqualTo("ACTION.HRIS_SEPARATION_CASE:MANAGE");
                    assertThat(capability.resourceKey())
                            .isEqualTo("ACTION.HRIS_SEPARATION_CASE");
                    assertThat(capability.action()).isEqualTo("MANAGE");
                    assertThat(capability.requiresProductEntitlement()).isTrue();
                    assertThat(capability.authorityMode())
                            .isEqualTo("PERMISSION_AND_RELATIONSHIP");
                    assertThat(capability.scopeResolver())
                            .isEqualTo("WORKFORCE_TARGET_POPULATION");
                    assertThat(capability.riskTier()).isEqualTo("CRITICAL");
                    assertThat(capability.activationPolicy())
                            .isEqualTo("STEPUP-MGMT-HIGH-V1");
                    assertThat(capability.requiredResponsibilityCode())
                            .isEqualTo("HRIS_DUTY_SEPARATION_MAKER");
                    assertThat(capability.sodPolicyId()).isEqualTo("NO_APPROVE_OWN_CHANGE");
                    assertThat(capability.routeContractKeys()).containsExactly(ACTION);
                });
        assertThat(contract.predicatePolicies()).filteredOn(value ->
                PREDICATE.equals(value.predicatePolicyKey())).singleElement()
                .satisfies(predicate -> {
                    assertThat(predicate.ownerServiceKey()).isEqualTo("people");
                    assertThat(predicate.targetBindingKinds())
                            .containsExactly("OBJECT", "TARGET_POPULATION");
                    assertThat(predicate.inputEvidenceSchemaKey())
                            .isEqualTo("HcmSeparationCaseScopeEvidenceV1");
                    assertThat(predicate.parameterSchemaKey())
                            .isEqualTo("predicate.hcm-separation-case-scope.v1.parameters.v1");
                    assertThat(predicate.routeContractKeys())
                            .containsExactly(DATA, PAGE, ACTION);
                });

        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        var page = routes.get(PAGE);
        var data = routes.get(DATA);
        assertThat(page.authorizationEquivalenceKey())
                .isEqualTo("wire-authority.hcm.operations.exit-return.v1")
                .isEqualTo(data.authorizationEquivalenceKey());
        assertThat(page.uiRouteId()).isEqualTo("hcm.operations.exit-return");
        assertThat(page.uiRoutePattern()).isEqualTo("/hr/operations/people/exit-return");
        for (var read : java.util.List.of(page, data)) {
            assertThat(read.gatewayApiBindings()).singleElement().satisfies(binding -> {
                assertThat(binding.method()).isEqualTo("GET");
                assertThat(binding.path()).isEqualTo(
                        "/api/people/v1/hris/workbench/operations/exit/return");
            });
            assertThat(read.servicePepBindings()).singleElement().satisfies(binding -> {
                assertThat(binding.method()).isEqualTo("GET");
                assertThat(binding.path()).isEqualTo(
                        "/v1/hris/workbench/operations/exit/return");
                assertThat(binding.serviceKey()).isEqualTo("people");
            });
            assertThat(read.accessProfiles()).singleElement().satisfies(profile -> {
                assertThat(profile.profileKey()).isEqualTo("people-operations-reader");
                assertThat(profile.requiredAccess().capabilityContractKey())
                        .isEqualTo("hcm.operations.people.view");
                assertThat(profile.targetBindingKinds())
                        .containsExactly("TARGET_POPULATION");
                assertThat(profile.predicatePolicyKeys()).containsExactly(PREDICATE);
                assertThat(profile.readOnly()).isTrue();
                assertThat(profile.responseProjectionBindings()).singleElement()
                        .satisfies(projection -> assertThat(projection.responseSchemaKey())
                                .isEqualTo("Hrm013ExitReturnProjectionV1"));
            });
        }

        var action = routes.get(ACTION);
        assertThat(action.gatewayApiBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.method()).isEqualTo("POST");
            assertThat(binding.path()).isEqualTo("/api/people/v1/hris/separation-cases");
        });
        assertThat(action.servicePepBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.method()).isEqualTo("POST");
            assertThat(binding.path()).isEqualTo("/v1/hris/separation-cases");
            assertThat(binding.serviceKey()).isEqualTo("people");
        });
        assertThat(action.accessProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.profileKey()).isEqualTo("separation-case-maker");
            assertThat(profile.activeAccessModes()).containsExactly("ELEVATED");
            assertThat(profile.requiredAccess().capabilityContractKey())
                    .isEqualTo(CAPABILITY);
            assertThat(profile.targetBindingKinds())
                    .containsExactly("OBJECT", "TARGET_POPULATION");
            assertThat(profile.predicatePolicyKeys())
                    .containsExactly(PREDICATE, "predicate.people.object-version.v1");
            assertThat(profile.readOnly()).isFalse();
        });
        assertThat(action.stepUpCommandBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.targetType()).isEqualTo("WORK_RELATIONSHIP");
            assertThat(binding.targetIdBodyFields()).containsExactly("relationshipPublicId");
            assertThat(binding.expectedObjectVersionSource()).isEqualTo("COMMAND_HEADER");
            assertThat(binding.expectedObjectVersionName()).isEqualTo("If-Match");
            assertThat(binding.ownerServiceKey()).isEqualTo("people");
            assertThat(binding.audience()).isEqualTo("dwp-people-server");
        });
    }
}
