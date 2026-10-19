package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationV53ContractTest {

    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void acceptsThePinnedV53AndSealsTheThreeHrm011Routes() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v53.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }
        assertThat(contract.version()).isEqualTo(53);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(
                "c655a850ae2ac01466edb37f2e1c69f9e269dd2a4a529c3a87b1610c0561a359");
        assertThat(contract.capabilities()).hasSize(269);
        assertThat(contract.routes()).hasSize(1095);

        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        assertThat(routes).containsKeys(
                "route.hcm.operations.contracts-compensation-basis.page",
                "route.hcm.operations.contracts-compensation-basis.data",
                "route.hcm.operations.employment-contract-issue.action");

        var matchingCapabilities = contract.capabilities().stream()
                .filter(value -> "hcm.operations.contract.issue".equals(value.contractKey()))
                .toList();
        assertThat(matchingCapabilities).singleElement();
        var capability = matchingCapabilities.getFirst();
        assertThat(capability.resolvedCapabilityCode())
                .isEqualTo("ACTION.HRIS_EMPLOYMENT_CONTRACT:ISSUE");
        assertThat(capability.resourceKey()).isEqualTo("ACTION.HRIS_EMPLOYMENT_CONTRACT");
        assertThat(capability.action()).isEqualTo("ISSUE");
        assertThat(capability.scopeResolver()).isEqualTo("WORKFORCE_TARGET_POPULATION");
        assertThat(capability.riskTier()).isEqualTo("HIGH");
        assertThat(capability.activationPolicy()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(capability.responsibilityRequirement()).isEqualTo("REQUIRED");
        assertThat(capability.requiredResponsibilityCode())
                .isEqualTo("HRIS_DUTY_CONTRACT_ISSUER");
        assertThat(capability.sodPolicyId()).isEqualTo("NO_APPROVE_OWN_CHANGE");
        assertThat(capability.routeContractKeys())
                .containsExactly("route.hcm.operations.employment-contract-issue.action");

        var page = routes.get("route.hcm.operations.contracts-compensation-basis.page");
        var data = routes.get("route.hcm.operations.contracts-compensation-basis.data");
        assertThat(page.authorizationEquivalenceKey())
                .isEqualTo("wire-authority.hcm.operations.contracts-compensation-basis.v1")
                .isEqualTo(data.authorizationEquivalenceKey());
        assertThat(page.gatewayApiBindings().getFirst().path())
                .isEqualTo(data.gatewayApiBindings().getFirst().path())
                .isEqualTo("/api/people/v1/hris/workbench/operations/contracts/compensation/basis");
        assertThat(page.servicePepBindings().getFirst().path())
                .isEqualTo(data.servicePepBindings().getFirst().path())
                .isEqualTo("/v1/hris/workbench/operations/contracts/compensation/basis");
        for (var read : java.util.List.of(page, data)) {
            var profile = read.accessProfiles().getFirst();
            assertThat(profile.profileKey()).isEqualTo("people-operations-reader");
            assertThat(profile.requiredAccess().capabilityContractKey())
                    .isEqualTo("hcm.operations.people.view");
            assertThat(profile.targetBindingKinds()).containsExactly("TARGET_POPULATION");
            assertThat(profile.responseProjectionBindings()).singleElement()
                    .satisfies(projection -> {
                        assertThat(projection.responseSchemaKey())
                                .isEqualTo("Hrm011ContractsCompensationBasisProjectionV2");
                        assertThat(projection.projectionPolicyKey())
                                .isEqualTo(read.routeContractKey()
                                        + ".people-operations-reader.projection.v1");
                    });
        }

        var action = routes.get("route.hcm.operations.employment-contract-issue.action");
        assertThat(action.accessProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.profileKey()).isEqualTo("employment-contract-issuer");
            assertThat(profile.requiredAccess().capabilityContractKey())
                    .isEqualTo("hcm.operations.contract.issue");
            assertThat(profile.targetBindingKinds())
                    .containsExactly("OBJECT", "TARGET_POPULATION");
            assertThat(profile.readOnly()).isFalse();
        });
        assertThat(action.stepUpCommandBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.targetType()).isEqualTo("WORK_RELATIONSHIP");
            assertThat(binding.targetIdBodyFields()).containsExactly("relationshipPublicId");
            assertThat(binding.expectedObjectVersionSource()).isEqualTo("COMMAND_HEADER");
            assertThat(binding.expectedObjectVersionName())
                    .isEqualTo("X-DWP-Expected-Object-Version");
            assertThat(binding.ownerServiceKey()).isEqualTo("people");
            assertThat(binding.audience()).isEqualTo("dwp-people-server");
        });
    }
}
