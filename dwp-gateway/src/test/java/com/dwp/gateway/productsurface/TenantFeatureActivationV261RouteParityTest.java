package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV261RouteParityTest {

    private static final Set<String> HRM009_ROUTES = Set.of(
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data",
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data",
            "route.hcm.operations.certificate-issue.action");
    private static final Set<String> HRM009_QUERY_ROUTES = Set.of(
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data",
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data");

    @Test
    void v261DefaultOffBindingsMatchTheExactGatewayEnforcementBoundary() throws Exception {
        Path root = repositoryRoot();
        String migration = Files.readString(root.resolve(
                "dwp-auth-server/src/main/resources/db/migration/"
                        + "V261__declare_hris_r7_batch03_hrm009_v52_authorization_seed.sql"));
        assertThat(migration)
                .contains("'HRIS_HRM_CERTIFICATE_WORKBENCH'")
                .contains("52, 'OFF'")
                .doesNotContain("INSERT INTO auth_product_feature_activation_changes")
                .doesNotContain("INSERT INTO com_tenant_feature_enrollment_changes");
        HRM009_ROUTES.forEach(route -> assertThat(migration).contains("'" + route + "'"));

        assertThat(privateSet("TENANT_FEATURE_GOVERNED_ROUTE_KEYS"))
                .containsAll(HRM009_ROUTES);
        assertThat(privateSet("HRM009_ENTRY_QUERY_ROUTE_KEYS"))
                .containsExactlyInAnyOrderElementsOf(HRM009_QUERY_ROUTES);
        assertThat(privateSet("HRM009_OPERATIONS_POPULATION_KINDS"))
                .containsExactlyInAnyOrder(
                        "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT");

        GeneratedProductRouteCatalog previous = catalog(root, 51);
        GeneratedProductRouteCatalog current = catalog(root, 52);
        assertThat(previous.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .doesNotContainAnyElementsOf(HRM009_ROUTES);
        assertThat(current.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .containsAll(HRM009_ROUTES);
    }

    private static Set<String> privateSet(String name) throws Exception {
        Field field = ProductSurfaceDecisionContextFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> value = (Set<String>) field.get(null);
        return value;
    }

    private static GeneratedProductRouteCatalog catalog(Path root, int version) {
        return new GeneratedProductRouteCatalog(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new org.springframework.core.io.FileSystemResource(
                        root.resolve("contracts/product-authorization/"
                                + "product-surfaces-v1.bundle-v" + version + ".json")));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("dwp-auth-server"))) return current;
        return current.getParent();
    }
}
