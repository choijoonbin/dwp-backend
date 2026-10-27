package com.dwp.services.people.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads, verifies, and compiles the generated People PEP projection at startup. */
final class HcmPeoplePepProjection {

    static final String RESOURCE =
            "product-authorization/hcm-people-pep-v60.generated.json";
    static final String REGISTRY_V60_CHECKSUM =
            "405ac7c88d30a5730faa40c730968fffc873bd1a3679e4997532f0ea9ff1c47c";
    static final String PROJECTION_V60_CHECKSUM =
            "8c1a718274b7e126ccf1ebc91114e44336d4cc9e3bb840986bea8c082756f276";
    static final String HRM009_ACTION_ROUTE =
            "route.hcm.operations.certificate-issue.action";
    static final String HRM009_OPERATIONS_PROFILE = "certificate-operations-issuer";
    static final String HRM009_SELF_PROFILE = "certificate-self-requester";
    static final String PER008_RELEASE_ROUTE =
            "route.hcm.performance.feedback-release.data";

    private final ObjectMapper objectMapper;

    HcmPeoplePepProjection(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<Binding> compile(ObjectNode projection) {
        List<Binding> result = new ArrayList<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            Map<String, JsonNode> publicBindings = indexArray(
                    route.path("gatewayApiBindings"), "bindingKey", routeKey);
            Map<String, JsonNode> stepUpBindings = route.has("stepUpCommandBindings")
                    ? indexArray(route.path("stepUpCommandBindings"), "bindingKey", routeKey)
                    : Map.of();
            List<Profile> profiles = new ArrayList<>();
            for (JsonNode value : requiredArray(route, "accessProfiles")) {
                Map<String, ProjectionBinding> projections = new LinkedHashMap<>();
                JsonNode projectionValues = value.path("responseProjectionBindings");
                if (projectionValues.isArray()) projectionValues.forEach(bindingProjection -> {
                    String bindingKey = bindingProjection.path("apiBindingKey").asText();
                    String projectionPolicyKey =
                            bindingProjection.path("projectionPolicyKey").asText();
                    String responseSchemaKey =
                            bindingProjection.path("responseSchemaKey").asText();
                    require(!bindingKey.isBlank()
                                    && !projectionPolicyKey.isBlank()
                                    && !responseSchemaKey.isBlank()
                                    && publicBindings.containsKey(bindingKey)
                                    && projections.putIfAbsent(
                                            bindingKey,
                                            new ProjectionBinding(
                                                    projectionPolicyKey,
                                                    responseSchemaKey)) == null,
                            routeKey + ": invalid response projection binding");
                });
                profiles.add(new Profile(
                        value.path("profileKey").asText(), value.path("precedence").asInt(),
                        value.path("readOnly").asBoolean(),
                        textValues(value.path("activeAccessModes")),
                        textValues(value.path("predicatePolicyKeys")),
                        textValues(value.path("targetBindingKinds")),
                        value.path("requiredAccess"), Map.copyOf(projections)));
            }
            for (JsonNode service : requiredArray(route, "servicePepBindings")) {
                require("people".equals(service.path("serviceKey").asText()),
                        routeKey + ": wrong HCM PEP owner");
                JsonNode publicBinding = publicBindings.get(service.path("bindingKey").asText());
                require(publicBinding != null, routeKey + ": public binding missing");
                require(service.path("method").asText()
                                .equals(publicBinding.path("method").asText()),
                        routeKey + ": public/service method mismatch");
                JsonNode stepUp = stepUpBindings.get(service.path("bindingKey").asText());
                result.add(new Binding(
                        routeKey, route.path("routeKind").asText(),
                        service.path("bindingKey").asText(), service.path("method").asText(),
                        publicBinding.path("path").asText(),
                        HcmPepBindingConstraints.PathTemplate.compile(
                                service.path("path").asText(), null),
                        HcmPepBindingConstraints.PathTemplate.compile(
                                service.path("path").asText(),
                                service.path("pathParameterConstraints")),
                        HcmPepBindingConstraints.QueryConstraints.compile(
                                service.path("queryParameterConstraints")),
                        List.copyOf(profiles), stepUp == null ? null : stepUp(stepUp)));
            }
            if (HRM009_ACTION_ROUTE.equals(routeKey)) {
                require(stepUpBindings.size() == 1
                                && profiles.size() == 2
                                && profiles.stream().map(Profile::profileKey).collect(
                                Collectors.toUnmodifiableSet()).equals(
                                Set.of(HRM009_OPERATIONS_PROFILE, HRM009_SELF_PROFILE)),
                        routeKey + ": route-wide HIGH profile closure changed");
            }
            require(stepUpBindings.size() == (route.has("stepUpCommandBindings")
                            ? route.path("stepUpCommandBindings").size() : 0),
                    routeKey + ": invalid step-up binding closure");
        }
        require(result.size() == 160, "HCM People PEP binding count changed");
        return List.copyOf(result);
    }

