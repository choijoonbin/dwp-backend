package com.dwp.services.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAuthorizationV51ContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(mapper);

    @Test
    void acceptsThePinnedV51AndKeepsAuthorizationOnlyPagesOffTheNetwork() throws Exception {
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v51.generated.json")
                .getInputStream()) {
            var contract = validator.validateDocument(input);
            assertThat(contract.version()).isEqualTo(51);
            assertThat(contract.checksum()).isEqualTo(
                    "3beda5c13a3af0e265ace4533a3afcee3691c2b7486465dcf46156e58ae5ed2c");
            Map<String, com.dwp.services.auth.dto.ProductAuthorizationContractDtos.GovernedRoute>
                    routes = contract.routes().stream().collect(Collectors.toMap(
                    com.dwp.services.auth.dto.ProductAuthorizationContractDtos.GovernedRoute
                            ::routeContractKey,
                    Function.identity()));
            for (String key : java.util.Set.of(
                    "route.hcm.configuration.workflow.page",
                    "route.hcm.team.delegation.page")) {
                var route = routes.get(key);
                assertThat(route.bindingMode()).isEqualTo("AUTHORIZATION_ONLY");
                assertThat(route.gatewayApiBindings()).isEmpty();
                assertThat(route.servicePepBindings()).isEmpty();
                assertThat(route.accessProfiles().getFirst().responseProjectionBindings())
                        .isNullOrEmpty();
            }
        }
    }

    @Test
    void rejectsAnAuthorizationOnlyPageWithANetworkBinding() throws Exception {
        ObjectNode document;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v51.generated.json")
                .getInputStream()) {
            document = (ObjectNode) mapper.readTree(input);
        }
        ObjectNode route = null;
        for (var candidate : document.path("routes")) {
            if ("route.hcm.configuration.workflow.page".equals(
                    candidate.path("routeContractKey").asText())) {
                route = (ObjectNode) candidate;
                break;
            }
        }
        assertThat(route).isNotNull();
        ObjectNode binding = mapper.createObjectNode();
        binding.put("bindingKey", "route.hcm.configuration.workflow.page.binding.01");
        binding.put("method", "GET");
        binding.put("path", "/api/people/v1/forbidden");
        binding.set("pathParameterConstraints", mapper.createObjectNode());
        ((ArrayNode) route.path("gatewayApiBindings")).add(binding);
        document.put("checksum", validator.checksum(document));

        assertThatThrownBy(() -> validator.validateDocument(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid authorization-only PAGE envelope");
    }
}
