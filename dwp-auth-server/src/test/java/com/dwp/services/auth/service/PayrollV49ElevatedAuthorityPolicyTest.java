package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PayrollV49ElevatedAuthorityPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void admitsOnlyTheFourExactV49ElevatedContractsForNormalChallenge() throws Exception {
        ProductAuthorizationContractDtos.BundleContract bundle = bundle();
        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes = bundle.routes()
                .stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities =
                bundle.capabilities().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                        Function.identity()));

        for (String routeKey : PayrollV49ElevatedAuthorityPolicy.ROUTES) {
            ProductAuthorizationContractDtos.GovernedRoute route = routes.get(routeKey);
            assertThat(PayrollV49ElevatedAuthorityPolicy.isExactRoute(route, capabilities))
                    .as(routeKey).isTrue();
            assertThat(PayrollV49ElevatedAuthorityPolicy.isNormalChallengeCandidate(
                    request(ProductSurfaceAuthorityDtos.AccessMode.NORMAL, routeKey),
                    route, capabilities)).as(routeKey).isTrue();
            assertThat(PayrollV49ElevatedAuthorityPolicy.isNormalChallengeCandidate(
                    request(ProductSurfaceAuthorityDtos.AccessMode.ELEVATED, routeKey),
                    route, capabilities)).as(routeKey).isFalse();
            assertThat(PayrollV49ElevatedAuthorityPolicy.isExactCommandRoute(
                    route, capabilities)).as(routeKey)
                    .isEqualTo(PayrollV49ElevatedAuthorityPolicy.COMMAND_ROUTES
                            .contains(routeKey));
        }

        assertThat(PayrollV49ElevatedAuthorityPolicy.isExactRoute(
                routes.get("route.hcm.operations.payroll-run.data"), capabilities)).isFalse();
    }

    @Test
    void rejectsModeProfileBindingAndCapabilityDrift() throws Exception {
        ProductAuthorizationContractDtos.BundleContract bundle = bundle();
        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities =
                bundle.capabilities().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                        Function.identity()));
        ProductAuthorizationContractDtos.GovernedRoute prepare = bundle.routes().stream()
                .filter(route -> "route.hcm.operations.payroll-run.action"
                        .equals(route.routeContractKey()))
                .findFirst().orElseThrow();

        ObjectNode wrongMode = mapper.valueToTree(prepare);
        ((ObjectNode) wrongMode.withArray("accessProfiles").get(0))
                .set("activeAccessModes", mapper.createArrayNode().add("NORMAL").add("ELEVATED"));
        assertThat(PayrollV49ElevatedAuthorityPolicy.isExactRoute(
                route(wrongMode), capabilities)).isFalse();

        ObjectNode extraProfile = mapper.valueToTree(prepare);
        ArrayNode profiles = extraProfile.withArray("accessProfiles");
        profiles.add(profiles.get(0).deepCopy());
        assertThat(PayrollV49ElevatedAuthorityPolicy.isExactRoute(
                route(extraProfile), capabilities)).isFalse();

        ObjectNode wrongAudience = mapper.valueToTree(prepare);
        ((ObjectNode) wrongAudience.withArray("stepUpCommandBindings").get(0))
                .put("audience", "dwp-people-server");
        assertThat(PayrollV49ElevatedAuthorityPolicy.isExactRoute(
                route(wrongAudience), capabilities)).isFalse();

        Map<String, ProductAuthorizationContractDtos.CapabilityContract> wrongCapability =
                new HashMap<>(capabilities);
        ObjectNode capability = mapper.valueToTree(
                capabilities.get(PayrollV49ElevatedAuthorityPolicy.RUN_CAPABILITY));
        capability.put("riskTier", "HIGH");
        wrongCapability.put(PayrollV49ElevatedAuthorityPolicy.RUN_CAPABILITY,
                mapper.treeToValue(capability,
                        ProductAuthorizationContractDtos.CapabilityContract.class));
        assertThat(PayrollV49ElevatedAuthorityPolicy.isExactRoute(
                prepare, wrongCapability)).isFalse();
    }

    private ProductAuthorizationContractDtos.BundleContract bundle() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/product-authorization/product-surfaces-v1.bundle-v49.generated.json")) {
            return mapper.readValue(input, ProductAuthorizationContractDtos.BundleContract.class);
        }
    }

    private ProductAuthorizationContractDtos.GovernedRoute route(ObjectNode value)
            throws Exception {
        return mapper.treeToValue(value, ProductAuthorizationContractDtos.GovernedRoute.class);
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request(
            ProductSurfaceAuthorityDtos.AccessMode mode,
            String routeKey) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                7L, 41L, "hcm", "hcm.operations", mode, routeKey,
                null, null, null, null, List.of());
    }
}
