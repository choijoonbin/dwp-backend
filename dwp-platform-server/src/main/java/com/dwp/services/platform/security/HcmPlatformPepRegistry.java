package com.dwp.services.platform.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Closed Platform-owned HCM PEP projection for the v40 SYS navigation-policy handoff. */
@Component
public final class HcmPlatformPepRegistry {

    static final String RESOURCE =
            "product-authorization/hcm-platform-pep-v40.generated.json";
    static final String REGISTRY_V40_CHECKSUM =
            "4dca145cbc9169c2d1fde71771b83a1a0635679a7190a39d0a21b4e76bbb9329";
    private static final String CONFIGURATION_ROOT = "/v1/admin/hris/configurations";
    private static final String WORKBENCH_PATH = "/v1/hris/workbench/settings-reference";
    private static final String NAVIGATION_POLICY_ROOT =
            "/v1/admin/hris/navigation-policies";
    private static final String NAVIGATION_PROJECTION_PATH =
            "/v1/hris/navigation/projection";

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> capabilities;
    private final List<Binding> bindings;

    public HcmPlatformPepRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode projection = readProjection();
        validateEnvelope(projection);
        capabilities = index(requiredArray(projection, "capabilities"),
                "contractKey", "capabilities");
        bindings = compile(projection);
        validateClosure(projection);
    }

    /** Claims only the canonical HRIS configuration families, never generic /v1/admin. */
    public boolean governsPathFamily(String path) {
        if (path == null) return false;
        return claimed(path) || claimed(decodeForClaim(path));
    }

    private boolean claimed(String path) {
        return path.equals(WORKBENCH_PATH)
                || path.startsWith(WORKBENCH_PATH + ";")
                || path.equals(NAVIGATION_PROJECTION_PATH)
                || path.startsWith(NAVIGATION_PROJECTION_PATH + ";")
                || path.equals(CONFIGURATION_ROOT)
                || path.startsWith(CONFIGURATION_ROOT + "/")
                || path.startsWith(CONFIGURATION_ROOT + ";")
                || path.startsWith(CONFIGURATION_ROOT + "%")
                || path.equals(NAVIGATION_POLICY_ROOT)
                || path.startsWith(NAVIGATION_POLICY_ROOT + "/")
                || path.startsWith(NAVIGATION_POLICY_ROOT + ";")
                || path.startsWith(NAVIGATION_POLICY_ROOT + "%");
    }

    public Decision authorize(RequestEvidence evidence) {
        if (!governsPathFamily(evidence.path())) {
            return Decision.denied("NOT_HCM_PLATFORM_ROUTE");
        }
        if (evidence.trustedRouteContractKey() == null
                || evidence.trustedRouteContractKey().isBlank()) {
            return Decision.denied("TRUSTED_ROUTE_KEY_REQUIRED");
        }
        List<Binding> matches = bindings.stream()
                .filter(binding -> binding.routeContractKey()
                        .equals(evidence.trustedRouteContractKey()))
                .filter(binding -> binding.method().equals(evidence.method()))
                .filter(binding -> binding.path().matches(evidence.path()))
                .toList();
        if (matches.size() != 1) {
            return Decision.denied(matches.isEmpty()
                    ? "UNKNOWN_METHOD_PATH_BINDING" : "AMBIGUOUS_METHOD_PATH_BINDING");
        }
        Binding binding = matches.getFirst();
        Profile profile = binding.profiles().stream()
                .sorted(Comparator.comparingInt(Profile::precedence).reversed())
                .filter(value -> value.allows(evidence.permissions(), capabilities))
                .findFirst().orElse(null);
        if (profile == null) return Decision.denied("EXACT_ROUTE_AUTHORITY_REQUIRED");
        String capabilityKey = profile.requiredAccess().path(
                "capabilityContractKey").asText(null);
        JsonNode capability = capabilityKey == null ? null : capabilities.get(capabilityKey);
        return Decision.allowed(new RouteAuthority(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.path().template(), profile.profileKey(), binding.highRisk(),
                capabilityKey,
                capability == null ? null : capability.path("activationPolicy").asText(null),
                binding.stepUpCommandBinding()));
    }

    public List<BindingContract> bindingContracts() {
        return bindings.stream().map(binding -> new BindingContract(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.path().template(), binding.highRisk())).toList();
    }

    private List<Binding> compile(ObjectNode projection) {
        List<Binding> result = new ArrayList<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            List<Profile> profiles = new ArrayList<>();
            for (JsonNode profile : requiredArray(route, "accessProfiles")) {
                profiles.add(new Profile(
                        profile.path("profileKey").asText(),
                        profile.path("precedence").asInt(),
                        profile.path("requiredAccess").deepCopy()));
            }
            for (JsonNode service : requiredArray(route, "servicePepBindings")) {
                require("platform".equals(service.path("serviceKey").asText()),
                        routeKey + ": foreign service binding escaped HCM Platform PEP");
                String path = service.path("path").asText();
                if (!claimed(path)) continue;
                StepUpCommandBinding stepUp = stepUpBinding(
                        route, service.path("bindingKey").asText());
                result.add(new Binding(
                        routeKey,
                        route.path("routeKind").asText(),
                        service.path("method").asText(),
                        PathTemplate.compile(path),
                        List.copyOf(profiles),
                        stepUp != null,
                        stepUp));
            }
        }
        require(result.size() == 21, "HCM Platform SYS binding count changed");
        return List.copyOf(result);
    }

    private StepUpCommandBinding stepUpBinding(JsonNode route, String bindingKey) {
        JsonNode values = route.path("stepUpCommandBindings");
        if (!values.isArray()) return null;
        List<JsonNode> matches = new ArrayList<>();
        values.forEach(value -> {
            if (bindingKey.equals(value.path("bindingKey").asText())) matches.add(value);
        });
        require(matches.size() <= 1, bindingKey + ": ambiguous step-up binding");
        if (matches.isEmpty()) return null;
        JsonNode value = matches.getFirst();
        return new StepUpCommandBinding(
                value.path("targetType").asText(),
                value.path("targetIdPathParameter").asText(null),
                value.path("expectedObjectVersionSource").asText(),
                value.path("expectedObjectVersionName").asText(),
                value.path("ownerServiceKey").asText(),
                value.path("audience").asText());
    }

    private void validateEnvelope(ObjectNode projection) {
        JsonNode registry = projection.path("registryRef");
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-platform-pep-v40".equals(
                        projection.path("projectionKey").asText())
                        && "platform".equals(projection.path("ownerServiceKey").asText())
                        && "product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 40
                        && REGISTRY_V40_CHECKSUM.equals(registry.path("sha256").asText()),
                "Unexpected HCM Platform PEP envelope");
        require(projection.path("sourceRegistryRouteCount").asInt() == 1012
                        && projection.path("projectedRouteContractCount").asInt() == 28
                        && projection.path("bindingPairCount").asInt() == 29
                        && requiredArray(projection, "capabilities").size() == 15
                        && requiredArray(projection, "accessPolicies").size() == 4
                        && requiredArray(projection, "entitlementExpressions").size() == 3
                        && requiredArray(projection, "predicatePolicies").size() == 4,
                "HCM Platform projection closure count changed");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null && checksum.asText().equals(sha256(payload)),
                "HCM Platform projection checksum mismatch");
    }

    private void validateClosure(ObjectNode projection) {
        for (Binding binding : bindings) {
            require(binding.routeContractKey().startsWith("route.hcm.configuration.")
                            || binding.routeContractKey().equals(
                            "route.hcm.operations.navigation-projection.data"),
                    "Non-configuration route entered the SYS handoff");
            for (Profile profile : binding.profiles()) {
                JsonNode access = profile.requiredAccess();
                String type = access.path("type").asText();
                if ("CAPABILITY".equals(type)) {
                    require(capabilities.containsKey(
                                    access.path("capabilityContractKey").asText()),
                            "Unknown HCM Platform capability");
                } else if ("CAPABILITY_EXPRESSION".equals(type)) {
                    require("ANY".equals(access.path("mode").asText())
                                    && access.path("capabilityContractKeys").isArray()
                                    && !access.path("capabilityContractKeys").isEmpty(),
                            "Invalid HCM Platform capability expression");
                    access.path("capabilityContractKeys").forEach(value -> require(
                            capabilities.containsKey(value.asText()),
                            "Unknown HCM Platform expression capability"));
                } else {
                    throw new IllegalStateException(
                            "Policy access is forbidden in the SYS exact handoff");
                }
            }
        }
        Set<String> routeKeys = bindings.stream()
                .map(Binding::routeContractKey).collect(Collectors.toUnmodifiableSet());
        require(routeKeys.size() == 21
                        && routeKeys.contains("route.hcm.configuration.settings-reference.page")
                        && routeKeys.contains(
                        "route.hcm.configuration.configuration-version-publish.action")
                        && routeKeys.contains(
                        "route.hcm.configuration.navigation-policy-revision-publish.action")
                        && routeKeys.contains(
                        "route.hcm.operations.navigation-projection.data"),
                "HCM Platform SYS route closure changed");
    }

    private ObjectNode readProjection() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Generated HCM Platform PEP is absent.");
            }
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated HCM Platform PEP must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated HCM Platform PEP cannot be read.", exception);
        }
    }

    private Map<String, JsonNode> index(
            JsonNode values, String keyField, String label) {
        require(values.isArray(), label + " must be an array");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        values.forEach(value -> {
            String key = value.path(keyField).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    label + ": duplicate or empty key");
        });
        return Map.copyOf(result);
    }

    private String decodeForClaim(String path) {
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

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("HCM Platform checksum failed.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name ->
                    result.set(name, canonical(value.get(name))));
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
            String trustedRouteContractKey) {
        public RequestEvidence {
            method = method == null ? "" : method.toUpperCase(Locale.ROOT);
            path = path == null ? "" : path;
            permissions = permissions == null ? Set.of() : permissions.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public record Decision(
            boolean allowed,
            String denialCode,
            RouteAuthority authority) {
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
            String method,
            String servicePath,
            String profileKey,
            boolean highRisk,
            String capabilityContractKey,
            String activationPolicy,
            StepUpCommandBinding stepUpCommandBinding) {
    }

    public record StepUpCommandBinding(
            String targetType,
            String targetIdPathParameter,
            String expectedObjectVersionSource,
            String expectedObjectVersionName,
            String ownerServiceKey,
            String audience) {
    }

    public record BindingContract(
            String routeContractKey,
            String routeKind,
            String method,
            String servicePath,
            boolean highRisk) {
    }

    private record Binding(
            String routeContractKey,
            String routeKind,
            String method,
            PathTemplate path,
            List<Profile> profiles,
            boolean highRisk,
            StepUpCommandBinding stepUpCommandBinding) {
    }

    private record Profile(
            String profileKey,
            int precedence,
            JsonNode requiredAccess) {
        boolean allows(Set<String> permissions, Map<String, JsonNode> capabilities) {
            String type = requiredAccess.path("type").asText();
            if ("CAPABILITY".equals(type)) {
                return hasCapability(requiredAccess.path(
                        "capabilityContractKey").asText(), permissions, capabilities);
            }
            if (!"CAPABILITY_EXPRESSION".equals(type)) return false;
            List<String> keys = new ArrayList<>();
            requiredAccess.path("capabilityContractKeys").forEach(value ->
                    keys.add(value.asText()));
            boolean all = "ALL".equals(requiredAccess.path("mode").asText());
            return all
                    ? keys.stream().allMatch(key ->
                    hasCapability(key, permissions, capabilities))
                    : keys.stream().anyMatch(key ->
                    hasCapability(key, permissions, capabilities));
        }

        private boolean hasCapability(
                String key,
                Set<String> permissions,
                Map<String, JsonNode> capabilities) {
            JsonNode capability = capabilities.get(key);
            return capability != null && permissions.contains(
                    capability.path("resolvedCapabilityCode").asText()
                            .toUpperCase(Locale.ROOT));
        }
    }

    private record PathTemplate(Pattern pattern, String template) {
        static PathTemplate compile(String template) {
            StringBuilder expression = new StringBuilder("^");
            Matcher matcher = Pattern.compile(
                    "\\{([A-Za-z][A-Za-z0-9]*)}").matcher(template);
            int offset = 0;
            while (matcher.find()) {
                expression.append(Pattern.quote(
                                template.substring(offset, matcher.start())))
                        .append("([^/;]+)");
                offset = matcher.end();
            }
            expression.append(Pattern.quote(template.substring(offset))).append('$');
            return new PathTemplate(Pattern.compile(expression.toString()), template);
        }

        boolean matches(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            return !normalized.contains("%")
                    && !normalized.contains("\\")
                    && !normalized.contains("//")
                    && !normalized.contains("/./")
                    && !normalized.contains("/../")
                    && !normalized.contains(";")
                    && pattern.matcher(value).matches();
        }
    }
}
