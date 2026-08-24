package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedProductRouteCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeneratedProductRouteCatalog catalog = catalog(3);

    @Test
    void officialBundleAuthorityEndpointTopologyIsV1NoneAndV2V3ExactOne() {
        assertThat(catalog(1).authorityEndpointsForTesting()).isEmpty();
        for (int version : List.of(2, 3)) {
            assertThat(catalog(version).authorityEndpointsForTesting())
                    .singleElement()
                    .satisfies(endpoint -> {
                        assertThat(endpoint.endpointKey())
                                .isEqualTo("product-surface-step-up-challenge.issue");
                        assertThat(endpoint.method()).isEqualTo("POST");
                        assertThat(endpoint.publicPath())
                                .isEqualTo("/api/auth/product-surface-step-up-challenges");
                        assertThat(endpoint.expectedDecisionRevisionHeader())
                                .isEqualTo("X-DWP-Expected-Decision-Revision");
                    });
        }
    }

    @Test
    void declaredAuthorityEndpointUsesAStrictClosedShape() throws Exception {
        ObjectNode unknownField = bundle(3);
        ((ObjectNode) unknownField.withArray("authorityEndpoints").get(0))
                .put("unknownAuthorityField", "must-fail");
        assertInvalid(unknownField);

        ObjectNode empty = bundle(3);
        empty.set("authorityEndpoints", objectMapper.createArrayNode());
        assertInvalid(empty);

        ObjectNode duplicate = bundle(3);
        ArrayNode endpoints = duplicate.withArray("authorityEndpoints");
        endpoints.add(endpoints.get(0).deepCopy());
        assertInvalid(duplicate);
    }

    @Test
    void collapsesOnlyGeneratorValidatedApprovalWireEquivalence() {
        var tasks = catalog.match("GET", "/api/approvals/v1/tasks", null);
        var requests = catalog.match("GET", "/api/approvals/v1/requests", null);

        assertThat(tasks.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(tasks.routes()).hasSize(2);
        assertThat(tasks.uniqueRoute().authorizationEquivalenceKey())
                .isEqualTo("wire-authority.approvals.work.tasks-list.v1");
        assertThat(requests.routes()).hasSize(4);
        assertThat(requests.uniqueRoute()).isNotNull();
    }

    @Test
    void resolvesNonEquivalentWorkflowViewsByGeneratedQueryConstraint() {
        var page = catalog.match(
                "GET", "/api/approvals/v1/admin/workflows", null);
        var reference = catalog.match(
                "GET", "/api/approvals/v1/admin/workflows", "view=reference");
        var duplicate = catalog.match(
                "GET", "/api/approvals/v1/admin/workflows",
                "view=reference&view=reference");

        assertThat(page.uniqueRoute().routeContractKey())
                .isEqualTo("route.approvals.admin.workflows.page");
        assertThat(reference.uniqueRoute().routeContractKey())
                .isEqualTo("route.approvals.admin.forms-workflow-reference.data");
        assertThat(duplicate.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void appliesFixedParametersBeforeChoosingTheProductOwner() {
        var approval = catalog.match(
                "PUT", "/api/platform/v1/home-preferences/surfaces/approval-home", null);
        var hcm = catalog.match(
                "PUT", "/api/platform/v1/home-preferences/surfaces/hcm-home", null);
        var spoofed = catalog.match(
                "PUT", "/api/platform/v1/home-preferences/surfaces/services-home", null);

        assertThat(approval.uniqueRoute().productKey()).isEqualTo("approvals");
        assertThat(hcm.uniqueRoute().productKey()).isEqualTo("hcm");
        assertThat(spoofed.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void distinguishesUnknownProductNamespaceFromNonProductTraffic() {
        assertThat(catalog.match("GET", "/api/approvals/v1/not-registered", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(catalog.match("GET", "/api/auth/me", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        assertThat(catalog.match("DELETE", "/api/approvals/v1/tasks", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void exportsTheCanonicalIssuerWithoutTreatingItAsDomainPepTraffic() {
        var endpoint = catalog.authorityEndpoint(
                "POST", "/api/auth/product-surface-step-up-challenges");

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.requiresAuthentication()).isTrue();
        assertThat(endpoint.requiresCsrf()).isTrue();
        assertThat(endpoint.expectedDecisionRevisionHeader())
                .isEqualTo("X-DWP-Expected-Decision-Revision");
        assertThat(catalog.match(
                "POST", endpoint.publicPath(), null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
    }

    @Test
    void approvalV3Preserves49BindingsAndCollapsesTo43WireAuthorities() {
        Set<String> wireBindings = new HashSet<>();
        Set<String> authorities = new HashSet<>();
        int bindings = 0;
        for (GeneratedProductRouteCatalog.Route route : catalog.routesForTesting()) {
            if (!"approvals".equals(route.productKey())) continue;
            bindings++;
            wireBindings.add(route.method() + " " + route.publicPath());
            String equivalence = route.authorizationEquivalenceKey();
            authorities.add(equivalence == null
                    ? route.method() + " " + route.publicPath() + " " + route.queryConstraints()
                    : route.method() + " " + route.publicPath() + " " + equivalence);
        }
        assertThat(bindings).isEqualTo(49);
        assertThat(wireBindings).hasSize(43);
        // Two non-equivalent workflow wire resources are intentionally split by `view`.
        assertThat(authorities).hasSize(45);
    }

    private GeneratedProductRouteCatalog catalog(int version) {
        return new GeneratedProductRouteCatalog(
                objectMapper,
                new FileSystemResource("../contracts/product-authorization/"
                        + "product-surfaces-v1.bundle-v" + version + ".json"));
    }

    private ObjectNode bundle(int version) throws IOException {
        try (var input = new FileSystemResource(
                "../contracts/product-authorization/product-surfaces-v1.bundle-v"
                        + version + ".json").getInputStream()) {
            return (ObjectNode) objectMapper.readTree(input);
        }
    }

    private void assertInvalid(ObjectNode bundle) throws Exception {
        byte[] value = objectMapper.writeValueAsBytes(bundle);
        assertThatThrownBy(() -> new GeneratedProductRouteCatalog(
                objectMapper, new ByteArrayResource(value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority endpoint");
    }
}
