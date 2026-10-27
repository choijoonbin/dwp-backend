package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.auth.service.ProductAuthorizationContractRules.nullSafe;
import static com.dwp.services.auth.service.ProductAuthorizationContractRules.nullSafeMap;

/** Closed wire/profile exceptions that use an existing elevated session. */
final class ProductAuthorizationGenericStepUpRoutes {
    private ProductAuthorizationGenericStepUpRoutes() {
    }

    static boolean matches(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            long bundleVersion) {
        return isExactGrandfatheredGenericStepUpRoute(route)
                || isExactV49GenericStepUpWithoutFreshCommandRoute(route)
                || isExactV56AutomationReadRoute(route)
                || bundleVersion >= 60
                        && isExactV60IntegrationControlPlaneGenericSessionRoute(route)
                || ((PayrollV49ElevatedAuthorityPolicy.STATUTORY_READ_ROUTES.contains(
                        route.routeContractKey())
                        || PayrollV49ElevatedAuthorityPolicy.RETIREMENT_READ_ROUTES.contains(
                        route.routeContractKey()))
                        && PayrollV49ElevatedAuthorityPolicy.isExactRoute(
                                route, capabilities));
    }

    /**
     * SYS-008 uses the platform command-header contract for execution creation and
     * an existing elevated session for execution detail. Neither route issues a
     * fresh product-surface step-up challenge, so the registry must not synthesize
     * a {@link ProductAuthorizationContractDtos.StepUpCommandBinding}. Keep the
     * exception closed over the two exact v60 wire/profile shapes.
     */
    private static boolean isExactV60IntegrationControlPlaneGenericSessionRoute(
            ProductAuthorizationContractDtos.GovernedRoute route) {
        String key = route.routeContractKey();
        if (!Set.of(
                "route.hcm.configuration.connector-execution-create.action",
                "route.hcm.configuration.connector-execution-detail.data").contains(key)
                || route.subject() == null
                || !"PRODUCT".equals(route.subject().type())
                || !"hcm".equals(route.subject().productKey())
                || !"hcm.management".equals(route.subject().surfaceKey())
                || !"Platform + HCM + Security".equals(route.owner())
                || route.policyVersion() != 1
                || !"ACTIVE".equals(route.lifecycleState())
                || route.gatewayApiBindings() == null
                || route.gatewayApiBindings().size() != 1
                || route.servicePepBindings() == null
                || route.servicePepBindings().size() != 1
                || route.accessProfiles() == null
                || route.accessProfiles().size() != 1) {
            return false;
        }
        var gateway = route.gatewayApiBindings().getFirst();
        var service = route.servicePepBindings().getFirst();
        var profile = route.accessProfiles().getFirst();
        var required = profile.requiredAccess();
        String bindingKey = key + ".binding.01";
        if (!bindingKey.equals(gateway.bindingKey())
                || !bindingKey.equals(service.bindingKey())
                || !nullSafeMap(gateway.pathParameterConstraints()).isEmpty()
                || !nullSafeMap(gateway.queryParameterConstraints()).isEmpty()
                || !nullSafeMap(service.pathParameterConstraints()).isEmpty()
                || !nullSafeMap(service.queryParameterConstraints()).isEmpty()
                || !"integration-control-plane-executor".equals(profile.profileKey())
                || profile.precedence() != 300
                || !List.of("CONFIG_SCOPE", "OBJECT")
                        .equals(profile.targetBindingKinds())
                || !List.of(
                        "predicate.hcm-settings-operations-authority.v1",
                        "predicate.hcm-reference-configuration-authority.v1")
                        .equals(profile.predicatePolicyKeys())
                || required == null
                || !"CAPABILITY".equals(required.type())
                || !"hcm.integration.control-plane.execute"
                        .equals(required.capabilityContractKey())) {
            return false;
        }
        if (key.endsWith("connector-execution-create.action")) {
            return exactRouteWire(route, "hcm.management", "ACTION", false, "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/executions",
                    "platform",
                    "/v1/admin/hris/connectors/{connectorId}/executions")
                    && List.of("ELEVATED").equals(profile.activeAccessModes())
                    && !profile.readOnly()
                    && nullSafe(profile.responseProjectionBindings()).isEmpty();
        }
        return exactRouteWire(route, "hcm.management", "DATA", true, "GET",
                "/api/platform/v1/admin/hris/connector-executions/{executionId}",
                "platform",
                "/v1/admin/hris/connector-executions/{executionId}")
                && List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes())
                && profile.readOnly()
                && exactProjection(profile, bindingKey,
                "sys008.sys-api-025.execution-detail.fields.v1",
                "SysApi025GetResponseV1");
    }

    private static boolean isExactV56AutomationReadRoute(
            ProductAuthorizationContractDtos.GovernedRoute route) {
        String key = route.routeContractKey();
        if (!Set.of(
                "route.hcm.configuration.automation-catalog.data",
                "route.hcm.configuration.automation-run-detail.data").contains(key)
                || route.subject() == null
                || !"PRODUCT".equals(route.subject().type())
                || !"hcm".equals(route.subject().productKey())
                || route.gatewayApiBindings() == null
                || route.gatewayApiBindings().size() != 1
                || route.servicePepBindings() == null
                || route.servicePepBindings().size() != 1
                || route.accessProfiles() == null) {
            return false;
        }
        String bindingKey = key + ".binding.01";
        if (!bindingKey.equals(route.gatewayApiBindings().getFirst().bindingKey())
                || !bindingKey.equals(route.servicePepBindings().getFirst().bindingKey())) {
            return false;
        }
        if (key.endsWith("automation-catalog.data")) {
            return exactRouteWire(route, "hcm.management", "DATA", true, "GET",
                    "/api/platform/v1/admin/hris/automations", "platform",
                    "/v1/admin/hris/automations")
                    && route.accessProfiles().size() == 3
                    && exactCapabilityProfile(route.accessProfiles().get(0),
                    "automation-author", 300, true, List.of("CONFIG_SCOPE"),
                    "predicate.hcm-settings-operations-authority.v1",
                    "hcm.configuration.policy.update")
                    && exactProjection(route.accessProfiles().get(0), bindingKey,
                    "sys006.iaq-059.automation-author.fields.v1",
                    "SysApi017GetResponseV1")
                    && exactCapabilityProfile(route.accessProfiles().get(1),
                    "automation-publisher", 200, true, List.of("CONFIG_SCOPE"),
                    "predicate.hcm-settings-operations-authority.v1",
                    "hcm.configuration.policy.publish")
                    && exactProjection(route.accessProfiles().get(1), bindingKey,
                    "sys006.iaq-059.automation-publisher.fields.v1",
                    "SysApi017GetResponseV1")
                    && exactCapabilityProfile(route.accessProfiles().get(2),
                    "automation-executor", 100, true, List.of("CONFIG_SCOPE"),
                    "predicate.hcm-settings-operations-authority.v1",
                    "hcm.integration.execute")
                    && exactProjection(route.accessProfiles().get(2), bindingKey,
                    "sys006.iaq-059.automation-executor.fields.v1",
                    "SysApi017GetResponseV1");
        }
        var profile = route.accessProfiles().size() == 1
                ? route.accessProfiles().getFirst() : null;
        var access = profile == null ? null : profile.requiredAccess();
        return exactRouteWire(route, "hcm.management", "DATA", true, "GET",
                "/api/platform/v1/admin/hris/automation-runs/{runId}", "platform",
                "/v1/admin/hris/automation-runs/{runId}")
                && profile != null
                && "automation-executor".equals(profile.profileKey())
                && profile.precedence() == 300
                && List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes())
                && profile.readOnly()
                && List.of("CONFIG_SCOPE", "OBJECT").equals(profile.targetBindingKinds())
                && List.of(
                        "predicate.hcm-settings-operations-authority.v1",
                        "predicate.hcm-reference-configuration-authority.v1")
                        .equals(profile.predicatePolicyKeys())
                && access != null
                && "CAPABILITY".equals(access.type())
                && "hcm.integration.execute".equals(access.capabilityContractKey())
                && exactProjection(profile, bindingKey,
                "sys006.iaq-059.automation-executor.fields.v1",
                "SysApi019GetResponseV1");
    }

    private static boolean isExactV49GenericStepUpWithoutFreshCommandRoute(
            ProductAuthorizationContractDtos.GovernedRoute route) {
        String key = route.routeContractKey();
        if (!Set.of(
                "route.hcm.operations.payroll-population.page",
                "route.hcm.operations.payroll-support.data",
                "route.hcm.operations.payroll-run.data",
                "route.hcm.operations.time-close-validate.action",
                "route.hcm.operations.time-payroll-handoff-create.action",
                "route.hcm.operations.time-payroll-handoff.data").contains(key)
                || !"hcm.operations".equals(route.navigationContextId())
                || route.subject() == null
                || !"PRODUCT".equals(route.subject().type())
                || !"hcm".equals(route.subject().productKey())
                || !"hcm.operations".equals(route.subject().surfaceKey())
                || route.accessProfiles() == null || route.accessProfiles().size() != 1
                || route.gatewayApiBindings() == null || route.gatewayApiBindings().size() != 1
                || route.servicePepBindings() == null || route.servicePepBindings().size() != 1) {
            return false;
        }
        var profile = route.accessProfiles().getFirst();
        var required = profile.requiredAccess();
        var gateway = route.gatewayApiBindings().getFirst();
        var service = route.servicePepBindings().getFirst();
        String bindingKey = key + ".binding.01";
        if (profile.precedence() != 300 || required == null
                || !bindingKey.equals(gateway.bindingKey())
                || !bindingKey.equals(service.bindingKey())) {
            return false;
        }
        if ("route.hcm.operations.payroll-run.data".equals(key)) {
            var projections = nullSafe(profile.responseProjectionBindings());
            return "DATA".equals(route.routeKind())
                    && Boolean.TRUE.equals(route.sideEffectFree())
                    && "payroll-run-viewer".equals(profile.profileKey())
                    && List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes())
                    && profile.readOnly()
                    && List.of("OBJECT", "TARGET_POPULATION")
                            .equals(profile.targetBindingKinds())
                    && List.of("predicate.hcm-payroll-run-authority.v1")
                            .equals(profile.predicatePolicyKeys())
                    && "CAPABILITY_EXPRESSION".equals(required.type())
                    && "ANY".equals(required.mode())
                    && List.of(
                            "hcm.operations.payroll-run.execute",
                            "hcm.operations.payroll-support.view")
                            .equals(required.capabilityContractKeys())
                    && projections.size() == 1
                    && bindingKey.equals(projections.getFirst().apiBindingKey())
                    && (key + ".payroll-run-viewer.projection.v1")
                            .equals(projections.getFirst().projectionPolicyKey())
                    && (key + ".response.v1")
                            .equals(projections.getFirst().responseSchemaKey())
                    && "GET".equals(gateway.method())
                    && "/api/payroll/v1/runs/{runId}".equals(gateway.path())
                    && "payroll".equals(service.serviceKey())
                    && "GET".equals(service.method())
                    && "/v1/runs/{runId}".equals(service.path());
        }
        if (key.startsWith("route.hcm.operations.payroll-")) {
            boolean page = key.endsWith("payroll-population.page");
            String expectedKind = page ? "PAGE" : "DATA";
            var projections = nullSafe(profile.responseProjectionBindings());
            return expectedKind.equals(route.routeKind())
                    && (page ? route.sideEffectFree() == null
                            : Boolean.TRUE.equals(route.sideEffectFree()))
                    && "payroll-support-viewer".equals(profile.profileKey())
                    && List.of("ELEVATED").equals(profile.activeAccessModes())
                    && profile.readOnly()
                    && List.of("TARGET_POPULATION").equals(profile.targetBindingKinds())
                    && List.of("predicate.hcm-payroll-population-authority.v1")
                            .equals(profile.predicatePolicyKeys())
                    && "CAPABILITY".equals(required.type())
                    && "hcm.operations.payroll-support.view"
                            .equals(required.capabilityContractKey())
                    && projections.size() == 1
                    && bindingKey.equals(projections.getFirst().apiBindingKey())
                    && (key + ".payroll-support-viewer.projection.v1")
                            .equals(projections.getFirst().projectionPolicyKey())
                    && "route.hcm.operations.payroll-support.data.response.v1"
                            .equals(projections.getFirst().responseSchemaKey())
                    && "GET".equals(gateway.method())
                    && "/api/payroll/v1/workbench/population".equals(gateway.path())
                    && "payroll".equals(service.serviceKey())
                    && "GET".equals(service.method())
                    && "/v1/workbench/population".equals(service.path());
        }
        boolean data = key.endsWith("time-payroll-handoff.data");
        String expectedGatewayPath;
        String expectedServicePath;
        if (data) {
            expectedGatewayPath = "/api/time/v1/payroll-handoffs/{handoffId}";
            expectedServicePath = "/v1/payroll-handoffs/{handoffId}";
        } else if (key.endsWith("time-close-validate.action")) {
            expectedGatewayPath = "/api/time/v1/close-periods/{periodId}/validations";
            expectedServicePath = "/v1/close-periods/{periodId}/validations";
        } else {
            expectedGatewayPath = "/api/time/v1/close-periods/{periodId}/payroll-handoffs";
            expectedServicePath = "/v1/close-periods/{periodId}/payroll-handoffs";
        }
        var projections = nullSafe(profile.responseProjectionBindings());
        return (data ? "DATA" : "ACTION").equals(route.routeKind())
                && (data ? Boolean.TRUE.equals(route.sideEffectFree())
                        : route.sideEffectFree() == null)
                && "time-close-operator".equals(profile.profileKey())
                && List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes())
                && profile.readOnly() == data
                && List.of("OBJECT", "TARGET_POPULATION").equals(
                        profile.targetBindingKinds())
                && List.of("predicate.hcm-time-period-authority.v1")
                        .equals(profile.predicatePolicyKeys())
                && "CAPABILITY".equals(required.type())
                && "hcm.operations.time.close".equals(required.capabilityContractKey())
                && (data
                        ? projections.size() == 1
                                && bindingKey.equals(
                                        projections.getFirst().apiBindingKey())
                                && (key + ".time-close-operator.projection.v1")
                                        .equals(projections.getFirst().projectionPolicyKey())
                                && "ClosedTimeResultV1".equals(
                                        projections.getFirst().responseSchemaKey())
                        : projections.isEmpty())
                && (data ? "GET" : "POST").equals(gateway.method())
                && expectedGatewayPath.equals(gateway.path())
                && "time".equals(service.serviceKey())
                && (data ? "GET" : "POST").equals(service.method())
                && expectedServicePath.equals(service.path());
    }

    private static boolean isExactGrandfatheredGenericStepUpRoute(
            ProductAuthorizationContractDtos.GovernedRoute route) {
        String key = route.routeContractKey();
        if (!Set.of(
                "route.hcm.configuration.configuration-version.data",
                "route.hcm.configuration.configurations.data",
                "route.hcm.operations.time-interpretation-run.action",
                "route.hcm.operations.time-interpretation-trace.data",
                "route.hcm.time.timecard-correction.action",
                "route.hcm.time.timecard.data").contains(key)
                || route.subject() == null
                || !"PRODUCT".equals(route.subject().type())
                || !"hcm".equals(route.subject().productKey())
                || route.gatewayApiBindings() == null
                || route.gatewayApiBindings().size() != 1
                || route.servicePepBindings() == null
                || route.servicePepBindings().size() != 1
                || route.accessProfiles() == null) {
            return false;
        }
        var gateway = route.gatewayApiBindings().getFirst();
        var service = route.servicePepBindings().getFirst();
        String bindingKey = key + ".binding.01";
        if (!bindingKey.equals(gateway.bindingKey())
                || !bindingKey.equals(service.bindingKey())) {
            return false;
        }
        return switch (key) {
            case "route.hcm.configuration.configuration-version.data" ->
                    exactRouteWire(route, "hcm.management", "DATA", true, "GET",
                            "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}",
                            "platform",
                            "/v1/admin/hris/configurations/{configId}/versions/{version}")
                            && route.accessProfiles().size() == 1
                            && exactExpressionProfile(route.accessProfiles().getFirst(),
                            "configuration-manager", 300, true,
                            List.of("CONFIG_SCOPE", "OBJECT"),
                            "predicate.hcm-reference-configuration-authority.v1",
                            List.of("hcm.configuration.policy.update",
                                    "hcm.configuration.policy.publish"))
                            && exactProjection(route.accessProfiles().getFirst(), bindingKey,
                            key + ".configuration-manager.projection.v1",
                            key + ".response.v1");
            case "route.hcm.configuration.configurations.data" ->
                    exactRouteWire(route, "hcm.management", "DATA", true, "GET",
                            "/api/platform/v1/admin/hris/configurations", "platform",
                            "/v1/admin/hris/configurations")
                            && route.accessProfiles().size() == 1
                            && exactExpressionProfile(route.accessProfiles().getFirst(),
                            "configuration-manager", 300, true, List.of("CONFIG_SCOPE"),
                            "predicate.hcm-reference-configuration-authority.v1",
                            List.of("hcm.configuration.policy.update",
                                    "hcm.configuration.policy.publish"))
                            && exactProjection(route.accessProfiles().getFirst(), bindingKey,
                            key + ".configuration-manager.projection.v1",
                            key + ".response.v1");
            case "route.hcm.operations.time-interpretation-run.action" ->
                    exactRouteWire(route, "hcm.operations", "ACTION", false, "POST",
                            "/api/time/v1/interpretation-runs", "time",
                            "/v1/interpretation-runs")
                            && route.accessProfiles().size() == 2
                            && exactExpressionProfile(route.accessProfiles().get(0),
                            "full-management", 300, false,
                            List.of("OBJECT", "TARGET_POPULATION"),
                            "predicate.hcm-time-capture-authority.v1",
                            List.of("hcm.operations.time.update"))
                            && exactCapabilityProfile(route.accessProfiles().get(1),
                            "self-service", 200, false, List.of("SELF", "OBJECT"),
                            "predicate.hcm-time-capture-authority.v1",
                            "hcm.personal.time.submit");
            case "route.hcm.operations.time-interpretation-trace.data" ->
                    exactRouteWire(route, "hcm.operations", "DATA", true, "GET",
                            "/api/time/v1/interpretation-runs/{runId}/trace", "time",
                            "/v1/interpretation-runs/{runId}/trace")
                            && route.accessProfiles().size() == 2
                            && exactExpressionProfile(route.accessProfiles().get(0),
                            "full-management", 300, true,
                            List.of("OBJECT", "TARGET_POPULATION"),
                            "predicate.hcm-time-capture-authority.v1",
                            List.of("hcm.operations.time.update"))
                            && exactProjection(route.accessProfiles().get(0), bindingKey,
                            key + ".full-management.projection.v1", key + ".response.v1")
                            && exactCapabilityProfile(route.accessProfiles().get(1),
                            "self-service", 200, true, List.of("SELF", "OBJECT"),
                            "predicate.hcm-time-capture-authority.v1",
                            "hcm.personal.time.view")
                            && exactProjection(route.accessProfiles().get(1), bindingKey,
                            key + ".self-service.projection.v1", key + ".response.v1");
            case "route.hcm.time.timecard-correction.action" ->
                    exactRouteWire(route, "hcm.operations", "ACTION", false, "POST",
                            "/api/time/v1/timecards/{timecardId}/corrections", "time",
                            "/v1/timecards/{timecardId}/corrections")
                            && route.accessProfiles().size() == 2
                            && exactExpressionProfile(route.accessProfiles().get(0),
                            "time-operator", 300, false,
                            List.of("OBJECT", "TARGET_POPULATION"),
                            "predicate.hcm-timecard-authority.v1",
                            List.of("hcm.operations.time.update"))
                            && exactCapabilityProfile(route.accessProfiles().get(1),
                            "self-submitter", 200, false, List.of("SELF", "OBJECT"),
                            "predicate.hcm-timecard-authority.v1",
                            "hcm.personal.time.submit");
            case "route.hcm.time.timecard.data" ->
                    exactRouteWire(route, "hcm.operations", "DATA", true, "GET",
                            "/api/time/v1/timecards/{timecardId}", "time",
                            "/v1/timecards/{timecardId}")
                            && route.accessProfiles().size() == 3
                            && exactExpressionProfile(route.accessProfiles().get(0),
                            "time-operator", 400, true,
                            List.of("OBJECT", "TARGET_POPULATION"),
                            "predicate.hcm-timecard-authority.v1",
                            List.of("hcm.operations.time.update"))
                            && exactProjection(route.accessProfiles().get(0), bindingKey,
                            key + ".time-operator.projection.v1", "TimecardV1")
                            && exactCapabilityProfile(route.accessProfiles().get(1),
                            "manager-approver", 300, true,
                            List.of("OBJECT", "TARGET_POPULATION"),
                            "predicate.hcm-timecard-authority.v1",
                            "hcm.team.time.approve")
                            && exactProjection(route.accessProfiles().get(1), bindingKey,
                            key + ".manager-approver.projection.v1", "TimecardV1")
                            && exactCapabilityProfile(route.accessProfiles().get(2),
                            "self-reader", 200, true, List.of("SELF", "OBJECT"),
                            "predicate.hcm-timecard-authority.v1",
                            "hcm.personal.time.view")
                            && exactProjection(route.accessProfiles().get(2), bindingKey,
                            key + ".self-reader.projection.v1", "TimecardV1");
            default -> false;
        };
    }

    private static boolean exactRouteWire(
            ProductAuthorizationContractDtos.GovernedRoute route,
            String navigation,
            String kind,
            boolean sideEffectFree,
            String method,
            String gatewayPath,
            String serviceKey,
            String servicePath) {
        var gateway = route.gatewayApiBindings().getFirst();
        var service = route.servicePepBindings().getFirst();
        return navigation.equals(route.navigationContextId())
                && navigation.equals(route.subject().surfaceKey())
                && kind.equals(route.routeKind())
                && (sideEffectFree
                        ? Boolean.TRUE.equals(route.sideEffectFree())
                        : route.sideEffectFree() == null)
                && method.equals(gateway.method())
                && gatewayPath.equals(gateway.path())
                && serviceKey.equals(service.serviceKey())
                && method.equals(service.method())
                && servicePath.equals(service.path());
    }

    private static boolean exactExpressionProfile(
            ProductAuthorizationContractDtos.AccessProfile profile,
            String key,
            int precedence,
            boolean readOnly,
            List<String> targets,
            String predicate,
            List<String> capabilities) {
        var access = profile.requiredAccess();
        return exactProfileEnvelope(profile, key, precedence, readOnly, targets, predicate)
                && access != null
                && "CAPABILITY_EXPRESSION".equals(access.type())
                && "ANY".equals(access.mode())
                && capabilities.equals(access.capabilityContractKeys());
    }

    private static boolean exactCapabilityProfile(
            ProductAuthorizationContractDtos.AccessProfile profile,
            String key,
            int precedence,
            boolean readOnly,
            List<String> targets,
            String predicate,
            String capability) {
        var access = profile.requiredAccess();
        return exactProfileEnvelope(profile, key, precedence, readOnly, targets, predicate)
                && access != null
                && "CAPABILITY".equals(access.type())
                && capability.equals(access.capabilityContractKey());
    }

    private static boolean exactProfileEnvelope(
            ProductAuthorizationContractDtos.AccessProfile profile,
            String key,
            int precedence,
            boolean readOnly,
            List<String> targets,
            String predicate) {
        return key.equals(profile.profileKey())
                && precedence == profile.precedence()
                && List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes())
                && readOnly == profile.readOnly()
                && targets.equals(profile.targetBindingKinds())
                && List.of(predicate).equals(profile.predicatePolicyKeys());
    }

    private static boolean exactProjection(
            ProductAuthorizationContractDtos.AccessProfile profile,
            String bindingKey,
            String projection,
            String schema) {
        var projections = nullSafe(profile.responseProjectionBindings());
        return projections.size() == 1
                && bindingKey.equals(projections.getFirst().apiBindingKey())
                && projection.equals(projections.getFirst().projectionPolicyKey())
                && schema.equals(projections.getFirst().responseSchemaKey());
    }

}
