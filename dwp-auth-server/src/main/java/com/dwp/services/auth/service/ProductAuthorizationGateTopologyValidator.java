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
                .filter(access -> "CAPABILITY".equals(access.type()))
                .map(ProductAuthorizationContractDtos.RequiredAccess::capabilityContractKey)
                .map(capabilities::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(capability -> Set.of("HIGH", "CRITICAL").contains(capability.riskTier())
                        && text(capability.activationPolicy())
                        && capability.activationPolicy().startsWith("STEPUP-"));
        List<ProductAuthorizationContractDtos.StepUpCommandBinding> stepUps =
                nullSafe(route.stepUpCommandBindings());
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
