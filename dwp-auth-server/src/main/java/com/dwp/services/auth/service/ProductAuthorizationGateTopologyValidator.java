package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Validates gate-specific snapshot topology and the closed elevated-risk command binding union.
 */
final class ProductAuthorizationGateTopologyValidator {

    private static final Pattern STEP_UP_TARGET_TYPE =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,79}$");

    private ProductAuthorizationGateTopologyValidator() {
    }

    static void validateRoute(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities) {
        boolean elevatedStepUp = nullSafe(route.accessProfiles()).stream()
                .map(ProductAuthorizationContractDtos.AccessProfile::requiredAccess)
                .filter(java.util.Objects::nonNull)
                .anyMatch(access -> requiredCapabilities(access, capabilities).stream()
                        .anyMatch(ProductAuthorizationGateTopologyValidator
                                ::isActivatedCapability));
        List<ProductAuthorizationContractDtos.StepUpCommandBinding> stepUps =
                nullSafe(route.stepUpCommandBindings());
        boolean originalAuthorityReceipt =
                ProductAuthorizationRecovery11ProjectionSchema
                        .isOriginalAuthorityReceiptRoute(route.routeContractKey())
                && "DATA".equals(route.routeKind())
                && Boolean.TRUE.equals(route.sideEffectFree())
                && route.accessProfiles() != null
                && route.accessProfiles().size() == 1
                && ProductAuthorizationRecovery11ProjectionSchema.RECEIPT_PROFILE
                .equals(route.accessProfiles().getFirst().profileKey())
                && route.accessProfiles().getFirst().readOnly();
        boolean nonPublishingReviewRejection =
                "route.approvals.admin.form-publish-review-reject.action"
                        .equals(route.routeContractKey())
                && "ACTION".equals(route.routeKind())
                && route.gatewayApiBindings() != null
                && route.gatewayApiBindings().size() == 1
                && "POST".equals(route.gatewayApiBindings().getFirst().method())
                && "/api/approvals/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject"
                        .equals(route.gatewayApiBindings().getFirst().path())
                && route.servicePepBindings() != null
                && route.servicePepBindings().size() == 1
                && "POST".equals(route.servicePepBindings().getFirst().method())
                && "/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject"
                        .equals(route.servicePepBindings().getFirst().path())
                && route.accessProfiles() != null
                && route.accessProfiles().size() == 1
                && "full-management".equals(route.accessProfiles().getFirst().profileKey())
                && route.accessProfiles().getFirst().requiredAccess() != null
                && "CAPABILITY".equals(route.accessProfiles().getFirst().requiredAccess().type())
                && "approvals.design.publish".equals(route.accessProfiles().getFirst()
                        .requiredAccess().capabilityContractKey())
                && List.of("OBJECT").equals(route.accessProfiles().getFirst().targetBindingKinds())
                && List.of("predicate.approval.object-version.v1")
                        .equals(route.accessProfiles().getFirst().predicatePolicyKeys())
                && !route.accessProfiles().getFirst().readOnly();
        if (elevatedStepUp && originalAuthorityReceipt) {
            require(stepUps.isEmpty(), route.routeContractKey()
                    + ": receipt DATA must not declare command step-up bindings.");
            return;
        }
        if (elevatedStepUp && nonPublishingReviewRejection) {
            require(stepUps.isEmpty(), route.routeContractKey()
                    + ": non-publishing review rejection must not declare step-up bindings.");
            return;
        }
        ProductAuthorizationContractDtos.CapabilityContract auditCapability =
                capabilities.get(HcmAuditAuthorityPolicy.CAPABILITY);
        if (elevatedStepUp
                && HcmAuditAuthorityPolicy.isExactReadRoute(route, auditCapability)) {
            require(stepUps.isEmpty(), route.routeContractKey()
                    + ": HCM audit evidence GET must not declare command step-up bindings.");
            return;
        }
        if (elevatedStepUp && isExactGenericStepUpWithoutFreshCommandRoute(route)) {
            require(stepUps.isEmpty(), route.routeContractKey()
                    + ": generic session step-up must not synthesize fresh command bindings.");
            return;
        }
        if (!elevatedStepUp) {
            require(stepUps.isEmpty(), route.routeContractKey()
                    + ": step-up command bindings are forbidden.");
            return;
        }

        Map<String, ProductAuthorizationContractDtos.ServicePepBinding> services = index(
                route.servicePepBindings(),
                ProductAuthorizationContractDtos.ServicePepBinding::bindingKey,
                route.routeContractKey() + " step-up service binding");
        Map<String, ProductAuthorizationContractDtos.StepUpCommandBinding> bindings = index(
                stepUps,
                ProductAuthorizationContractDtos.StepUpCommandBinding::bindingKey,
                route.routeContractKey() + " step-up command binding");
        require(!bindings.isEmpty() && bindings.keySet().equals(services.keySet()),
                route.routeContractKey() + ": incomplete step-up command bindings.");
        bindings.forEach((bindingKey, binding) -> {
            ProductAuthorizationContractDtos.ServicePepBinding service = services.get(bindingKey);
            require(text(binding.ownerServiceKey())
                            && binding.ownerServiceKey().equals(service.serviceKey())
                            && ("dwp-" + binding.ownerServiceKey() + "-server")
                            .equals(binding.audience()),
                    bindingKey + ": step-up owner or audience mismatch.");
            boolean pathTarget = text(binding.targetIdPathParameter());
            List<String> bodyFields = nullSafe(binding.targetIdBodyFields());
            boolean bodyTarget = !bodyFields.isEmpty();
            require(text(binding.targetType())
                            && STEP_UP_TARGET_TYPE.matcher(binding.targetType()).matches()
                            && pathTarget != bodyTarget,
                    bindingKey + ": invalid step-up target binding.");
            if (pathTarget) {
                require(service.path().contains("{" + binding.targetIdPathParameter() + "}"),
                        bindingKey + ": step-up path target is not bound by the service route.");
            } else {
                require(bodyFields.stream().allMatch(ProductAuthorizationGateTopologyValidator::text)
                                && bodyFields.stream().distinct().count() == bodyFields.size(),
                        bindingKey + ": step-up body target fields must be non-empty and unique.");
            }
            require(Set.of("COMMAND_BODY", "COMMAND_HEADER")
                            .contains(binding.expectedObjectVersionSource())
                            && text(binding.expectedObjectVersionName()),
                    bindingKey + ": invalid expected object version binding.");
        });
    }

    private static boolean isExactGenericStepUpWithoutFreshCommandRoute(
            ProductAuthorizationContractDtos.GovernedRoute route) {
        return isExactGrandfatheredGenericStepUpRoute(route)
                || isExactV49GenericStepUpWithoutFreshCommandRoute(route);
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

    static void validateBundle(ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract.version() != 2) {
            return;
        }
        require(contract.capabilities().stream().noneMatch(capability ->
                        "hcm".equals(capability.productKey())
                                || capability.contractKey().startsWith("hcm."))
                        && contract.accessPolicies().stream().noneMatch(policy ->
                        "hcm".equals(policy.productKey())
                                || policy.accessPolicyKey().startsWith("hcm."))
                        && contract.routes().stream().noneMatch(route ->
                        "hcm".equals(route.subject().productKey())
                                || route.routeContractKey().startsWith("route.hcm.")),
                "Registry v2 W1a must contain zero HCM product descriptors.");
        List<ProductAuthorizationContractDtos.StepUpCommandBinding> approvalBindings =
                contract.routes().stream()
                        .filter(route -> "approvals".equals(route.subject().productKey()))
                        .flatMap(route -> nullSafe(route.stepUpCommandBindings()).stream())
                        .toList();
        require(approvalBindings.size() == 4
                        && approvalBindings.stream()
                        .map(ProductAuthorizationContractDtos.StepUpCommandBinding::bindingKey)
                        .distinct().count() == 4,
                "Registry v2 W1a must close exactly four Approval HIGH bindings.");
    }

    private static List<ProductAuthorizationContractDtos.CapabilityContract> requiredCapabilities(
            ProductAuthorizationContractDtos.RequiredAccess access,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities) {
        if ("POLICY".equals(access.type())) return List.of();
        List<String> keys;
        if ("CAPABILITY".equals(access.type())) {
            require(text(access.capabilityContractKey()),
                    "Invalid activated-capability traversal.");
            keys = List.of(access.capabilityContractKey());
        } else {
            require("CAPABILITY_EXPRESSION".equals(access.type())
                            && Set.of("ANY", "ALL").contains(access.mode())
                            && access.capabilityContractKeys() != null
                            && !access.capabilityContractKeys().isEmpty(),
                    "Invalid activated-capability traversal.");
            keys = access.capabilityContractKeys();
        }
        return keys.stream().map(key -> {
            ProductAuthorizationContractDtos.CapabilityContract capability =
                    capabilities.get(key);
            require(capability != null,
                    "Activated-capability traversal references an unknown capability.");
            return capability;
        }).toList();
    }

    private static boolean isActivatedCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        return "ACTIVE".equals(capability.lifecycleState())
                && Set.of("HIGH", "CRITICAL").contains(capability.riskTier())
                && text(capability.activationPolicy())
                && capability.activationPolicy().startsWith("STEPUP-");
    }

    private static <T> Map<String, T> index(
            List<T> values, Function<T, String> keyExtractor, String label) {
        require(values != null, label + " list is required.");
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String key = keyExtractor.apply(value);
            require(text(key) && result.putIfAbsent(key, value) == null,
                    "Duplicate or empty " + label + " key: " + key);
        }
        return result;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
