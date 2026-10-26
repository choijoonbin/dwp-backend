package com.dwp.gateway.productsurface;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Closed SYS-008 browser-to-Platform operation boundary. This class does not mount routes. */
public final class Sys008GatewayRoutes {
    public static final String PURPOSE = "HRIS_INTEGRATION_ADMIN";
    public static final String FEATURE_KEY = "HRIS_INTEGRATION_CONTROL_PLANE";

    public static final List<Binding> BINDINGS = List.of(
            binding("IAQ-057", "IAQ.OP.057", "GET",
                    "/api/platform/v1/hris/workbench/settings-integrations",
                    "/v1/hris/workbench/settings-integrations",
                    "route.hcm.configuration.settings-integrations.page", "PAGE",
                    "hcm.configuration.workbench.view", false),
            binding("IAQ-062", "IAQ.OP.062", "GET",
                    "/api/platform/v1/hris/workbench/dwp-integrations",
                    "/v1/hris/workbench/dwp-integrations",
                    "route.hcm.configuration.dwp-integrations.data", "DATA",
                    "hcm.configuration.workbench.view", false),
            binding("SYS-API-021.GET", "PEP-SYS-022", "GET",
                    "/api/platform/v1/admin/hris/connectors",
                    "/v1/admin/hris/connectors",
                    "route.hcm.configuration.connector-catalog.data", "DATA",
                    "hcm.integration.definition.update", false),
            binding("SYS-API-021.POST", "PEP-SYS-023", "POST",
                    "/api/platform/v1/admin/hris/connectors",
                    "/v1/admin/hris/connectors",
                    "route.hcm.configuration.connector-definition-create.action", "ACTION",
                    "hcm.integration.definition.update", false),
            binding("SYS-API-022", "PEP-SYS-024", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/test",
                    "/v1/admin/hris/connectors/{connectorId}/test",
                    "route.hcm.configuration.connector-test.action", "ACTION",
                    "hcm.integration.definition.update", false),
            binding("SYS-API-023", "PEP-SYS-025", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/dry-runs",
                    "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/dry-runs",
                    "route.hcm.configuration.connector-mapping-dry-run.action", "ACTION",
                    "hcm.integration.definition.update", true),
            binding("SYS-API-024", "PEP-SYS-026", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/executions",
                    "/v1/admin/hris/connectors/{connectorId}/executions",
                    "route.hcm.configuration.connector-execution-create.action", "ACTION",
                    "hcm.integration.control-plane.execute", false),
            binding("SYS-API-025", "PEP-SYS-027", "GET",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}",
                    "/v1/admin/hris/connector-executions/{executionId}",
                    "route.hcm.configuration.connector-execution-detail.data", "DATA",
                    "hcm.integration.control-plane.execute", false),
            binding("SYS-API-057", "PEP-SYS-059", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/validate",
                    "/v1/admin/hris/connectors/{connectorId}/validate",
                    "route.hcm.configuration.connector-definition-validate.action", "ACTION",
                    "hcm.integration.definition.update", true),
            binding("SYS-API-058", "PEP-SYS-060", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/submit",
                    "/v1/admin/hris/connectors/{connectorId}/submit",
                    "route.hcm.configuration.connector-definition-submit.action", "ACTION",
                    "hcm.integration.definition.update", true),
            binding("SYS-API-059", "PEP-SYS-061", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/approve",
                    "/v1/admin/hris/connectors/{connectorId}/approve",
                    "route.hcm.configuration.connector-definition-approve.action", "ACTION",
                    "hcm.integration.control-plane.execute", true),
            binding("SYS-API-060", "PEP-SYS-062", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/activate",
                    "/v1/admin/hris/connectors/{connectorId}/activate",
                    "route.hcm.configuration.connector-definition-activate.action", "ACTION",
                    "hcm.integration.control-plane.execute", true),
            binding("SYS-API-061", "PEP-SYS-063", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings",
                    "/v1/admin/hris/connectors/{connectorId}/mappings",
                    "route.hcm.configuration.connector-mapping-version-create.action", "ACTION",
                    "hcm.integration.definition.update", false),
            binding("SYS-API-062", "PEP-SYS-064", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/validate",
                    "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/validate",
                    "route.hcm.configuration.connector-mapping-version-validate.action", "ACTION",
                    "hcm.integration.definition.update", true),
            binding("SYS-API-063", "PEP-SYS-065", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/submit",
                    "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/submit",
                    "route.hcm.configuration.connector-mapping-version-submit.action", "ACTION",
                    "hcm.integration.definition.update", true),
            binding("SYS-API-064", "PEP-SYS-066", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/approve",
                    "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/approve",
                    "route.hcm.configuration.connector-mapping-version-approve.action", "ACTION",
                    "hcm.integration.control-plane.execute", true),
            binding("SYS-API-065", "PEP-SYS-067", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/activate",
                    "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/activate",
                    "route.hcm.configuration.connector-mapping-version-activate.action", "ACTION",
                    "hcm.integration.control-plane.execute", true),
            binding("SYS-API-066", "PEP-SYS-068", "POST",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}/reconcile",
                    "/v1/admin/hris/connector-executions/{executionId}/reconcile",
                    "route.hcm.configuration.connector-execution-reconcile.action", "ACTION",
                    "hcm.integration.control-plane.execute", true),
            binding("SYS-API-067", "PEP-SYS-069", "POST",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}/retry",
                    "/v1/admin/hris/connector-executions/{executionId}/retry",
                    "route.hcm.configuration.connector-execution-retry.action", "ACTION",
                    "hcm.integration.control-plane.execute", true),
            binding("SYS-API-068", "PEP-SYS-070", "POST",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}/cancel",
                    "/v1/admin/hris/connector-executions/{executionId}/cancel",
                    "route.hcm.configuration.connector-execution-cancel.action", "ACTION",
                    "hcm.integration.control-plane.execute", true));

    private static final Map<String, Binding> BY_ROUTE_KEY = BINDINGS.stream()
            .collect(Collectors.toUnmodifiableMap(Binding::routeContractKey, Function.identity()));
    private static final Map<String, StepUpTarget> COMMAND_BOUND_STEP_UP = Map.of(
            "route.hcm.configuration.connector-definition-approve.action",
                    new StepUpTarget("HRIS_CONNECTOR_DEFINITION", "connectorId"),
            "route.hcm.configuration.connector-definition-activate.action",
                    new StepUpTarget("HRIS_CONNECTOR_DEFINITION", "connectorId"),
            "route.hcm.configuration.connector-mapping-version-approve.action",
                    new StepUpTarget("HRIS_CONNECTOR_MAPPING_VERSION", "connectorId"),
            "route.hcm.configuration.connector-mapping-version-activate.action",
                    new StepUpTarget("HRIS_CONNECTOR_MAPPING_VERSION", "connectorId"),
            "route.hcm.configuration.connector-execution-reconcile.action",
                    new StepUpTarget("HRIS_CONNECTOR_EXECUTION", "executionId"),
            "route.hcm.configuration.connector-execution-retry.action",
                    new StepUpTarget("HRIS_CONNECTOR_EXECUTION", "executionId"),
            "route.hcm.configuration.connector-execution-cancel.action",
                    new StepUpTarget("HRIS_CONNECTOR_EXECUTION", "executionId"));
    public static final Set<String> ROUTE_KEYS = BY_ROUTE_KEY.keySet();

    private Sys008GatewayRoutes() { }

    public static Binding binding(String routeContractKey) {
        return BY_ROUTE_KEY.get(routeContractKey);
    }

    public static boolean requiresExpectedObjectRevision(String routeContractKey) {
        Binding binding = binding(routeContractKey);
        return binding != null && binding.casCommand();
    }

    public static StepUpTarget commandBoundStepUp(String routeContractKey) {
        return COMMAND_BOUND_STEP_UP.get(routeContractKey);
    }

    /** Claims all four exact public families so an unknown sibling cannot fall through. */
    public static boolean claimsPath(String path) {
        return isFamily(path, "/api/platform/v1/hris/workbench/settings-integrations")
                || isFamily(path, "/api/platform/v1/hris/workbench/dwp-integrations")
                || isFamily(path, "/api/platform/v1/admin/hris/connectors")
                || isFamily(path, "/api/platform/v1/admin/hris/connector-executions");
    }

    public static boolean matches(
            GeneratedProductRouteCatalog.Route route,
            String requestMethod,
            String requestPath) {
        if (route == null) return false;
        Binding binding = binding(route.routeContractKey());
        return binding != null
                && binding.method().equals(requestMethod)
                && binding.method().equals(route.method())
                && binding.browserPath().equals(route.publicPath())
                && binding.routeKind().equals(route.routeKind())
                && binding.stateChanging() == route.stateChanging()
                && "hcm".equals(route.productKey())
                && "hcm.management".equals(route.surfaceKey())
                && route.exactPattern() != null
                && requestPath != null
                && route.exactPattern().matcher(requestPath).matches()
                && binding.pathPattern().matcher(requestPath).matches();
    }

    private static boolean isFamily(String path, String root) {
        return path != null && (root.equals(path) || path.startsWith(root + "/"));
    }

    private static Binding binding(
            String api,
            String pep,
            String method,
            String browserPath,
            String servicePath,
            String routeContractKey,
            String routeKind,
            String capability,
            boolean casCommand) {
        boolean stateChanging = "ACTION".equals(routeKind);
        return new Binding(api, pep, method, browserPath, servicePath, routeContractKey,
                routeKind, capability, stateChanging, casCommand, pathPattern(browserPath));
    }

    private static Pattern pathPattern(String template) {
        String quoted = Pattern.quote(template
                .replace("{connectorId}", "@CONNECTOR_ID@")
                .replace("{executionId}", "@EXECUTION_ID@")
                .replace("{version}", "@VERSION@"));
        String uuid = "\\E[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\Q";
        String regex = quoted
                .replace("@CONNECTOR_ID@", uuid)
                .replace("@EXECUTION_ID@", uuid)
                .replace("@VERSION@", "\\E[1-9][0-9]*\\Q");
        return Pattern.compile("^" + regex + "$");
    }

    public record Binding(
            String api,
            String pep,
            String method,
            String browserPath,
            String servicePath,
            String routeContractKey,
            String routeKind,
            String capability,
            boolean stateChanging,
            boolean casCommand,
            Pattern pathPattern) {
    }

    public record StepUpTarget(String targetType, String targetIdPathParameter) {
    }
}
