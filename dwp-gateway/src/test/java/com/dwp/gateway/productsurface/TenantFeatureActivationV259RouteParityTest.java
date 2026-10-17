package com.dwp.gateway.productsurface;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TenantFeatureActivationV259RouteParityTest {

    private static final Pattern V259_BINDING = Pattern.compile(
            "\\('([^']+)',\\s*'([^']+)',\\s*(\\d+),\\s*'([^']+)',\\s*'([^']+)',"
                    + "\\s*(\\d+),\\s*'(PAGE|DATA|ACTION)',\\s*'(PRODUCT)',\\s*'([^']+)'\\)");
    private static final Pattern JAVA_ROUTE = Pattern.compile("\"(route\\.hcm\\.[^\"]+)\"");

    @Test
    void v259RouteBindingsExactlyMatchGatewayAlwaysEvaluateClosedSet() throws Exception {
        Path root = repositoryRoot();
        String migration = Files.readString(root.resolve(
                "dwp-auth-server/src/main/resources/db/migration/"
                        + "V259__govern_product_feature_activation_and_tenant_enrollment.sql"));
        String gatewaySource = Files.readString(root.resolve(
                "dwp-gateway/src/main/java/com/dwp/gateway/filter/"
                        + "ProductSurfaceDecisionContextFilter.java"));

        List<SeedBinding> seeds = parseSeedBindings(section(
                migration,
                "INSERT INTO sys_product_feature_route_bindings",
                "COMMENT ON TABLE sys_product_feature_activation_policies"));
        List<RouteTuple> migrationRoutes = seeds.stream()
                .map(seed -> new RouteTuple(seed.routeKey(), seed.routeKind()))
                .toList();
        List<RouteTuple> gatewayRoutes = parseGatewayRoutes(section(
                gatewaySource,
                "private static final Set<String> TENANT_FEATURE_GOVERNED_ROUTE_KEYS",
                "private static final Set<String> TIM_WAVE2_ROUTE_KEYS"));

        assertThat(seeds).hasSize(16);
        assertThat(seeds.stream().map(SeedBinding::routeKey).toList())
                .doesNotHaveDuplicates();
        assertThat(seeds.stream().map(SeedBinding::featureKey).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "HRIS_ORGANIZATION_POSITIONS",
                        "HRIS_OPERATIONS_EXCEPTION_CENTER",
                        "HRIS_PAYROLL_RUN_PREPARE",
                        "HRIS_TIME_CLOSE_HANDOFF");
        assertThat(seeds).allSatisfy(seed -> {
            assertThat(seed.policyVersion()).isEqualTo(1L);
            assertThat(seed.productKey()).isEqualTo("hcm");
            assertThat(seed.appResourceKey()).isEqualTo("APP.HCM");
            assertThat(seed.minimumBundleVersion()).isEqualTo(50L);
            assertThat(seed.subjectType()).isEqualTo("PRODUCT");
            assertThat(seed.surfaceKey()).isIn("hcm.operations", "hcm.management");
        });

        assertThat(gatewayRoutes).hasSize(16).doesNotHaveDuplicates();
        assertThat(gatewayRoutes)
                .as("V259 binding order and semantics must match the Gateway closed set")
                .containsExactlyElementsOf(migrationRoutes);

        Field field = ProductSurfaceDecisionContextFilter.class
                .getDeclaredField("TENANT_FEATURE_GOVERNED_ROUTE_KEYS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> compiled = (Set<String>) field.get(null);
        assertThat(compiled)
                .hasSize(16)
                .containsExactlyInAnyOrderElementsOf(
                        migrationRoutes.stream().map(RouteTuple::routeKey).toList());
    }

    private static List<SeedBinding> parseSeedBindings(String source) {
        List<SeedBinding> result = new ArrayList<>();
        Matcher matcher = V259_BINDING.matcher(source);
        while (matcher.find()) {
            result.add(new SeedBinding(
                    matcher.group(1),
                    matcher.group(2),
                    Long.parseLong(matcher.group(3)),
                    matcher.group(4),
                    matcher.group(5),
                    Long.parseLong(matcher.group(6)),
                    matcher.group(7),
                    matcher.group(8),
                    matcher.group(9)));
        }
        return List.copyOf(result);
    }

    private static List<RouteTuple> parseGatewayRoutes(String source) {
        List<RouteTuple> result = new ArrayList<>();
        Matcher matcher = JAVA_ROUTE.matcher(source);
        Set<String> unique = new LinkedHashSet<>();
        while (matcher.find()) {
            String routeKey = matcher.group(1);
            assertThat(unique.add(routeKey)).as("duplicate Gateway route %s", routeKey).isTrue();
            result.add(new RouteTuple(routeKey, kind(routeKey)));
        }
        return List.copyOf(result);
    }

    private static String kind(String routeKey) {
        if (routeKey.endsWith(".page")) return "PAGE";
        if (routeKey.endsWith(".data")) return "DATA";
        if (routeKey.endsWith(".action")) return "ACTION";
        throw new AssertionError("Unsupported governed route kind: " + routeKey);
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(start).as("start marker %s", startMarker).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker %s", endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("dwp-auth-server"))) return current;
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("dwp-auth-server"))) return parent;
        throw new AssertionError("Cannot locate the repository root from " + current);
    }

    private record RouteTuple(String routeKey, String routeKind) {}

    private record SeedBinding(
            String routeKey,
            String featureKey,
            long policyVersion,
            String productKey,
            String appResourceKey,
            long minimumBundleVersion,
            String routeKind,
            String subjectType,
            String surfaceKey) {}
}
