package com.dwp.services.people.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime-only, closed People PEP projection generated from CORE-006 registry v3.
 * Provider-support profiles are parsed to detect contract drift, but they are not a service
 * readiness signal: {@link HcmProductSurfacePepFilter} denies them until the trusted request
 * carries the contractual legal-entity population boundary as well as the support session.
 */
@Component
public final class HcmV3PepRegistry {

    static final String RESOURCE =
            "product-authorization/hcm-people-pep-v3.generated.json";
    static final String W1B_V3_CHECKSUM =
            "f90c4e3a734204a4619ae77d3476ebc7cc802c43ed8574fcf4f3fc85def67a8e";

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> capabilities;
    private final Map<String, JsonNode> policies;
    private final Map<String, JsonNode> expressions;
    private final Map<String, JsonNode> predicates;
    private final List<Binding> bindings;

    public HcmV3PepRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode projection = readProjection();
        validateEnvelope(projection);
        capabilities = index(projection, "capabilities", "contractKey");
        policies = index(projection, "accessPolicies", "accessPolicyKey");
        expressions = index(projection, "entitlementExpressions", "expressionKey");
        predicates = index(projection, "predicatePolicies", "predicatePolicyKey");
        bindings = compile(projection);
        validateClosure(projection);
    }

    public Decision authorize(RequestEvidence evidence) {
        if (evidence.trustedRouteContractKey() == null
                || evidence.trustedRouteContractKey().isBlank()) {
            return Decision.denied("TRUSTED_ROUTE_KEY_REQUIRED");
        }
        List<Binding> matches = bindings.stream()
                .filter(binding -> binding.routeContractKey()
                        .equals(evidence.trustedRouteContractKey()))
                .filter(binding -> binding.method().equals(evidence.method()))
                .filter(binding -> binding.path().matches(evidence.path()))
                .filter(binding -> binding.query().matches(evidence.rawQuery()))
                .toList();
        if (matches.size() != 1) {
            return Decision.denied(matches.isEmpty()
                    ? "UNKNOWN_METHOD_PATH_BINDING" : "AMBIGUOUS_METHOD_PATH_BINDING");
        }
        Binding binding = matches.getFirst();
        Profile profile = binding.profiles().stream()
                .sorted(Comparator.comparingInt(Profile::precedence).reversed())
                .filter(value -> value.activeModes().contains(evidence.activeAccessMode()))
                .filter(value -> profileAllows(value, evidence))
                .findFirst().orElse(null);
        if (profile == null) return Decision.denied("EXACT_ROUTE_AUTHORITY_REQUIRED");
        String capabilityKey = profile.requiredAccess().path("capabilityContractKey").asText(null);
        String activationPolicy = capabilityKey == null ? null
                : descriptor(capabilities, capabilityKey, "capability")
                .path("activationPolicy").asText(null);
        ProjectionBinding projection = profile.projections().get(binding.bindingKey());
        return Decision.allowed(new RouteAuthority(
                binding.routeContractKey(), binding.routeKind(), profile.profileKey(),
                profile.readOnly(), profile.predicateKeys(), profile.targetBindingKinds(),
                binding.bindingKey(), capabilityKey, activationPolicy,
                binding.method(), binding.publicPath(), binding.stepUp(),
                projection == null ? null : projection.projectionPolicyKey(),
                projection == null ? null : projection.responseSchemaKey()));
    }

    public boolean owns(String method, String path, String rawQuery) {
        // Claim a generated route's broad path before method, fixed/allowlisted
        // path values, and query constraints are evaluated. Otherwise an
        // invalid constraint could skip this PEP and fall through to legacy
        // controller authorization.
        return claimedNamespace(path)
                || bindings.stream().anyMatch(binding -> binding.claimPath().claims(path));
    }

    private boolean claimedNamespace(String path) {
        if (path == null) return false;
        if (isClaimedNamespace(path)) return true;
        String decoded = decodePercentForClaim(path);
        return !decoded.equals(path) && isClaimedNamespace(decoded);
    }

    private boolean isClaimedNamespace(String path) {
        return claimedPrefix(path, "/v1/hr")
                || claimedPrefix(path, "/v1/workforce")
                || claimedPrefix(path, "/v1/people")
                || claimedPrefix(path, "/v1/org-chart");
    }

    /**
     * Decodes printable ASCII only for broad ownership detection. Authorization
     * still receives the raw URI and rejects every encoded/non-canonical path.
     * Two passes also claim a once-decoded percent sequence without treating it
     * as a valid route binding.
     */
    private String decodePercentForClaim(String path) {
        String current = path;
        for (int pass = 0; pass < 2; pass++) {
            StringBuilder decoded = new StringBuilder(current.length());
            boolean changed = false;
            for (int index = 0; index < current.length(); index++) {
                if (current.charAt(index) == '%' && index + 2 < current.length()) {
                    int high = Character.digit(current.charAt(index + 1), 16);
                    int low = Character.digit(current.charAt(index + 2), 16);
                    int value = high < 0 || low < 0 ? -1 : (high << 4) + low;
                    if (value >= 0x20 && value <= 0x7e) {
                        decoded.append((char) value);
                        index += 2;
                        changed = true;
                        continue;
                    }
                }
                decoded.append(current.charAt(index));
            }
            if (!changed) return current;
            current = decoded.toString();
        }
        return current;
    }

    private boolean claimedPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/")
                || path.startsWith(prefix + ";");
    }

    public List<BindingContract> bindingContracts() {
        return bindings.stream().map(binding -> new BindingContract(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.path().template())).toList();
    }

    public List<BindingContract> highRiskBindingContracts() {
        return bindings.stream().filter(binding -> binding.stepUp() != null)
                .map(binding -> new BindingContract(
                        binding.routeContractKey(), binding.routeKind(), binding.method(),
                        binding.path().template())).toList();
    }

    private boolean profileAllows(Profile profile, RequestEvidence evidence) {
        JsonNode access = profile.requiredAccess();
        return switch (access.path("type").asText()) {
            case "CAPABILITY" -> capabilityAllows(
                    access.path("capabilityContractKey").asText(), evidence);
            case "CAPABILITY_EXPRESSION" -> capabilityExpressionAllows(access, evidence);
            case "POLICY" -> policyAllows(access.path("accessPolicyKey").asText(), evidence);
            default -> false;
        };
    }

    private boolean capabilityExpressionAllows(JsonNode access, RequestEvidence evidence) {
        List<Boolean> decisions = new ArrayList<>();
        access.path("capabilityContractKeys").forEach(value ->
                decisions.add(capabilityAllows(value.asText(), evidence)));
        return !decisions.isEmpty() && ("ALL".equals(access.path("mode").asText())
                ? decisions.stream().allMatch(Boolean::booleanValue)
                : "ANY".equals(access.path("mode").asText())
                && decisions.stream().anyMatch(Boolean::booleanValue));
    }

    private boolean policyAllows(String policyKey, RequestEvidence evidence) {
        JsonNode policy = descriptor(policies, policyKey, "policy");
        if ("MODE_BRANCH".equals(policy.path("evaluationType").asText())) {
            for (JsonNode branch : policy.path("modeBranches")) {
                if (!evidence.activeAccessMode().equals(
                        branch.path("activeAccessMode").asText())) continue;
                if ("SUPPORT_SESSION".equals(branch.path("authorityMode").asText())) {
                    Set<String> required = textValues(branch.path("supportScopes"));
                    return !required.isEmpty()
                            && evidence.supportScopes().stream().anyMatch(required::contains);
                }
                List<Boolean> values = new ArrayList<>();
                branch.path("capabilityContractKeys").forEach(value ->
                        values.add(capabilityAllows(value.asText(), evidence)));
                return !values.isEmpty() && ("ALL".equals(branch.path("capabilityMode").asText())
                        ? values.stream().allMatch(Boolean::booleanValue)
                        : "ANY".equals(branch.path("capabilityMode").asText())
                        && values.stream().anyMatch(Boolean::booleanValue));
            }
            return false;
        }
        if (!"SINGLE".equals(policy.path("evaluationType").asText())) return false;
        JsonNode expression = descriptor(expressions,
                policy.path("entitlementExpressionKey").asText(), "expression");
        return expressionAllows(expression.path("expression"), evidence.permissions());
    }

    private boolean capabilityAllows(String capabilityKey, RequestEvidence evidence) {
        JsonNode capability = descriptor(capabilities, capabilityKey, "capability");
        if (!evidence.permissions().contains(
                capability.path("resolvedCapabilityCode").asText().toUpperCase(Locale.ROOT))) {
            return false;
        }
        if (capability.path("requiresProductEntitlement").asBoolean()
                && !containsPermission(evidence.permissions(), "APP.HCM:VIEW")
                && !containsPermission(evidence.permissions(), "APP.HCM:MANAGE")) {
            return false;
        }
        if ("REQUIRED".equals(capability.path("responsibilityRequirement").asText())
                && !hasResourceRole(evidence.resourceRoles(), capability)) {
            return false;
        }
        // Activation is evaluated by Auth and is carried in the current signed decision.
        // Requiring ELEVATED here would incorrectly reject STEP_UP_REQUIRED capabilities
        // after Auth has already selected a valid normal-mode read profile.
        return true;
    }

    private boolean expressionAllows(JsonNode expression, Set<String> permissions) {
        String type = expression.path("type").asText();
        if ("LEAF".equals(type)) {
            return containsPermission(
                    permissions,
                    expression.path("entitlement").asText().toUpperCase(Locale.ROOT));
        }
        List<Boolean> children = new ArrayList<>();
        JsonNode values = expression.has("children")
                ? expression.path("children") : expression.path("operands");
        values.forEach(child -> children.add(expressionAllows(child, permissions)));
        return !children.isEmpty() && ("ALL".equals(type)
                ? children.stream().allMatch(Boolean::booleanValue)
                : "ANY".equals(type) && children.stream().anyMatch(Boolean::booleanValue));
    }

    private boolean containsPermission(Set<String> permissions, String expected) {
        if (permissions.contains(expected)) return true;
        if (expected.startsWith("APP.HCM:")) {
            return permissions.contains(
                    "APP.HRIS:" + expected.substring("APP.HCM:".length()));
        }
        if (expected.startsWith("APP.HRIS:")) {
            return permissions.contains(
                    "APP.HCM:" + expected.substring("APP.HRIS:".length()));
        }
        return false;
    }

    private boolean hasResourceRole(String header, JsonNode capability) {
        String prefix = "APP_RESOURCE_SET:";
        String resolver = capability.path("scopeResolver").asText();
        String responsibility = capability.path("requiredResponsibilityCode").asText();
        if (!resolver.startsWith(prefix) || responsibility.isBlank() || header == null) {
            return false;
        }
        String expected = (responsibility + '@' + resolver.substring(prefix.length()))
                .toUpperCase(Locale.ROOT);
        return Arrays.stream(header.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }

    private List<Binding> compile(ObjectNode projection) {
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
            require(stepUpBindings.size() == (route.has("stepUpCommandBindings")
                            ? route.path("stepUpCommandBindings").size() : 0),
                    routeKey + ": invalid step-up binding closure");
        }
        require(result.size() == 75, "HCM People PEP binding count changed");
        return List.copyOf(result);
    }

    private StepUpBinding stepUp(JsonNode value) {
        require("people".equals(value.path("ownerServiceKey").asText())
                        && "dwp-people-server".equals(value.path("audience").asText())
                        && "COMMAND_HEADER".equals(
                        value.path("expectedObjectVersionSource").asText())
                        && "X-DWP-Expected-Object-Version".equals(
                        value.path("expectedObjectVersionName").asText()),
                "Invalid People step-up owner/version binding");
        String pathParameter = value.path("targetIdPathParameter").asText(null);
        List<String> bodyFields = new ArrayList<>();
        if (value.has("targetIdBodyFields")) {
            value.path("targetIdBodyFields").forEach(field -> bodyFields.add(field.asText()));
        }
        require((pathParameter != null) != !bodyFields.isEmpty(),
                "Exactly one People step-up target source is required");
        return new StepUpBinding(
                value.path("targetType").asText(), pathParameter,
                List.copyOf(bodyFields), value.path("ownerServiceKey").asText(),
                value.path("audience").asText());
    }

    private void validateEnvelope(ObjectNode projection) {
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-people-pep-v3".equals(projection.path("projectionKey").asText())
                        && "people".equals(projection.path("ownerServiceKey").asText()),
                "Unexpected HCM People PEP envelope");
        JsonNode registry = projection.path("registryRef");
        require("product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 3
                        && W1B_V3_CHECKSUM.equals(registry.path("sha256").asText()),
                "HCM People registry reference mismatch");
        require(projection.path("sourceRegistryRouteCount").asInt() == 129
                        && projection.path("projectedRouteContractCount").asInt() == 48
                        && projection.path("bindingPairCount").asInt() == 75,
                "HCM People release counts changed");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null && checksum.asText().equals(sha256(payload)),
                "HCM People projection checksum mismatch");
    }

    private void validateClosure(ObjectNode projection) {
        require(capabilities.size() == 28 && policies.size() == 5
                        && expressions.size() == 3 && predicates.size() == 11,
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
    }

    private ObjectNode readProjection() {
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

    private Map<String, JsonNode> index(ObjectNode root, String field, String keyField) {
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

    private JsonNode descriptor(Map<String, JsonNode> values, String key, String label) {
        JsonNode value = values.get(key);
        if (value == null) throw new IllegalStateException("Unknown HCM " + label + ' ' + key);
        return value;
    }

    private Set<String> textValues(JsonNode value) {
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

    private static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value instanceof ArrayNode, field + " must be an array");
        return (ArrayNode) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record RequestEvidence(
            String method,
            String path,
            Set<String> permissions,
            String resourceRoles,
            String activeAccessMode,
            Set<String> supportScopes,
            String trustedRouteContractKey,
            String rawQuery) {

        public RequestEvidence {
            method = method == null ? "" : method.toUpperCase(Locale.ROOT);
            path = path == null ? "" : path;
            permissions = normalize(permissions);
            resourceRoles = resourceRoles == null ? "" : resourceRoles;
            activeAccessMode = activeAccessMode == null
                    ? "NORMAL" : activeAccessMode.toUpperCase(Locale.ROOT);
            supportScopes = normalize(supportScopes);
        }

        private static Set<String> normalize(Set<String> values) {
            if (values == null) return Set.of();
            return values.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public record Decision(boolean allowed, String denialCode, RouteAuthority authority) {
        static Decision allowed(RouteAuthority authority) {
            return new Decision(true, null, authority);
        }

        static Decision denied(String code) {
            return new Decision(false, code, null);
        }
    }

    public record RouteAuthority(
            String routeContractKey,
            String routeKind,
            String profileKey,
            boolean readOnly,
            Set<String> predicatePolicyKeys,
            Set<String> targetBindingKinds,
            String bindingKey,
            String capabilityContractKey,
            String activationPolicy,
            String method,
            String publicPath,
            StepUpBinding stepUpBinding,
            String projectionPolicyKey,
            String responseSchemaKey) {

        public RouteAuthority(
                String routeContractKey,
                String routeKind,
                String profileKey,
                boolean readOnly,
                Set<String> predicatePolicyKeys,
                Set<String> targetBindingKinds,
                String bindingKey,
                String capabilityContractKey,
                String activationPolicy,
                String method,
                String publicPath,
                StepUpBinding stepUpBinding) {
            this(routeContractKey, routeKind, profileKey, readOnly,
                    predicatePolicyKeys, targetBindingKinds, bindingKey,
                    capabilityContractKey, activationPolicy, method, publicPath,
                    stepUpBinding, null, null);
        }

        public boolean highRisk() {
            return stepUpBinding != null;
        }
    }

    public record StepUpBinding(
            String targetType,
            String targetIdPathParameter,
            List<String> targetIdBodyFields,
            String ownerServiceKey,
            String audience) {
    }

    public record BindingContract(
            String routeContractKey,
            String routeKind,
            String method,
            String servicePath) {
    }

    private record Profile(
            String profileKey,
            int precedence,
            boolean readOnly,
            Set<String> activeModes,
            Set<String> predicateKeys,
            Set<String> targetBindingKinds,
            JsonNode requiredAccess,
            Map<String, ProjectionBinding> projections) {
    }

    private record ProjectionBinding(
            String projectionPolicyKey,
            String responseSchemaKey) {
    }

    private record Binding(
            String routeContractKey,
            String routeKind,
            String bindingKey,
            String method,
            String publicPath,
            HcmPepBindingConstraints.PathTemplate claimPath,
            HcmPepBindingConstraints.PathTemplate path,
            HcmPepBindingConstraints.QueryConstraints query,
            List<Profile> profiles,
            StepUpBinding stepUp) {
    }
}
