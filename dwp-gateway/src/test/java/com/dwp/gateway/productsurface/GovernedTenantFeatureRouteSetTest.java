package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedTenantFeatureRouteSetTest {

    private static final String AUTHORITY_DECISION_SHA256 =
            "fda6f51622c843b0c0da35f73b550cd6bd33c0ed190ee19058ac15f0e0502284";
    private static final String SORTED_ROUTE_SET_SHA256 =
            "d7d5d971f1d16bb64916a54bdaf70f4d2cd1c188b1e01697e53ea29cab645f9f";
    private static final Set<String> EXPECTED = Set.of(
            "route.hcm.operations.organization-positions.page",
            "route.hcm.operations.organization-graph.data",
            "route.hcm.configuration.settings-operations.page",
            "route.hcm.operations.exception.data",
            "route.hcm.operations.exception-detail.data",
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
            "route.hcm.operations.payroll-run.action",
            "route.hcm.operations.time-command-center.page",
            "route.hcm.operations.time-daily-close.page",
            "route.hcm.operations.time-monthly-close.page",
            "route.hcm.operations.time-close-validate.action",
            "route.hcm.operations.time-close.action",
            "route.hcm.operations.time-reopen.action",
            "route.hcm.operations.time-payroll-handoff-create.action",
            "route.hcm.operations.time-payroll-handoff.data");

    @Test
    void closedSetIsExactAndHashBoundWithoutTenantOrEnvironmentAllowlists() throws Exception {
        Field field = ProductSurfaceDecisionContextFilter.class
                .getDeclaredField("TENANT_FEATURE_GOVERNED_ROUTE_KEYS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> actual = (Set<String>) field.get(null);

        assertThat(AUTHORITY_DECISION_SHA256).hasSize(64);
        assertThat(actual)
                .as("routes pinned to activation decision %s", AUTHORITY_DECISION_SHA256)
                .hasSize(16)
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
        assertThat(digest(actual.stream().sorted().toList()))
                .isEqualTo(SORTED_ROUTE_SET_SHA256);
    }

    private String digest(List<String> routes) throws Exception {
        byte[] value = MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", routes).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(value);
    }
}
