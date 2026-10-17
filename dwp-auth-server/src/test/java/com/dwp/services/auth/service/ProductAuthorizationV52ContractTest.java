package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationV52ContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(mapper);

    @Test
    void acceptsThePinnedV52AndSealsTheFiveHrm009Routes() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v52.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }
        assertThat(contract.version()).isEqualTo(52);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(
                "b33fbf968e71f97b09fa497fdfad7d2f6854199170b79fd1602a0ea604977346");
        assertThat(contract.capabilities()).hasSize(268);
        assertThat(contract.routes()).hasSize(1092);

        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        assertThat(routes).containsKeys(
                "route.hcm.personal.requests.page",
                "route.hcm.personal.requests.data",
                "route.hcm.operations.certificates-documents.page",
                "route.hcm.operations.certificates-documents.data",
                "route.hcm.operations.certificate-issue.action");
        assertThat(routes).doesNotContainKey("route.hcm.personal.certificate-request.action");

        var action = routes.get("route.hcm.operations.certificate-issue.action");
        assertThat(action.accessProfiles())
                .extracting(ProductAuthorizationContractDtos.AccessProfile::profileKey)
                .containsExactly(
                        "certificate-operations-issuer",
                        "certificate-self-requester");
        assertThat(action.stepUpCommandBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.targetType()).isEqualTo("PERSON");
            assertThat(binding.targetIdBodyFields()).containsExactly("personPublicId");
            assertThat(binding.expectedObjectVersionSource()).isEqualTo("COMMAND_HEADER");
            assertThat(binding.expectedObjectVersionName())
                    .isEqualTo("X-DWP-Expected-Object-Version");
            assertThat(binding.ownerServiceKey()).isEqualTo("people");
            assertThat(binding.audience()).isEqualTo("dwp-people-server");
        });
    }

    @Test
    void dataRoutesCarryTheExactOwnerProjectionKeys() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v52.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }
        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        assertProjection(
                routes.get("route.hcm.personal.requests.data"),
                "route.hcm.personal.requests.data.self-request-reader.projection.v1",
                "Hrm009SelfRequestsProjectionV1");
        assertProjection(
                routes.get("route.hcm.operations.certificates-documents.data"),
                "route.hcm.operations.certificates-documents.data."
                        + "people-operations-reader.projection.v1",
                "Hrm009CertificatesDocumentsProjectionV1");
    }

    private void assertProjection(
            ProductAuthorizationContractDtos.GovernedRoute route,
            String projectionPolicyKey,
            String responseSchemaKey) {
        assertThat(route.accessProfiles()).singleElement().satisfies(profile ->
                assertThat(profile.responseProjectionBindings()).singleElement()
                        .satisfies(binding -> {
                            assertThat(binding.projectionPolicyKey())
                                    .isEqualTo(projectionPolicyKey);
                            assertThat(binding.responseSchemaKey())
                                    .isEqualTo(responseSchemaKey);
                        }));
    }
}