    private StepUpSpec stepUp(JsonNode value) {
        boolean standardVersionHeader = "X-DWP-Expected-Object-Version".equals(
                value.path("expectedObjectVersionName").asText());
        boolean hrm012ReceiptFirstVersionHeader = "If-Match".equals(
                value.path("expectedObjectVersionName").asText())
                && "WORKFORCE_INGESTION_OBJECT".equals(
                        value.path("targetType").asText())
                && (value.path("targetIdPathParameter").isMissingNode()
                    || value.path("targetIdPathParameter").isNull())
                && value.path("targetIdBodyFields").isArray()
                && value.path("targetIdBodyFields").size() == 1
                && "objectRef".equals(value.path("targetIdBodyFields").get(0).asText());
        require("people".equals(value.path("ownerServiceKey").asText())
                        && "dwp-people-server".equals(value.path("audience").asText())
                        && "COMMAND_HEADER".equals(
                        value.path("expectedObjectVersionSource").asText())
                        && (standardVersionHeader || hrm012ReceiptFirstVersionHeader),
                "Invalid People step-up owner/version binding");
        String pathParameter = value.path("targetIdPathParameter").asText(null);
        List<String> bodyFields = new ArrayList<>();
        if (value.has("targetIdBodyFields")) {
            value.path("targetIdBodyFields").forEach(field -> bodyFields.add(field.asText()));
        }
        require((pathParameter != null) != !bodyFields.isEmpty(),
                "Exactly one People step-up target source is required");
        return new StepUpSpec(
                value.path("targetType").asText(), pathParameter,
                List.copyOf(bodyFields), value.path("ownerServiceKey").asText(),
                value.path("audience").asText());
    }

