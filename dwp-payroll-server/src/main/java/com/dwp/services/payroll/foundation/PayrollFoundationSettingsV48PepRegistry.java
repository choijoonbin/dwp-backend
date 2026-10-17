package com.dwp.services.payroll.foundation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Fail-closed runtime view of the immutable Payroll owner PEP v48 settings aliases. */
@Component
@ConditionalOnProperty(
        name = {
                "dwp.hris.payroll-foundation.wave1.enabled",
                "dwp.hris.payroll-foundation.settings.enabled"
        },
        havingValue = "true",
        matchIfMissing = false)
final class PayrollFoundationSettingsV48PepRegistry {

    static final String RESOURCE =
            "product-authorization/hcm-payroll-pep-v48.generated.json";
    static final int AUTHORIZATION_VERSION = 48;
    static final String REGISTRY_CHECKSUM =
            "9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043";
    static final String PROJECTION_CHECKSUM =
            "a0e8f8ad423201cb13794f2a6209583048665084d1b9c0a18e6d620b9e34c66e";

    private final ObjectMapper mapper;
    private final Map<PayrollFoundationSettingsRoute, Binding> bindings;

    @Autowired
    PayrollFoundationSettingsV48PepRegistry(ObjectMapper mapper) {
        this(mapper, () -> PayrollFoundationSettingsV48PepRegistry.class.getClassLoader()
                .getResourceAsStream(RESOURCE));
    }

    PayrollFoundationSettingsV48PepRegistry(
            ObjectMapper mapper, Supplier<InputStream> resource) {
        this.mapper = mapper;
        ObjectNode projection = read(resource);
        validateEnvelope(projection);
        this.bindings = compile(projection);
    }

    Binding binding(PayrollFoundationSettingsRoute route) {
        Binding binding = bindings.get(route);
        if (binding == null) {
            throw new IllegalStateException(
                    "Generated Payroll settings route is unavailable: "
                            + route.routeContractKey());
        }
        return binding;
    }

