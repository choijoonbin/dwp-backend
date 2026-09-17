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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owner-service consumer for the generated Workplace v24 route projection. */
@Component
public final class PlatformWorkplaceProductPepRegistry {

    public static final String POLICY_ID = "P-WORKPLACE";
    public static final String PRODUCT_ID = "workplace";
    public static final String OWNER_SERVICE = "dwp-platform-server";
    public static final String SERVICE_KEY = "platform";
    static final String RESOURCE =
            "product-authorization/platform-workplace-pep-v24.generated.json";
    static final String REGISTRY_CHECKSUM =
            "be3db891d27cd0b94aa88ac706d9bc87d4b991c9f9d8e505e26b296647728b84";
    private static final Set<String> EXACT_ROOM_BINDINGS = Set.of(
            "GET /v1/rooms/policy",
            "GET /v1/rooms/availability",
            "GET /v1/rooms/bookings",
            "POST /v1/rooms/bookings",
            "PUT /v1/rooms/bookings/{eventId}",
            "POST /v1/rooms/bookings/{eventId}/response",
            "POST /v1/rooms/bookings/{eventId}/cancel",
            "GET /v1/admin/rooms/overview",
            "GET /v1/admin/rooms/policy",
            "PUT /v1/admin/rooms/policy",
            "GET /v1/admin/rooms/bookings/pending",
            "POST /v1/admin/rooms/bookings/{bookingId}/decision",
            "POST /v1/admin/rooms/resources",
            "PUT /v1/admin/rooms/resources/{resourceId}");

    private final ObjectMapper objectMapper;
    private final List<Binding> bindings;

    public PlatformWorkplaceProductPepRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode projection = read();
        validateEnvelope(projection);
        this.bindings = compile(projection);
        validateClosure();
    }

    public boolean ownsOwner(String method, String path) {
        return method != null && path != null && bindings.stream()
                .anyMatch(binding -> binding.method().equals(method)
                        && binding.pathPattern().matcher(path).matches());
    }

    public Decision authorize(
            String trustedRouteContractKey,
            String method,
            String path,
            Set<String> permissions,
            String activeAccessMode) {
        List<Binding> matches = bindings.stream()
                .filter(binding -> binding.routeContractKey()
                        .equals(trustedRouteContractKey))
                .filter(binding -> binding.method().equals(method))
                .filter(binding -> binding.pathPattern().matcher(path).matches())
                .toList();
        if (matches.size() != 1) return Decision.denied();
        Binding binding = matches.getFirst();
        boolean permissionAllowed = "ALL".equals(binding.permissionMode())
                ? permissions.containsAll(binding.permissions())
                : binding.permissions().stream().anyMatch(permissions::contains);
        if (!binding.activeAccessModes().contains(activeAccessMode) || !permissionAllowed) {
            return Decision.denied();
        }
        return Decision.allowed(binding);
    }

    public List<BindingContract> bindingContracts() {
        return bindings.stream().map(binding -> new BindingContract(
                POLICY_ID,
                PRODUCT_ID,
                binding.surfaceKey(),
                binding.authorityType(),
                binding.authorityKey(),
                OWNER_SERVICE,
                SERVICE_KEY,
                binding.routeContractKey(),
                binding.routeKind(),
                binding.method(),
                "/api/platform" + binding.servicePath(),
                binding.servicePath(),
                binding.permissions(),
                binding.activeAccessModes(),
                !"ACTION".equals(binding.routeKind()))).toList();
    }

    private ObjectNode read() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Generated Workplace v24 PEP projection is absent.");
            }
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException(
                        "Generated Workplace v24 PEP projection must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated Workplace v24 PEP projection cannot be read.", exception);
        }
    }

    private void validateEnvelope(ObjectNode projection) {
        JsonNode registry = projection.path("registryRef");
        require(projection.path("schemaVersion").asInt() == 1
                        && "platform-workplace-pep-v24".equals(
                        projection.path("projectionKey").asText())
                        && SERVICE_KEY.equals(projection.path("ownerServiceKey").asText())
                        && "product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 24
                        && REGISTRY_CHECKSUM.equals(registry.path("sha256").asText()),
                "Unexpected Workplace v24 PEP envelope.");
        require(projection.path("sourceRegistryRouteCount").asInt() == 776
                        && projection.path("projectedRouteContractCount").asInt() == 326
                        && projection.path("bindingPairCount").asInt() == 328
                        && requiredArray(projection, "capabilities").size() == 26
                        && requiredArray(projection, "accessPolicies").size() == 1
                        && requiredArray(projection, "entitlementExpressions").size() == 1
                        && requiredArray(projection, "predicatePolicies").size() == 1,
                "Workplace v24 PEP release counts changed.");
        ObjectNode payload = projection.deepCopy();
        JsonNode expected = payload.remove("projectionChecksum");
        require(expected != null && expected.asText().equals(sha256(payload)),
                "Workplace v24 PEP projection checksum mismatch.");
    }

    private List<Binding> compile(ObjectNode projection) {
        Map<String, String> capabilities = new LinkedHashMap<>();
        for (JsonNode capability : requiredArray(projection, "capabilities")) {
            String key = requiredText(capability, "contractKey");
            String code = requiredText(capability, "resolvedCapabilityCode")
                    .toUpperCase(Locale.ROOT);
            require(capabilities.putIfAbsent(key, code) == null,
                    key + ": duplicate Workplace capability.");
        }
        Map<String, Set<String>> expressions = new LinkedHashMap<>();
        for (JsonNode expression : requiredArray(projection, "entitlementExpressions")) {
            String key = requiredText(expression, "expressionKey");
            require(expressions.putIfAbsent(
                    key, entitlementLeaves(expression.path("expression"))) == null,
                    key + ": duplicate Workplace entitlement expression.");
        }
        Map<String, Set<String>> policies = new LinkedHashMap<>();
        for (JsonNode policy : requiredArray(projection, "accessPolicies")) {
            String key = requiredText(policy, "accessPolicyKey");
            String expressionKey = requiredText(policy, "entitlementExpressionKey");
            Set<String> permissions = expressions.get(expressionKey);
            require(permissions != null && !permissions.isEmpty(),
                    key + ": Workplace policy entitlement closure is absent.");
            require(policies.putIfAbsent(key, permissions) == null,
                    key + ": duplicate Workplace access policy.");
        }

        List<Binding> compiled = new ArrayList<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = requiredText(route, "routeContractKey");
            String routeKind = requiredText(route, "routeKind");
            String surfaceKey = requiredText(route.path("subject"), "surfaceKey");
            require(PRODUCT_ID.equals(route.path("subject").path("productKey").asText()),
                    routeKey + ": non-Workplace product escaped projection.");
            require(Set.of("PAGE", "DATA", "ACTION").contains(routeKind),
                    routeKey + ": unsupported route kind.");
            require(Set.of("workplace.work", "workplace.management").contains(surfaceKey),
                    routeKey + ": unsupported Workplace surface.");

            ArrayNode profiles = requiredArray(route, "accessProfiles");
            require(profiles.size() == 1,
                    routeKey + ": exactly one Workplace access profile is required.");
            JsonNode profile = profiles.get(0);
            JsonNode access = profile.path("requiredAccess");
            String authorityType = requiredText(access, "type");
            String authorityKey;
            Set<String> permissions;
            String permissionMode;
            if ("CAPABILITY".equals(authorityType)) {
                authorityKey = requiredText(access, "capabilityContractKey");
                String permission = capabilities.get(authorityKey);
                require(permission != null, routeKey + ": capability closure is absent.");
                permissions = Set.of(permission);
                permissionMode = "ANY";
            } else if ("CAPABILITY_EXPRESSION".equals(authorityType)) {
                permissionMode = requiredText(access, "mode");
                require(Set.of("ANY", "ALL").contains(permissionMode),
                        routeKey + ": invalid capability expression mode.");
                Set<String> capabilityKeys = textValues(access.path("capabilityContractKeys"));
                Set<String> resolvedPermissions = new LinkedHashSet<>();
                capabilityKeys.stream().sorted().forEach(capabilityKey -> {
                    String permission = capabilities.get(capabilityKey);
                    require(permission != null,
                            routeKey + ": capability expression closure is absent.");
                    resolvedPermissions.add(permission);
                });
                require(!resolvedPermissions.isEmpty(),
                        routeKey + ": empty capability expression is forbidden.");
                permissions = Set.copyOf(resolvedPermissions);
                authorityKey = permissionMode + "(" + String.join(",",
                        capabilityKeys.stream().sorted().toList()) + ")";
            } else {
                require("POLICY".equals(authorityType),
                        routeKey + ": unsupported Workplace authority union.");
                authorityKey = requiredText(access, "accessPolicyKey");
                permissions = policies.get(authorityKey);
                require(permissions != null, routeKey + ": policy closure is absent.");
                permissionMode = "ANY";
            }
            Set<String> accessModes = textValues(profile.path("activeAccessModes"));
            require(!accessModes.isEmpty()
                            && Set.of("NORMAL", "ELEVATED").containsAll(accessModes),
                    routeKey + ": invalid active access modes.");

            Map<String, JsonNode> publicBindings = index(
                    route.path("gatewayApiBindings"), "bindingKey", routeKey);
            for (JsonNode service : requiredArray(route, "servicePepBindings")) {
                require(SERVICE_KEY.equals(service.path("serviceKey").asText()),
                        routeKey + ": foreign service binding escaped projection.");
                String bindingKey = requiredText(service, "bindingKey");
                JsonNode publicBinding = publicBindings.get(bindingKey);
                require(publicBinding != null
                                && service.path("method").asText()
                                .equals(publicBinding.path("method").asText())
                                && publicBinding.path("path").asText()
                                .equals("/api/platform" + service.path("path").asText()),
                        routeKey + ": public/service binding mismatch.");
                String servicePath = requiredText(service, "path");
                require(withinOwnerFamily(requiredText(service, "method"), servicePath),
                        routeKey + ": binding escaped Workplace ownership.");
                compiled.add(new Binding(
                        routeKey,
                        routeKind,
                        surfaceKey,
                        authorityType,
                        authorityKey,
                        requiredText(service, "method"),
                        servicePath,
                        pathPattern(servicePath, service.path("pathParameterConstraints")),
                        permissions,
                        permissionMode,
                        accessModes));
            }
        }
        return List.copyOf(compiled);
    }

    private void validateClosure() {
        Set<String> routeKeys = bindings.stream().map(Binding::routeContractKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        require(routeKeys.size() == 326 && bindings.size() == 328,
                "Workplace PEP route or binding closure drifted from v24.");
        require(routeKeys.containsAll(Set.of(
                        "route.workplace.work.home.page",
                        "route.workplace.work.wayfinding.page",
                        "route.workplace.work.assistant.page",
                        "route.workplace.work.safety.page",
                        "route.workplace.management.devices.page",
                        "route.workplace.management.service-providers.page",
                        "route.workplace.management.space-planning.page",
                        "route.workplace.management.assistant-governance.page",
                        "route.workplace.management.governance.page",
                        "route.workplace.management.safety.page",
                        "route.workplace.management.visits.page",
                        "route.workplace.management.visit-policies.page",
                        "route.workplace.management.access-zones.page",
                        "route.workplace.management.visit-providers.page",
                        "route.workplace.management.kiosk-devices.page",
                        "route.workplace.management.overview.page",
                        "route.workplace.management.operations.page",
                        "route.workplace.management.exceptions.page",
                        "route.workplace.management.locations.page",
                        "route.workplace.management.policy.page",
                        "route.workplace.management.room-operations.page",
                        "route.workplace.management.room-policy.page")),
                "Workplace v24 page closure is incomplete.");
        require(bindings.stream().map(Binding::routeKind).collect(
                        java.util.stream.Collectors.toSet())
                        .equals(Set.of("PAGE", "DATA", "ACTION")),
                "Workplace PEP must close PAGE, DATA and ACTION.");
        require(bindings.stream().noneMatch(binding ->
                        binding.servicePath().equals("/v1/workplace/kiosk")
                                || binding.servicePath().startsWith("/v1/workplace/kiosk/")
                                || binding.servicePath().startsWith("/v1/workplace/devices/")
                                || binding.servicePath().startsWith("/v1/device/workplace/")),
                "Workplace DEVICE identity-plane route escaped into the human PEP.");
        require(bindings.stream()
                        .filter(binding -> binding.servicePath().startsWith("/v1/rooms/")
                                || binding.servicePath().startsWith("/v1/admin/rooms/"))
                        .map(binding -> binding.method() + " " + binding.servicePath())
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(EXACT_ROOM_BINDINGS),
                "Workplace v24 exact room support closure drifted.");

        Map<String, Set<String>> methodPaths = new LinkedHashMap<>();
        for (Binding binding : bindings) {
            String methodPath = binding.method() + " " + binding.servicePath();
            methodPaths.computeIfAbsent(methodPath, ignored -> new LinkedHashSet<>())
                    .add(binding.routeContractKey());
        }
        Set<String> exploreAliases = Set.of(
                "route.workplace.work.explore.page",
                "route.workplace.work.find.page",
                "route.workplace.work.home.page",
                "route.workplace.work.planner.page");
        methodPaths.forEach((methodPath, keys) -> {
            if (keys.size() > 1) {
                require(methodPath.equals("GET /v1/workplace/explore")
                                && keys.equals(exploreAliases),
                        "Duplicate Workplace binding " + methodPath);
            }
        });
    }

    private Pattern pathPattern(String template, JsonNode constraints) {
        Matcher matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}").matcher(template);
        StringBuilder expression = new StringBuilder("^");
        int offset = 0;
        while (matcher.find()) {
            expression.append(Pattern.quote(template.substring(offset, matcher.start())));
            JsonNode constraint = constraints.path(matcher.group(1));
            if (constraint.isMissingNode()) {
                expression.append("[A-Za-z0-9_-]+");
            } else {
                require("ALLOWLIST".equals(constraint.path("kind").asText())
                                && constraint.path("values").isArray()
                                && !constraint.path("values").isEmpty(),
                        "Invalid Workplace path parameter constraint.");
                expression.append("(?:");
                boolean first = true;
                for (JsonNode value : constraint.path("values")) {
                    require(value.isTextual() && !value.asText().isBlank(),
                            "Invalid Workplace path allowlist value.");
                    if (!first) expression.append('|');
                    expression.append(Pattern.quote(value.asText()));
                    first = false;
                }
                expression.append(')');
            }
            offset = matcher.end();
        }
        expression.append(Pattern.quote(template.substring(offset))).append('$');
        return Pattern.compile(expression.toString());
    }

    private Set<String> entitlementLeaves(JsonNode expression) {
        String type = requiredText(expression, "type");
        if ("LEAF".equals(type)) {
            return Set.of(requiredText(expression, "entitlement").toUpperCase(Locale.ROOT));
        }
        require(Set.of("AND", "OR").contains(type),
                "Unsupported Workplace entitlement expression.");
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode child : requiredArray(expression, "children")) {
            result.addAll(entitlementLeaves(child));
        }
        require(!result.isEmpty(), "Empty Workplace entitlement expression.");
        return Set.copyOf(result);
    }

    private Map<String, JsonNode> index(JsonNode values, String keyField, String label) {
        require(values.isArray(), label + " must be an array.");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        values.forEach(value -> {
            String key = requiredText(value, keyField);
            require(result.putIfAbsent(key, value) == null,
                    label + ": duplicate binding key.");
        });
        return Map.copyOf(result);
    }

    private Set<String> textValues(JsonNode values) {
        require(values.isArray(), "Expected a generated string array.");
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> require(value.isTextual()
                        && !value.asText().isBlank() && result.add(value.asText()),
                "Invalid generated string array."));
        return Set.copyOf(result);
    }

    private boolean withinOwnerFamily(String method, String path) {
        return path.equals("/v1/workplace")
                || path.startsWith("/v1/workplace/")
                || path.equals("/v1/admin/workplace")
                || path.startsWith("/v1/admin/workplace/")
                || EXACT_ROOM_BINDINGS.contains(method + " " + path);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        require(value != null && !value.isBlank() && value.equals(value.trim()),
                "Workplace PEP field " + field + " is missing or non-canonical.");
        return value;
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Workplace PEP checksum failed.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(
                    name -> result.set(name, canonical(value.get(name))));
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
        require(value instanceof ArrayNode, field + " must be an array.");
        return (ArrayNode) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Binding(
            String routeContractKey,
            String routeKind,
            String surfaceKey,
            String authorityType,
            String authorityKey,
            String method,
            String servicePath,
            Pattern pathPattern,
            Set<String> permissions,
            String permissionMode,
            Set<String> activeAccessModes) {
    }

    public record BindingContract(
            String policyId,
            String productId,
            String surfaceKey,
            String authorityType,
            String authorityKey,
            String ownerService,
            String serviceKey,
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            String servicePath,
            Set<String> resolvedAuthorities,
            Set<String> activeAccessModes,
            boolean readOnly) {
    }

    public record Decision(boolean allowed, Binding binding) {
        static Decision allowed(Binding binding) {
            return new Decision(true, binding);
        }

        static Decision denied() {
            return new Decision(false, null);
        }
    }
}