    void validateEnvelope(ObjectNode projection) {
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-people-pep-v60".equals(projection.path("projectionKey").asText())
                        && "people".equals(projection.path("ownerServiceKey").asText()),
                "Unexpected HCM People PEP envelope");
        JsonNode registry = projection.path("registryRef");
        require("product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 60
                        && REGISTRY_V60_CHECKSUM.equals(registry.path("sha256").asText()),
                "HCM People registry reference mismatch");
        require(projection.path("sourceRegistryRouteCount").asInt() == 1154
                        && projection.path("projectedRouteContractCount").asInt() == 133
                        && projection.path("bindingPairCount").asInt() == 160,
                "HCM People release counts changed");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null
                        && PROJECTION_V60_CHECKSUM.equals(checksum.asText())
                        && PROJECTION_V60_CHECKSUM.equals(sha256(payload)),
                "HCM People projection checksum mismatch");
    }

    void validateClosure(ObjectNode projection,
            Map<String, JsonNode> capabilities, Map<String, JsonNode> policies,
            Map<String, JsonNode> expressions, Map<String, JsonNode> predicates) {
        require(capabilities.size() == 64 && policies.size() == 5
                        && expressions.size() == 3 && predicates.size() == 22,
                "HCM People descriptor closure count changed");
        Set<String> routes = new LinkedHashSet<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            require(routes.add(routeKey), "Duplicate HCM People route " + routeKey);
            require("hcm".equals(route.path("subject").path("productKey").asText()),
                    routeKey + ": non-HCM route escaped People PEP");
            for (JsonNode profile : requiredArray(route, "accessProfiles")) {
                JsonNode access = profile.path("requiredAccess");
                if ("CAPABILITY".equals(access.path("type").asText())) {
                    descriptor(capabilities, access.path("capabilityContractKey").asText(),
                            "capability");
                } else if ("CAPABILITY_EXPRESSION".equals(access.path("type").asText())) {
                    textValues(access.path("capabilityContractKeys")).forEach(key ->
                            descriptor(capabilities, key, "capability"));
                } else if ("POLICY".equals(access.path("type").asText())) {
                    descriptor(policies, access.path("accessPolicyKey").asText(), "policy");
                } else {
                    throw new IllegalStateException(routeKey + ": unsupported access type");
                }
                for (String predicateKey : textValues(profile.path("predicatePolicyKeys"))) {
                    JsonNode predicate = descriptor(predicates, predicateKey, "predicate");
                    require("people".equals(predicate.path("ownerServiceKey").asText())
                                    && textValues(predicate.path("routeContractKeys"))
                                    .contains(routeKey),
                            routeKey + ": predicate owner or allowlist mismatch");
                }
            }
        }
        JsonNode releasePredicate = descriptor(
                predicates, "predicate.hcm-performance-feedback-release.v1", "predicate");
        require(Set.of("SELF", "OBJECT", "TARGET_POPULATION").equals(
                        textValues(releasePredicate.path("targetBindingKinds")))
                        && Set.of(PER008_RELEASE_ROUTE).equals(
                        textValues(releasePredicate.path("routeContractKeys"))),
                "PER-008 feedback release predicate closure changed");
    }

    ObjectNode readProjection() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException(
                    "Generated HCM People PEP is absent.");
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated HCM People PEP must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException("Generated HCM People PEP cannot be read.", exception);
        }
    }

    Map<String, JsonNode> index(ObjectNode root, String field, String keyField) {
        return indexArray(requiredArray(root, field), keyField, field);
    }

    private Map<String, JsonNode> indexArray(JsonNode values, String keyField, String label) {
        require(values.isArray(), label + " must be an array");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String key = value.path(keyField).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    label + ": duplicate or empty " + keyField);
        }
        return Map.copyOf(result);
    }

    static JsonNode descriptor(Map<String, JsonNode> values, String key, String label) {
        JsonNode value = values.get(key);
        if (value == null) throw new IllegalStateException("Unknown HCM " + label + ' ' + key);
        return value;
    }

    static Set<String> textValues(JsonNode value) {
        require(value.isArray(), "Expected a generated HCM string array");
        Set<String> result = new LinkedHashSet<>();
        value.forEach(item -> require(item.isTextual() && result.add(item.asText()),
                "Generated HCM string array contains an invalid or duplicate value"));
        return Set.copyOf(result);
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("HCM People PEP checksum failed.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value instanceof ArrayNode, field + " must be an array");
        return (ArrayNode) value;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Profile(
            String profileKey,
            int precedence,
            boolean readOnly,
            Set<String> activeModes,
            Set<String> predicateKeys,
            Set<String> targetBindingKinds,
            JsonNode requiredAccess,
            Map<String, ProjectionBinding> projections) {
    }

    record ProjectionBinding(
            String projectionPolicyKey,
            String responseSchemaKey) {
    }

    record Binding(
            String routeContractKey,
            String routeKind,
            String bindingKey,
            String method,
            String publicPath,
            HcmPepBindingConstraints.PathTemplate claimPath,
            HcmPepBindingConstraints.PathTemplate path,
            HcmPepBindingConstraints.QueryConstraints query,
            List<Profile> profiles,
            StepUpSpec stepUp) {
    }

    record StepUpSpec(String targetType, String targetIdPathParameter,
            List<String> targetIdBodyFields, String ownerServiceKey, String audience) {
    }
}
