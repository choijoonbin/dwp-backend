package com.dwp.services.payroll.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Fail-closed runtime view of the bundled current Payroll PEP v62. The
 * calculation owner never treats hand-authored route metadata as authority.
 */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollV41PepRegistry {

    static final String RESOURCE =
            "product-authorization/hcm-payroll-pep-v62.generated.json";
    static final String PROJECTION_CHECKSUM =
            "cacfb42a68eaff23952c34e9b86a0e2f36f52930b5b851d6cdfc4f9aa687965c";
    static final String REGISTRY_CHECKSUM =
            "cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770";
    static final int AUTHORIZATION_VERSION = 62;

    private final ObjectMapper mapper;
    private final Map<PayrollCalculationRoute, Binding> bindings;

    @Autowired
    PayrollV41PepRegistry(ObjectMapper mapper) {
        this(mapper, () -> PayrollV41PepRegistry.class.getClassLoader()
                .getResourceAsStream(RESOURCE));
    }

    PayrollV41PepRegistry(ObjectMapper mapper, Supplier<InputStream> resource) {
        this.mapper = mapper;
        ObjectNode projection = read(resource);
        validateEnvelope(projection);
        this.bindings = compile(projection);
    }

    Optional<Binding> resolve(String method, String path) {
        if (method == null || path == null) return Optional.empty();
        return bindings.values().stream()
                .filter(binding -> binding.method().equals(method))
                .filter(binding -> binding.path().matcher(path).matches())
                .findFirst();
    }

    Binding binding(PayrollCalculationRoute route) {
        Binding binding = bindings.get(route);
        if (binding == null) throw new IllegalStateException(
                "Generated Payroll route is unavailable: " + route.routeContractKey());
        return binding;
    }

    private Map<PayrollCalculationRoute, Binding> compile(ObjectNode projection) {
        Map<String, JsonNode> capabilities = index(
                requiredArray(projection, "capabilities"), "contractKey", "capability");
        Map<String, JsonNode> routes = index(
                requiredArray(projection, "routes"), "routeContractKey", "route");
        EnumMap<PayrollCalculationRoute, Binding> result =
                new EnumMap<>(PayrollCalculationRoute.class);
        for (PayrollCalculationRoute expected : PayrollCalculationRoute.values()) {
            JsonNode route = routes.get(expected.routeContractKey());
            require(route != null, "Generated Payroll route is missing: "
                    + expected.routeContractKey());
            require("ACTIVE".equals(route.path("lifecycleState").asText())
                            && "hcm".equals(route.path("subject").path("productKey").asText()),
                    expected.routeContractKey() + ": inactive or non-HCM route");
            JsonNode service = exactlyOne(route, "servicePepBindings");
            JsonNode gateway = exactlyOne(route, "gatewayApiBindings");
            String bindingKey = service.path("bindingKey").asText();
            String method = service.path("method").asText();
            String servicePath = service.path("path").asText();
            require("payroll".equals(service.path("serviceKey").asText())
                            && !bindingKey.isBlank()
                            && bindingKey.equals(gateway.path("bindingKey").asText())
                            && method.equals(gateway.path("method").asText())
                            && ("/api/payroll" + servicePath)
                            .equals(gateway.path("path").asText()),
                    expected.routeContractKey() + ": invalid owner/public binding");

            JsonNode profile = exactlyOne(route, "accessProfiles");
            Set<String> activeAccessModes = textValues(
                    profile.path("activeAccessModes"));
            require(!activeAccessModes.isEmpty()
                            && Set.of("NORMAL", "ELEVATED")
                            .containsAll(activeAccessModes),
                    expected.routeContractKey()
                            + ": generated active access modes are invalid");
            JsonNode access = profile.path("requiredAccess");
            Set<String> capabilityKeys = new LinkedHashSet<>();
            if ("CAPABILITY".equals(access.path("type").asText())) {
                capabilityKeys.add(access.path("capabilityContractKey").asText());
            } else if ("CAPABILITY_EXPRESSION".equals(access.path("type").asText())
                    && "ANY".equals(access.path("mode").asText())) {
                textValues(access.path("capabilityContractKeys"))
                        .forEach(capabilityKeys::add);
            } else {
                throw new IllegalStateException(expected.routeContractKey()
                        + ": unsupported generated access expression");
            }
            require(!capabilityKeys.isEmpty(), expected.routeContractKey()
                    + ": generated capability allocation is empty");
            Map<String, String> codesByKey = new LinkedHashMap<>();
            Map<String, CapabilitySemantics> semanticsByKey =
                    new LinkedHashMap<>();
            boolean highRisk = false;
            for (String capabilityKey : capabilityKeys) {
                JsonNode capability = capabilities.get(capabilityKey);
                require(capability != null
                                && "ACTIVE".equals(capability.path("lifecycleState").asText())
                                && textValues(capability.path("routeContractKeys"))
                                .contains(expected.routeContractKey()),
                        expected.routeContractKey() + ": capability closure is invalid");
                String code = capability.path("resolvedCapabilityCode").asText();
                require(!code.isBlank() && codesByKey.put(capabilityKey, code) == null,
                        expected.routeContractKey() + ": capability code is invalid");
                String capabilityAction = capability.path("action").asText();
                String scopeResolver = capability.path("scopeResolver").asText();
                String activationPolicy = capability.path("activationPolicy").asText();
                String sodPolicyId = capability.path("sodPolicyId").asText();
                require(!capabilityAction.isBlank() && !scopeResolver.isBlank(),
                        expected.routeContractKey()
                                + ": capability semantics are incomplete");
                semanticsByKey.put(capabilityKey, new CapabilitySemantics(
                        capabilityAction, scopeResolver,
                        activationPolicy.isBlank() ? null : activationPolicy,
                        sodPolicyId.isBlank() ? null : sodPolicyId));
                highRisk |= "STEPUP-MGMT-HIGH-V1".equals(activationPolicy);
            }

            JsonNode stepUps = route.path("stepUpCommandBindings");
            boolean commandBoundStepUp = stepUps.isArray() && !stepUps.isEmpty();
            require(commandBoundStepUp == highRisk,
                    expected.routeContractKey() + ": step-up activation/binding mismatch");
            if (commandBoundStepUp) validateStepUp(expected, exactlyOne(
                    route, "stepUpCommandBindings"), bindingKey);

            boolean responseProjection = false;
            JsonNode projections = profile.path("responseProjectionBindings");
            if (projections.isArray() && !projections.isEmpty()) {
                require(projections.size() == 1
                                && bindingKey.equals(projections.get(0)
                                .path("apiBindingKey").asText())
                                && !projections.get(0).path("projectionPolicyKey")
                                .asText().isBlank()
                                && !projections.get(0).path("responseSchemaKey")
                                .asText().isBlank(),
                        expected.routeContractKey() + ": invalid response projection");
                responseProjection = true;
            }
            result.put(expected, new Binding(
                    expected, expected.routeContractKey(), method,
                    servicePath, pathPattern(servicePath), Map.copyOf(codesByKey),
                    Map.copyOf(semanticsByKey), activeAccessModes,
                    highRisk, responseProjection));
        }
        require(result.size() == PayrollCalculationRoute.values().length,
                "Generated Payroll calculation route closure changed");
        return Map.copyOf(result);
    }

    private void validateStepUp(
            PayrollCalculationRoute route, JsonNode stepUp, String bindingKey) {
        require(bindingKey.equals(stepUp.path("bindingKey").asText())
                        && "payroll".equals(stepUp.path("ownerServiceKey").asText())
                        && "dwp-payroll-server".equals(stepUp.path("audience").asText())
                        && "COMMAND_HEADER".equals(
                        stepUp.path("expectedObjectVersionSource").asText())
                        && "X-DWP-Expected-Object-Version".equals(
                        stepUp.path("expectedObjectVersionName").asText())
                        && "PAYROLL_RUN".equals(stepUp.path("targetType").asText())
                        && "runId".equals(stepUp.path("targetIdPathParameter").asText())
                        && (route == PayrollCalculationRoute.CALCULATE
                        || route == PayrollCalculationRoute.APPROVE
                        || route == PayrollCalculationRoute.FINALIZE),
                route.routeContractKey() + ": invalid generated step-up binding");
    }

    private void validateEnvelope(ObjectNode projection) {
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-payroll-pep-v62".equals(
                        projection.path("projectionKey").asText())
                        && "payroll".equals(projection.path("ownerServiceKey").asText())
                        && "SHA-256".equals(
                        projection.path("projectionChecksumAlgorithm").asText())
                        && PROJECTION_CHECKSUM.equals(
                        projection.path("projectionChecksum").asText()),
                "Unexpected generated Payroll PEP envelope");
        JsonNode registry = projection.path("registryRef");
        require("product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == AUTHORIZATION_VERSION
                        && REGISTRY_CHECKSUM.equals(registry.path("sha256").asText()),
                "Generated Payroll PEP registry reference mismatch");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null && checksum.asText().equals(sha256(payload)),
                "Generated Payroll PEP checksum mismatch");
    }

    private ObjectNode read(Supplier<InputStream> resource) {
        try (InputStream input = resource.get()) {
            if (input == null) throw new IllegalStateException(
                    "Generated Payroll PEP v62 is absent");
            JsonNode value = mapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated Payroll PEP must be an object");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated Payroll PEP v62 cannot be read", exception);
        }
    }

    private Pattern pathPattern(String template) {
        StringBuilder regex = new StringBuilder("^");
        int cursor = 0;
        java.util.regex.Matcher matcher = Pattern.compile("\\{[^{}]+}").matcher(template);
        while (matcher.find()) {
            regex.append(Pattern.quote(template.substring(cursor, matcher.start())));
            regex.append("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-")
                    .append("[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
            cursor = matcher.end();
        }
        regex.append(Pattern.quote(template.substring(cursor))).append('$');
        return Pattern.compile(regex.toString());
    }

    private Map<String, JsonNode> index(
            ArrayNode values, String keyField, String label) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String key = value.path(keyField).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    "Generated Payroll " + label + " key is empty or duplicated");
        }
        return Map.copyOf(result);
    }

    private Set<String> textValues(JsonNode values) {
        require(values.isArray(), "Generated Payroll string set must be an array");
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> require(value.isTextual() && result.add(value.asText()),
                "Generated Payroll string set is invalid or duplicated"));
        return Set.copyOf(result);
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Generated Payroll PEP checksum failed", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value instanceof ArrayNode, field + " must be an array");
        return (ArrayNode) value;
    }

    private static JsonNode exactlyOne(JsonNode source, String field) {
        ArrayNode values = requiredArray(source, field);
        require(values.size() == 1, field + " must contain exactly one binding");
        return values.get(0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Binding(
            PayrollCalculationRoute route,
            String routeContractKey,
            String method,
            String pathTemplate,
            Pattern path,
            Map<String, String> capabilityCodesByContract,
            Map<String, CapabilitySemantics> capabilitySemanticsByContract,
            Set<String> activeAccessModes,
            boolean highRisk,
            boolean responseProjection) {

        Binding {
            capabilityCodesByContract = Map.copyOf(capabilityCodesByContract);
            capabilitySemanticsByContract = Map.copyOf(
                    capabilitySemanticsByContract);
            activeAccessModes = Set.copyOf(activeAccessModes);
        }

        boolean authorizedBy(Set<String> permissions) {
            return capabilityCodesByContract.values().stream()
                    .anyMatch(permissions::contains);
        }

        String matchedCapabilityContract(Set<String> permissions) {
            return capabilityCodesByContract.entrySet().stream()
                    .filter(entry -> permissions.contains(entry.getValue()))
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No generated Payroll capability matched"));
        }
    }

    record CapabilitySemantics(
            String action,
            String scopeResolver,
            String activationPolicy,
            String sodPolicyId) {
    }
}
