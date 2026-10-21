package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationV57ContractTest {
    private static final String CAPABILITY = "hcm.integration.workforce-ingestion.execute";
    private static final String PREDICATE = "predicate.hcm-workforce-ingestion-scope.v1";
    private static final String PAGE = "route.hcm.operations.people-data-quality.page";
    private static final String DATA = "route.hcm.operations.people-data-quality.data";
    private static final String ACTION = "route.hcm.operations.workforce-ingestion.action";

    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void sealsTheNarrowHrm012CapabilityPredicateAndThreeExactPeopleRoutes()
            throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v57.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }
        assertThat(contract.version()).isEqualTo(57);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(
                "f7d6e2ef92f563747ed062e496638ea83498a33414fc12586c794f517e626d6e");
        assertThat(contract.capabilities()).hasSize(271);
        assertThat(contract.accessPolicies()).hasSize(24);
        assertThat(contract.entitlementExpressions()).hasSize(17);
        assertThat(contract.predicatePolicies()).hasSize(83);
        assertThat(contract.routes()).hasSize(1123);

        assertThat(contract.capabilities()).filteredOn(value ->
                CAPABILITY.equals(value.contractKey())).singleElement()
                .satisfies(capability -> {
                    assertThat(capability.resolvedCapabilityCode())
                            .isEqualTo("ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE");
                    assertThat(capability.resourceKey())
                            .isEqualTo("ACTION.HRIS_INTEGRATION_EXECUTE");
                    assertThat(capability.action()).isEqualTo("EXECUTE");
                    assertThat(capability.requiresProductEntitlement()).isTrue();
                    assertThat(capability.authorityMode())
                            .isEqualTo("PERMISSION_AND_RELATIONSHIP");
                    assertThat(capability.scopeResolver())
                            .isEqualTo("CONNECTOR_OR_LEGAL_ENTITY_SCOPE");
                    assertThat(capability.riskTier()).isEqualTo("HIGH");
                    assertThat(capability.activationPolicy())
                            .isEqualTo("STEPUP-MGMT-HIGH-V1");
                    assertThat(capability.requiredResponsibilityCode())
                            .isEqualTo("HRIS_DUTY_INTEGRATION_EXECUTOR");
                    assertThat(capability.routeContractKeys()).containsExactly(ACTION);
                });
        assertThat(contract.capabilities()).filteredOn(value ->
                "hcm.integration.execute".equals(value.contractKey())).singleElement()
                .satisfies(legacy -> {
                    assertThat(legacy.resolvedCapabilityCode())
                            .isEqualTo("ACTION.WORKFORCE_DATA_OPERATIONS:EXECUTE");
                    assertThat(legacy.requiredResponsibilityCode())
                            .isEqualTo("APP_CONFIG_ADMIN");
                });
        assertThat(contract.predicatePolicies()).filteredOn(value ->
                PREDICATE.equals(value.predicatePolicyKey())).singleElement()
                .satisfies(predicate -> {
                    assertThat(predicate.ownerServiceKey()).isEqualTo("people");
                    assertThat(predicate.targetBindingKinds())
                            .containsExactly("OBJECT", "TARGET_POPULATION");
                    assertThat(predicate.inputEvidenceSchemaKey())
                            .isEqualTo("HcmWorkforceIngestionScopeEvidenceV1");
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
                .isEqualTo("wire-authority.hcm.operations.people-data-quality.v1")
                .isEqualTo(data.authorizationEquivalenceKey());
        assertThat(page.uiRouteId()).isEqualTo("hcm.operations.people-data-quality");
        assertThat(page.uiRoutePattern()).isEqualTo("/hr/operations/people/data-quality");
        for (var read : java.util.List.of(page, data)) {
            assertThat(read.gatewayApiBindings()).singleElement().satisfies(binding -> {
                assertThat(binding.method()).isEqualTo("GET");
                assertThat(binding.path()).isEqualTo(
                        "/api/people/v1/hris/workbench/operations/data/quality");
            });
            assertThat(read.servicePepBindings()).singleElement().satisfies(binding -> {
                assertThat(binding.method()).isEqualTo("GET");
                assertThat(binding.path()).isEqualTo(
                        "/v1/hris/workbench/operations/data/quality");
                assertThat(binding.serviceKey()).isEqualTo("people");
            });
            assertThat(read.accessProfiles()).singleElement().satisfies(profile -> {
                assertThat(profile.profileKey()).isEqualTo("people-data-quality-reader");
                assertThat(profile.requiredAccess().capabilityContractKey())
                        .isEqualTo("hcm.operations.people.view");
                assertThat(profile.targetBindingKinds())
                        .containsExactly("TARGET_POPULATION");
                assertThat(profile.predicatePolicyKeys()).containsExactly(PREDICATE);
                assertThat(profile.readOnly()).isTrue();
                assertThat(profile.responseProjectionBindings()).singleElement()
                        .satisfies(projection -> assertThat(projection.responseSchemaKey())
                                .isEqualTo("Hrm012DataQualityProjectionV1"));
            });
        }

        var action = routes.get(ACTION);
        assertThat(action.gatewayApiBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.method()).isEqualTo("POST");
            assertThat(binding.path()).isEqualTo(
                    "/api/people/v1/hris/integrations/workforce-ingestions");
        });
        assertThat(action.servicePepBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.method()).isEqualTo("POST");
            assertThat(binding.path()).isEqualTo(
                    "/v1/hris/integrations/workforce-ingestions");
            assertThat(binding.serviceKey()).isEqualTo("people");
        });
        assertThat(action.accessProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.profileKey()).isEqualTo("workforce-ingestion-executor");
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
            assertThat(binding.targetType()).isEqualTo("WORKFORCE_INGESTION_OBJECT");
            assertThat(binding.targetIdBodyFields()).containsExactly("objectRef");
            assertThat(binding.expectedObjectVersionSource()).isEqualTo("COMMAND_HEADER");
            assertThat(binding.expectedObjectVersionName()).isEqualTo("If-Match");
            assertThat(binding.ownerServiceKey()).isEqualTo("people");
            assertThat(binding.audience()).isEqualTo("dwp-people-server");
        });
    }
}
