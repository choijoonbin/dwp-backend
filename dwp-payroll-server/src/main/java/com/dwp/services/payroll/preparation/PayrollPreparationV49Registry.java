package com.dwp.services.payroll.preparation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Fail-closed view of the immutable generated v49 payroll authorization projection. */
@Component
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollPreparationV49Registry {

    static final String RESOURCE =
            "product-authorization/hcm-payroll-pep-v49.generated.json";
    static final String RESOURCE_SHA256 =
            "663ced685337956e1c198680283003339d149216caf39697b143ce969ea29f7b";
    static final String PROJECTION_CHECKSUM =
            "5204125c7748c5052a952506646b30ffb8109463d1b01066f3830e1c1af663b6";
    static final String REGISTRY_CHECKSUM =
            "5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60";

    private final ObjectMapper mapper;
    private final Map<PayrollPreparationRoute, Binding> bindings;

    PayrollPreparationV49Registry(ObjectMapper mapper) {
        this(mapper, () -> PayrollPreparationV49Registry.class.getClassLoader()
                .getResourceAsStream(RESOURCE));
    }

    PayrollPreparationV49Registry(
            ObjectMapper mapper, Supplier<InputStream> resource) {
        this.mapper = mapper;
        ObjectNode projection = read(resource);
        validateEnvelope(projection);
        this.bindings = compile(projection);
    }

    Optional<Binding> resolve(String method, String path, String routeContractKey) {
        if (method == null || path == null || routeContractKey == null) {
            return Optional.empty();
        }
        return bindings.values().stream()
                .filter(value -> value.route().method().equals(method))
                .filter(value -> value.route().path().equals(path))
                .filter(value -> value.route().routeContractKey().equals(routeContractKey))
                .findFirst();
    }

    boolean ownsPath(String method, String path) {
        return bindings.values().stream().anyMatch(value ->
                value.route().method().equals(method)
                        && value.route().path().equals(path));
    }

    Binding binding(PayrollPreparationRoute route) {
        return bindings.get(route);
    }

    private Map<PayrollPreparationRoute, Binding> compile(ObjectNode projection) {
        Map<String, JsonNode> capabilities = index(
                array(projection, "capabilities"), "contractKey");
        Map<String, JsonNode> predicates = index(
                array(projection, "predicatePolicies"), "predicatePolicyKey");
        Map<String, JsonNode> routes = index(
                array(projection, "routes"), "routeContractKey");
        EnumMap<PayrollPreparationRoute, Binding> result =
                new EnumMap<>(PayrollPreparationRoute.class);
        for (PayrollPreparationRoute expected : PayrollPreparationRoute.values()) {
            JsonNode route = required(routes.get(expected.routeContractKey()),
                    expected.routeContractKey() + " route");
            require("ACTIVE".equals(route.path("lifecycleState").asText()),
                    "route must be active");
            require(expected.routeKind().name().equals(route.path("routeKind").asText()),
                    "route kind differs");
            JsonNode service = one(route, "servicePepBindings");
            JsonNode gateway = one(route, "gatewayApiBindings");
            String bindingKey = service.path("bindingKey").asText();
            require(expected.method().equals(service.path("method").asText())
                            && expected.path().equals(service.path("path").asText())
                            && "payroll".equals(service.path("serviceKey").asText())
                            && bindingKey.equals(gateway.path("bindingKey").asText())
                            && expected.method().equals(gateway.path("method").asText())
                            && ("/api/payroll" + expected.path())
                            .equals(gateway.path("path").asText()),
                    "owner/gateway binding differs");
            JsonNode profile = one(route, "accessProfiles");
            JsonNode access = profile.path("requiredAccess");
            require("CAPABILITY".equals(access.path("type").asText())
                            && expected.capabilityContractKey().equals(
                            access.path("capabilityContractKey").asText()),
                    "capability allocation differs");
            require(textSet(profile.path("activeAccessModes"))
                            .equals(expected.activeAccessModes()),
                    "active access mode differs");
            require(textSet(profile.path("predicatePolicyKeys"))
                            .equals(Set.of(expected.predicatePolicyKey())),
                    "predicate allocation differs");
            require(profile.path("precedence").asInt() == 300,
                    "access profile precedence differs");
            if (expected == PayrollPreparationRoute.PREPARE) {
                require("payroll-run-operator".equals(
                                profile.path("profileKey").asText())
                                && !profile.path("readOnly").asBoolean(true)
                                && textSet(profile.path("targetBindingKinds"))
                                .equals(Set.of("OBJECT", "TARGET_POPULATION")),
                        "prepare access profile differs");
            } else {
                require("payroll-support-viewer".equals(
                                profile.path("profileKey").asText())
                                && profile.path("readOnly").asBoolean(false)
                                && textSet(profile.path("targetBindingKinds"))
                                .equals(Set.of("TARGET_POPULATION"))
                                && "wire-authority.hcm.operations.payroll-population.v1"
                                .equals(route.path("authorizationEquivalenceKey").asText()),
                        "population read profile differs");
                JsonNode response = one(profile, "responseProjectionBindings");
                require(bindingKey.equals(response.path("apiBindingKey").asText())
                                && (expected.routeContractKey()
                                + ".payroll-support-viewer.projection.v1")
                                .equals(response.path("projectionPolicyKey").asText())
                                && "route.hcm.operations.payroll-support.data.response.v1"
                                .equals(response.path("responseSchemaKey").asText())
                                && (expected != PayrollPreparationRoute.DATA
                                || route.path("sideEffectFree").asBoolean(false)),
                        "population response projection differs");
            }
            JsonNode capability = required(capabilities.get(
                    expected.capabilityContractKey()), "capability");
            require(expected.capabilityCode().equals(
                            capability.path("resolvedCapabilityCode").asText())
                            && expected.scopeResolver().equals(
                            capability.path("scopeResolver").asText())
                            && textSet(capability.path("routeContractKeys"))
                            .contains(expected.routeContractKey()),
                    "capability semantics differ");
            JsonNode predicate = required(predicates.get(
                    expected.predicatePolicyKey()), "predicate");
            require("payroll".equals(predicate.path("ownerServiceKey").asText())
                            && textSet(predicate.path("routeContractKeys"))
                            .contains(expected.routeContractKey()),
                    "predicate closure differs");
            if (expected.commandChallenge()) {
                JsonNode stepUp = one(route, "stepUpCommandBindings");
                require(bindingKey.equals(stepUp.path("bindingKey").asText())
                                && "dwp-payroll-server".equals(
                                stepUp.path("audience").asText())
                                && "PAYROLL_RUN_PREPARATION".equals(
                                stepUp.path("targetType").asText())
                                && textSet(stepUp.path("targetIdBodyFields"))
                                .equals(Set.of("preparationId"))
                                && "X-DWP-Expected-Object-Version".equals(
                                stepUp.path("expectedObjectVersionName").asText())
                                && "COMMAND_HEADER".equals(
                                stepUp.path("expectedObjectVersionSource").asText())
                                && "payroll".equals(
                                stepUp.path("ownerServiceKey").asText()),
                        "prepare step-up binding differs");
            } else {
                require(route.path("stepUpCommandBindings").isMissingNode()
                                || route.path("stepUpCommandBindings").isEmpty(),
                        "read route unexpectedly requires a command challenge");
            }
            result.put(expected, new Binding(expected));
        }
        require(result.size() == 3, "PAY-013 route closed set differs");
        return Map.copyOf(result);
    }

    private void validateEnvelope(ObjectNode projection) {
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-payroll-pep-v49".equals(
                        projection.path("projectionKey").asText())
                        && "payroll".equals(projection.path("ownerServiceKey").asText())
                        && "SHA-256".equals(
                        projection.path("projectionChecksumAlgorithm").asText())
                        && PROJECTION_CHECKSUM.equals(
                        projection.path("projectionChecksum").asText()),
                "unexpected generated Payroll v49 envelope");
        JsonNode registry = projection.path("registryRef");
        require(registry.path("version").asInt() == 49
                        && "product-surfaces".equals(
                        registry.path("bundleKey").asText())
                        && REGISTRY_CHECKSUM.equals(registry.path("sha256").asText()),
                "unexpected v49 registry reference");
        ObjectNode payload = projection.deepCopy();
        payload.remove("projectionChecksum");
        require(PROJECTION_CHECKSUM.equals(sha256(payload)),
                "generated v49 projection checksum differs");
    }

    private ObjectNode read(Supplier<InputStream> resource) {
        try (InputStream input = resource.get()) {
            if (input == null) throw new IllegalStateException("generated v49 projection absent");
            byte[] bytes = input.readAllBytes();
            require(RESOURCE_SHA256.equals(sha256(bytes)),
                    "generated v49 resource digest differs");
            JsonNode value = mapper.readTree(bytes);
            if (value instanceof ObjectNode object) return object;
            throw new IllegalStateException("generated v49 projection is not an object");
        } catch (IOException exception) {
            throw new IllegalStateException("generated v49 projection cannot be read", exception);
        }
    }

    private Map<String, JsonNode> index(ArrayNode values, String field) {
        java.util.LinkedHashMap<String, JsonNode> result = new java.util.LinkedHashMap<>();
        values.forEach(value -> {
            String key = value.path(field).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    field + " is blank or duplicated");
        });
        return Map.copyOf(result);
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("projection checksum failed", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("resource checksum failed", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(name ->
                    result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static ArrayNode array(JsonNode value, String field) {
        if (value.path(field) instanceof ArrayNode array) return array;
        throw new IllegalStateException(field + " must be an array");
    }

    private static JsonNode one(JsonNode value, String field) {
        ArrayNode array = array(value, field);
        require(array.size() == 1, field + " must contain exactly one item");
        return array.get(0);
    }

    private static Set<String> textSet(JsonNode value) {
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalStateException("expected a string array");
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        array.forEach(item -> require(item.isTextual() && result.add(item.asText()),
                "string array is invalid or duplicated"));
        return Set.copyOf(result);
    }

    private static JsonNode required(JsonNode value, String label) {
        if (value == null || value.isMissingNode()) {
            throw new IllegalStateException(label + " is missing");
        }
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Binding(PayrollPreparationRoute route) {
    }
}
