package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationAuthoritySupportTest {

    private static final List<String> ROLLOUT_PRODUCTS = List.of(
            "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
            "meetings", "messaging", "notifications", "services", "spaces", "workplace");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void productResourceFallbackRequiresOneCommonAppResource() throws Exception {
        Registry ambiguous = registry(
                List.of(policy("access-a", "EXPRESSION_A")),
                List.of(expression(
                        "EXPRESSION_A",
                        """
                                {
                                  "type": "ANY",
                                  "children": [
                                    {"type": "LEAF", "entitlement": "APP.A:VIEW"},
                                    {"type": "LEAF", "entitlement": "APP.B:VIEW"}
                                  ]
                                }
                                """)));
        Registry noCommon = registry(
                List.of(
                        policy("access-a", "EXPRESSION_A"),
                        policy("access-b", "EXPRESSION_B")),
                List.of(
                        expression(
                                "EXPRESSION_A",
                                "{\"type\":\"LEAF\",\"entitlement\":\"APP.A:VIEW\"}"),
                        expression(
                                "EXPRESSION_B",
                                "{\"type\":\"LEAF\",\"entitlement\":\"APP.B:VIEW\"}")));

        assertThat(ProductAuthorizationAuthoritySupport.productResourceKey(
                request(), ambiguous)).isNull();
        assertThat(ProductAuthorizationAuthoritySupport.productResourceKey(
                request(), noCommon)).isNull();
    }

    @Test
    void exactEntitlementEvaluationHonorsOnlyTheDeclaredHcmCompatibilityAlias() throws Exception {
        JsonNode hcmView = objectMapper.readTree(
                "{\"type\":\"LEAF\",\"entitlement\":\"APP.HCM:VIEW\"}");
        JsonNode hcmManage = objectMapper.readTree(
                "{\"type\":\"LEAF\",\"entitlement\":\"APP.HCM:MANAGE\"}");
        ProductAuthorizationIdentityEvidenceService.IdentityEvidence legacyIdentity =
                identity(Set.of("APP.HRIS:VIEW"));

        assertThat(ProductAuthorizationAuthoritySupport.evaluateEntitlement(
                hcmView, legacyIdentity)).isTrue();
        assertThat(ProductAuthorizationAuthoritySupport.evaluateEntitlement(
                hcmManage, legacyIdentity)).isFalse();
        assertThat(legacyIdentity.hasPermission("APP.HCM:VIEW")).isTrue();
        assertThat(legacyIdentity.hasPermission("APP.PEOPLE_DIRECTORY:VIEW")).isFalse();
    }

    @Test
    void immutableBundleVersionsHaveExactAdditiveProductParticipation() throws Exception {
        List<Set<String>> expectedProducts = List.of(
                Set.of("communications", "services"),
                Set.of("approvals", "communications", "services"),
                Set.of("approvals", "communications", "hcm", "services"));
        List<Integer> expectedProductRoutes = List.of(33, 74, 127);
        List<Integer> expectedCapabilities = List.of(10, 34, 62);

        for (int version = 1; version <= 3; version++) {
            ProductAuthorizationContractDtos.BundleContract contract = objectMapper
                    .findAndRegisterModules()
                    .readValue(
                            getClass().getResourceAsStream(
                                    "/product-authorization/"
                                            + "product-surfaces-v1.bundle-v"
                                            + version
                                            + ".generated.json"),
                            ProductAuthorizationContractDtos.BundleContract.class);
            Registry registry = new Registry(contract);

            assertThat(ROLLOUT_PRODUCTS.stream().filter(registry::hasProduct))
                    .containsExactlyInAnyOrderElementsOf(expectedProducts.get(version - 1));
            assertThat(contract.routes().stream()
                    .filter(route -> "PRODUCT".equals(route.subject().type())))
                    .hasSize(expectedProductRoutes.get(version - 1));
            assertThat(contract.capabilities())
                    .hasSize(expectedCapabilities.get(version - 1));
        }
    }

    private Registry registry(
            List<ProductAuthorizationContractDtos.AccessPolicy> policies,
            List<ProductAuthorizationContractDtos.EntitlementExpression> expressions) {
        return new Registry(new ProductAuthorizationContractDtos.BundleContract(
                1,
                "product-surfaces",
                1,
                "DRAFT",
                "test",
                "SHA-256",
                "checksum",
                List.of(),
                policies,
                expressions,
                List.of(),
                List.of(),
                List.of()));
    }

    private ProductAuthorizationContractDtos.AccessPolicy policy(
            String key,
            String expressionKey) {
        return new ProductAuthorizationContractDtos.AccessPolicy(
                key,
                "test.work",
                "test",
                "test.work",
                List.of("test.work"),
                "EXPRESSION",
                "ENTITLEMENT",
                expressionKey,
                true,
                null,
                "SELF",
                List.of(),
                List.of(),
                List.of(),
                "test",
                1,
                "ACTIVE");
    }

    private ProductAuthorizationContractDtos.EntitlementExpression expression(
            String key,
            String json) throws Exception {
        return new ProductAuthorizationContractDtos.EntitlementExpression(
                key,
                objectMapper.readTree(json),
                "test",
                1,
                "ACTIVE");
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request() {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                1L,
                2L,
                "test",
                "test.work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null,
                null,
                null,
                null,
                null,
                List.of());
    }

    private ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity(
            Set<String> permissions) {
        return new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                permissions, Set.of(), List.of(), List.of(), "auth-test-revision");
    }
}
