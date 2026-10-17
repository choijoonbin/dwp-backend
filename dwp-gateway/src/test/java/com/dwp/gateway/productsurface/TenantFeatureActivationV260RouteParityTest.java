package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV260RouteParityTest {
    private static final Set<String> HRM_NETWORK_ROUTES = Set.of(
            "route.hcm.team.people-changes.page",
            "route.hcm.team.people-changes.data",
            "route.hcm.operations.people-appointments.page",
            "route.hcm.operations.people-appointments.data",
            "route.hcm.configuration.people-employment.page",
            "route.hcm.configuration.people-employment.data");
    private static final Set<String> SYS_AUTHORIZATION_ONLY_ROUTES = Set.of(
            "route.hcm.configuration.workflow.page",
            "route.hcm.team.delegation.page");

    @Test
    void v260DefaultOffBindingsMatchGatewayNetworkEnforcementBoundary() throws Exception {
        String migration = Files.readString(repositoryRoot().resolve(
                "dwp-auth-server/src/main/resources/db/migration/"
                        + "V260__declare_hris_r7_batch02_v51_authorization_seed.sql"));
        assertThat(migration)
                .contains("'HRIS_HRM_APPOINTMENT_WORKBENCH'")
                .contains("'HRIS_APPROVAL_WORKFLOW_AND_DELEGATION_ADAPTERS'")
                .contains("51, 'OFF'")
                .doesNotContain("INSERT INTO auth_product_feature_activation_changes")
                .doesNotContain("INSERT INTO com_tenant_feature_enrollment_changes");
        HRM_NETWORK_ROUTES.forEach(route -> assertThat(migration).contains("'" + route + "'"));
        SYS_AUTHORIZATION_ONLY_ROUTES.forEach(
                route -> assertThat(migration).contains("'" + route + "'"));

        Field field = ProductSurfaceDecisionContextFilter.class
                .getDeclaredField("HRM007_ENTRY_QUERY_ROUTE_KEYS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> compiled = (Set<String>) field.get(null);
        assertThat(compiled).containsExactlyInAnyOrderElementsOf(HRM_NETWORK_ROUTES);

        GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new org.springframework.core.io.FileSystemResource(
                        repositoryRoot().resolve("contracts/product-authorization/"
                                + "product-surfaces-v1.bundle-v51.json")));
        assertThat(catalog.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .containsAll(HRM_NETWORK_ROUTES)
                .doesNotContainAnyElementsOf(SYS_AUTHORIZATION_ONLY_ROUTES);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("dwp-auth-server"))) return current;
        return current.getParent();
    }
}