    private Map<PayrollFoundationSettingsRoute, Binding> compile(ObjectNode projection) {
        Map<String, JsonNode> routes = index(requiredArray(projection, "routes"),
                "routeContractKey", "route");
        Map<String, JsonNode> capabilities = index(requiredArray(projection, "capabilities"),
                "contractKey", "capability");
        EnumMap<PayrollFoundationSettingsRoute, Binding> result =
                new EnumMap<>(PayrollFoundationSettingsRoute.class);
        for (PayrollFoundationSettingsRoute expected : PayrollFoundationSettingsRoute.values()) {
            JsonNode route = routes.get(expected.routeContractKey());
            require(route != null, expected.routeContractKey() + ": generated route is missing");
            require("ACTIVE".equals(route.path("lifecycleState").asText())
                            && "hcm.management".equals(route.path("navigationContextId").asText())
                            && "hcm".equals(route.path("subject").path("productKey").asText())
                            && "hcm.management".equals(
                            route.path("subject").path("surfaceKey").asText()),
                    expected.routeContractKey() + ": wrong subject or lifecycle");

            JsonNode service = exactlyOne(route, "servicePepBindings");
            JsonNode gateway = exactlyOne(route, "gatewayApiBindings");
            String bindingKey = expected.routeContractKey() + ".binding.01";
            require(bindingKey.equals(service.path("bindingKey").asText())
                            && bindingKey.equals(gateway.path("bindingKey").asText())
                            && expected.method().equals(service.path("method").asText())
                            && expected.method().equals(gateway.path("method").asText())
                            && expected.pathTemplate().equals(service.path("path").asText())
                            && ("/api/payroll" + expected.pathTemplate())
                            .equals(gateway.path("path").asText())
                            && "payroll".equals(service.path("serviceKey").asText()),
                    expected.routeContractKey() + ": wrong owner/public binding");

            JsonNode profile = exactlyOne(route, "accessProfiles");
            JsonNode requiredAccess = profile.path("requiredAccess");
            require(expected.profileKey().equals(profile.path("profileKey").asText())
                            && "CAPABILITY".equals(requiredAccess.path("type").asText())
                            && expected.capabilityContractKey().equals(
                            requiredAccess.path("capabilityContractKey").asText())
                            && expected.targetBindingKinds().equals(
                            textValues(profile.path("targetBindingKinds")))
                            && expected.predicatePolicyKeys().equals(
                            textValues(profile.path("predicatePolicyKeys")))
                            && (!expected.elevated() || Set.of("ELEVATED").equals(
                            textValues(profile.path("activeAccessModes"))))
                            && (expected.elevated() || Set.of("NORMAL", "ELEVATED").equals(
                            textValues(profile.path("activeAccessModes"))))
                            && profile.path("readOnly").asBoolean() == !expected.command(),
                    expected.routeContractKey() + ": wrong profile authority tuple");

            JsonNode capability = capabilities.get(expected.capabilityContractKey());
            require(capability != null
                            && "ACTIVE".equals(capability.path("lifecycleState").asText())
                            && expected.capability().equals(
                            capability.path("resolvedCapabilityCode").asText())
                            && textValues(capability.path("routeContractKeys"))
                            .contains(expected.routeContractKey()),
                    expected.routeContractKey() + ": capability closure is invalid");

            ArrayNode stepUps = optionalArray(route, "stepUpCommandBindings");
            require(stepUps.size() == (expected.elevated() ? 1 : 0),
                    expected.routeContractKey() + ": step-up closure is invalid");
            if (expected.elevated()) {
                JsonNode stepUp = stepUps.get(0);
                require(bindingKey.equals(stepUp.path("bindingKey").asText())
                                && "PAYROLL_CONFIGURATION".equals(
                                stepUp.path("targetType").asText())
                                && "configurationId".equals(
                                stepUp.path("targetIdPathParameter").asText())
                                && "COMMAND_BODY".equals(
                                stepUp.path("expectedObjectVersionSource").asText())
                                && "expectedVersion".equals(
                                stepUp.path("expectedObjectVersionName").asText())
                                && "payroll".equals(stepUp.path("ownerServiceKey").asText())
                                && "dwp-payroll-server".equals(
                                stepUp.path("audience").asText()),
                        expected.routeContractKey() + ": wrong step-up binding");
            }
            result.put(expected, new Binding(
                    expected.routeContractKey(), expected.method(), expected.pathTemplate(),
                    expected.profileKey(), expected.capabilityContractKey(),
                    expected.capability(), expected.targetBindingKinds(),
                    expected.predicatePolicyKeys(), expected.elevated()));
        }
        require(result.size() == PayrollFoundationSettingsRoute.values().length,
                "Generated Payroll settings route closure changed");
        return Map.copyOf(result);
    }

    private void validateEnvelope(ObjectNode projection) {
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-payroll-pep-v48".equals(
                        projection.path("projectionKey").asText())
                        && "payroll".equals(projection.path("ownerServiceKey").asText())
                        && PROJECTION_CHECKSUM.equals(
                        projection.path("projectionChecksum").asText())
                        && projection.path("bindingPairCount").asInt() == 31,
                "Unexpected generated Payroll PEP v48 envelope");
        JsonNode registry = projection.path("registryRef");
        require("product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == AUTHORIZATION_VERSION
                        && REGISTRY_CHECKSUM.equals(registry.path("sha256").asText()),
                "Generated Payroll PEP v48 registry reference mismatch");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null && checksum.asText().equals(sha256(payload)),
                "Generated Payroll PEP v48 checksum mismatch");
    }

    private ObjectNode read(Supplier<InputStream> resource) {
        try (InputStream input = resource.get()) {
            if (input == null) throw new IllegalStateException(
                    "Generated Payroll PEP v48 is absent");
            JsonNode value = mapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated Payroll PEP v48 must be an object");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated Payroll PEP v48 cannot be read", exception);
        }
    }

    private Map<String, JsonNode> index(ArrayNode values, String field, String label) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        values.forEach(value -> {
            String key = value.path(field).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    "Generated Payroll " + label + " is empty or duplicated");
        });
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

    private static ArrayNode optionalArray(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null) return JsonNodeFactory.instance.arrayNode();
        require(value instanceof ArrayNode, field + " must be an array when present");
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
            String routeContractKey,
            String method,
            String servicePath,
            String profileKey,
            String capabilityContractKey,
            String capabilityCode,
            Set<String> targetBindingKinds,
            Set<String> predicatePolicyKeys,
            boolean elevated) {
        Binding {
            targetBindingKinds = Set.copyOf(targetBindingKinds);
            predicatePolicyKeys = Set.copyOf(predicatePolicyKeys);
        }
    }
}
