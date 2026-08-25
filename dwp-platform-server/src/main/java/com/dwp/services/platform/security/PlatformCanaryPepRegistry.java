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
import java.util.stream.StreamSupport;

/**
 * Runtime view of the generated Communications and Services Platform PEP projection.
 *
 * <p>The class intentionally has no handwritten capability, policy, predicate, or route table.
 * Every decision input is resolved from the canonical registry projection.</p>
 */
@Component
public final class PlatformCanaryPepRegistry {

    static final String RESOURCE =
            "product-authorization/platform-canary-pep-v1.generated.json";
    static final String IMMUTABLE_V1_CHECKSUM =
            "bc34f47b0ad783d27aa7979f25f75e2fdf29506a12a23c0088f94837abad0b67";
    private final ObjectMapper objectMapper;
    private final Set<String> ownedPathRoots;
    private final Map<String, JsonNode> capabilities;
    private final Map<String, JsonNode> policies;
    private final Map<String, JsonNode> expressions;
    private final Map<String, JsonNode> predicates;
    private final Map<String, Set<String>> routePredicates;
    private final List<CompiledBinding> bindings;
    private final List<BindingContract> bindingContracts;

    public PlatformCanaryPepRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode projection = readProjection();
        validateEnvelope(projection);
        this.ownedPathRoots = textValues(projection.path("ownedPathRoots"));
        this.capabilities = index(projection, "capabilities", "contractKey");
        this.policies = index(projection, "accessPolicies", "accessPolicyKey");
        this.expressions = index(projection, "entitlementExpressions", "expressionKey");
        this.predicates = index(projection, "predicatePolicies", "predicatePolicyKey");
        this.routePredicates = indexRoutePredicates(projection);
        CompiledProjection compiled = compile(projection);
        this.bindings = compiled.bindings();
        this.bindingContracts = compiled.contracts();
        validateClosure(projection);
    }

    public boolean ownsPath(String path) {
        if (path == null) return false;
        return ownedPathRoots.stream()
                .anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
    }

    public boolean ownsRouteContractKey(String routeContractKey) {
        return routeContractKey != null && bindings.stream().anyMatch(
                binding -> binding.routeContractKey().equals(routeContractKey));
    }

    public Decision authorize(RequestEvidence evidence) {
        if (!ownsPath(evidence.path())) {
            return Decision.denied("NOT_CANARY_ROUTE");
        }
        if (!ownsRouteContractKey(evidence.trustedRouteContractKey())) {
            return Decision.denied("TRUSTED_ROUTE_KEY_REQUIRED");
        }
        CompiledBinding trusted = bindings.stream()
                .filter(binding -> binding.routeContractKey()
                        .equals(evidence.trustedRouteContractKey()))
                .findFirst().orElseThrow();
        List<CompiledBinding> matches = collapseEquivalent(bindings.stream()
                .filter(binding -> binding.method().equals(evidence.method()))
                .filter(binding -> binding.matches(evidence.path()))
                .filter(binding -> sameAuthority(binding, trusted))
                .toList());
        if (matches.isEmpty()) {
            return Decision.denied("UNKNOWN_METHOD_PATH_BINDING");
        }

        String mode = evidence.supportSessionId() == null
                || evidence.supportSessionId().isBlank()
                ? "NORMAL" : "PROVIDER_SUPPORT";
        Set<String> allowedRoutes = new LinkedHashSet<>();
        for (CompiledBinding binding : matches) {
            if (StreamSupport.stream(binding.profiles().spliterator(), false)
                    .anyMatch(profile -> profileAllows(profile, mode, evidence))) {
                allowedRoutes.add(binding.routeContractKey());
            }
        }
        return allowedRoutes.isEmpty()
                ? Decision.denied("EXACT_ROUTE_AUTHORITY_REQUIRED")
                : Decision.allowed(List.copyOf(allowedRoutes));
    }

    private boolean sameAuthority(CompiledBinding candidate, CompiledBinding trusted) {
        if (candidate.routeContractKey().equals(trusted.routeContractKey())) return true;
        return trusted.authorizationEquivalenceKey() != null
                && trusted.authorizationEquivalenceKey()
                .equals(candidate.authorizationEquivalenceKey());
    }

    private List<CompiledBinding> collapseEquivalent(List<CompiledBinding> matches) {
        if (matches.size() < 2) return matches;
        String equivalence = matches.getFirst().authorizationEquivalenceKey();
        if (equivalence == null || matches.stream().anyMatch(
                binding -> !equivalence.equals(binding.authorizationEquivalenceKey()))) {
            return List.of();
        }
        return List.of(matches.stream().min(java.util.Comparator.comparing(
                CompiledBinding::routeContractKey)).orElseThrow());
    }

    public List<BindingContract> bindingContracts() {
        return bindingContracts;
    }

    String capabilityCode(String contractKey) {
        JsonNode descriptor = requireDescriptor(capabilities, contractKey, "capability");
        return descriptor.path("resolvedCapabilityCode").asText();
    }

    boolean predicatesCover(List<String> routeContractKeys, Set<String> requiredPredicates) {
        if (routeContractKeys == null || routeContractKeys.isEmpty()
                || requiredPredicates == null || requiredPredicates.isEmpty()) {
            return false;
        }
        if (routeContractKeys.stream().anyMatch(key -> !routePredicates.containsKey(key))) {
            return false;
        }
        return routeContractKeys.stream()
                .map(routePredicates::get)
                .anyMatch(values -> values.containsAll(requiredPredicates));
    }

    private boolean profileAllows(
            JsonNode profile,
            String mode,
            RequestEvidence evidence) {
        if (!textValues(profile.path("activeAccessModes")).contains(mode)) return false;
        JsonNode access = profile.path("requiredAccess");
        return switch (access.path("type").asText()) {
            case "CAPABILITY" -> capabilityAllows(
                    access.path("capabilityContractKey").asText(), evidence);
            case "CAPABILITY_EXPRESSION" -> expressionAllows(
                    access.path("mode").asText(),
                    textValues(access.path("capabilityContractKeys")).stream()
                            .map(key -> capabilityAllows(key, evidence))
                            .toList());
            case "POLICY" -> policyAllows(
                    access.path("accessPolicyKey").asText(),
                    mode,
                    profile.path("readOnly").asBoolean(),
                    evidence);
            default -> false;
        };
    }

    private boolean policyAllows(
            String policyKey,
            String mode,
            boolean readOnly,
            RequestEvidence evidence) {
        JsonNode policy = requireDescriptor(policies, policyKey, "policy");
        if ("SINGLE".equals(policy.path("evaluationType").asText())) {
            if (!"ENTITLEMENT".equals(policy.path("authorityMode").asText())
                    || !policy.path("requiresProductEntitlement").asBoolean()) {
                return false;
            }
            JsonNode expression = requireDescriptor(
                    expressions,
                    policy.path("entitlementExpressionKey").asText(),
                    "entitlement expression");
            return entitlementAllows(expression.path("expression"), evidence.permissions());
        }
        for (JsonNode branch : policy.path("modeBranches")) {
            if (!mode.equals(branch.path("activeAccessMode").asText())) continue;
            if ("CAPABILITY".equals(branch.path("resultGrantKind").asText())) {
                return expressionAllows(
                        branch.path("capabilityMode").asText(),
                        textValues(branch.path("capabilityContractKeys")).stream()
                                .map(key -> capabilityAllows(key, evidence))
                                .toList());
            }
            return "PROVIDER_SUPPORT".equals(mode)
                    && readOnly
                    && "GET".equals(evidence.method())
                    && positiveLong(evidence.actorTenantId())
                    && evidence.supportScopes().contains("TENANT_CONFIGURATION_READ")
                    && textValues(branch.path("supportScopes"))
                            .contains("TENANT_CONFIGURATION_READ");
        }
        return false;
    }

    private boolean capabilityAllows(String contractKey, RequestEvidence evidence) {
        JsonNode capability = requireDescriptor(capabilities, contractKey, "capability");
        String exactCode = capability.path("resolvedCapabilityCode")
                .asText().toUpperCase(Locale.ROOT);
        if (!evidence.permissions().contains(exactCode)) return false;
        if (!"REQUIRED".equals(capability.path("responsibilityRequirement").asText())) {
            return true;
        }
        String resolver = capability.path("scopeResolver").asText();
        String prefix = "APP_RESOURCE_SET:";
        return resolver.startsWith(prefix)
                && ResourceRoleAuthorization.has(
                        evidence.resourceRoles(),
                        "APP_CONFIG_ADMIN",
                        resolver.substring(prefix.length()));
    }

    private boolean entitlementAllows(JsonNode expression, Set<String> permissions) {
        String type = expression.path("type").asText();
        if ("LEAF".equals(type)) {
            return permissions.contains(
                    expression.path("entitlement").asText().toUpperCase(Locale.ROOT));
        }
        List<Boolean> children = new ArrayList<>();
        expression.path("children").forEach(child ->
                children.add(entitlementAllows(child, permissions)));
        return expressionAllows(type, children);
    }

    private boolean expressionAllows(String mode, List<Boolean> values) {
        if (values.isEmpty()) return false;
        return switch (mode) {
            case "ALL" -> values.stream().allMatch(Boolean::booleanValue);
            case "ANY" -> values.stream().anyMatch(Boolean::booleanValue);
            default -> false;
        };
    }

    private CompiledProjection compile(ObjectNode projection) {
        List<CompiledBinding> compiled = new ArrayList<>();
        List<BindingContract> contracts = new ArrayList<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            String product = route.path("subject").path("productKey").asText();
            String surface = route.path("subject").path("surfaceKey").asText();
            Map<String, JsonNode> gateway = indexArray(
                    route.path("gatewayApiBindings"), "bindingKey", routeKey);
            for (JsonNode service : route.path("servicePepBindings")) {
                String bindingKey = service.path("bindingKey").asText();
                JsonNode publicBinding = gateway.get(bindingKey);
                require(publicBinding != null, routeKey + ": public binding missing");
                require("platform".equals(service.path("serviceKey").asText()),
                        routeKey + ": wrong PEP owner");
                require(service.path("method").asText()
                                .equals(publicBinding.path("method").asText()),
                        routeKey + ": public/service method mismatch");
                PathTemplate template = PathTemplate.compile(
                        service.path("path").asText(),
                        service.path("pathParameterConstraints"));
                compiled.add(new CompiledBinding(
                        routeKey,
                        route.path("routeKind").asText(),
                        textOrNull(route, "authorizationEquivalenceKey"),
                        product,
                        surface,
                        service.path("method").asText(),
                        template,
                        requiredArray(route, "accessProfiles")));
                contracts.add(new BindingContract(
                        routeKey,
                        route.path("routeKind").asText(),
                        product,
                        surface,
                        service.path("method").asText(),
                        publicBinding.path("path").asText(),
                        service.path("path").asText()));
            }
        }
        require(compiled.size() == projection.path("bindingPairCount").asInt(),
                "Platform Canary PEP binding count mismatch");
        return new CompiledProjection(List.copyOf(compiled), List.copyOf(contracts));
    }

    private void validateEnvelope(ObjectNode projection) {
        require(projection.path("schemaVersion").asInt() == 1,
                "Unsupported Platform Canary PEP schema");
        require("platform-canary-pep-v1".equals(projection.path("projectionKey").asText()),
                "Unexpected Platform Canary PEP key");
        require("platform".equals(projection.path("ownerServiceKey").asText()),
                "Unexpected Platform Canary PEP owner");
        JsonNode registry = projection.path("registryRef");
        require("product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 1
                        && IMMUTABLE_V1_CHECKSUM.equals(registry.path("sha256").asText()),
                "Platform Canary PEP registry reference mismatch");
        require(projection.path("sourceRegistryRouteCount").asInt() == 35
                        && projection.path("projectedRouteContractCount").asInt() == 33
                        && projection.path("bindingPairCount").asInt() == 36,
                "Platform Canary PEP release counts changed");
        Set<String> roots = textValues(projection.path("ownedPathRoots"));
        require(roots.size() == 4 && roots.stream().allMatch(root -> root.startsWith("/v1/")),
                "Platform Canary PEP owned path roots changed");
        require("SHA-256".equals(projection.path("projectionChecksumAlgorithm").asText()),
                "Unsupported Platform Canary PEP checksum");
        ObjectNode payload = projection.deepCopy();
        String actual = payload.remove("projectionChecksum").asText();
        require(actual.equals(sha256(payload)), "Platform Canary PEP checksum mismatch");
    }

    private void validateClosure(ObjectNode projection) {
        require(capabilities.size() == 10 && policies.size() == 3
                        && expressions.size() == 2 && predicates.size() == 5,
                "Platform Canary PEP descriptor closure count changed");
        Set<String> routeKeys = new LinkedHashSet<>();
        for (JsonNode route : projection.path("routes")) {
            String routeKey = route.path("routeContractKey").asText();
            require(routeKeys.add(routeKey), "Duplicate Platform Canary route " + routeKey);
            for (JsonNode profile : route.path("accessProfiles")) {
                JsonNode access = profile.path("requiredAccess");
                switch (access.path("type").asText()) {
                    case "CAPABILITY" -> requireDescriptor(
                            capabilities, access.path("capabilityContractKey").asText(), "capability");
                    case "POLICY" -> requireDescriptor(
                            policies, access.path("accessPolicyKey").asText(), "policy");
                    default -> throw new IllegalStateException(
                            routeKey + ": unsupported Canary access type");
                }
                for (String predicateKey : textValues(profile.path("predicatePolicyKeys"))) {
                    JsonNode predicate = requireDescriptor(predicates, predicateKey, "predicate");
                    require("platform".equals(predicate.path("ownerServiceKey").asText())
                                    && textValues(predicate.path("routeContractKeys"))
                                    .contains(routeKey),
                            routeKey + ": predicate owner or allowlist mismatch");
                }
            }
        }
        require(routeKeys.size() == 33, "Platform Canary route closure changed");
    }

    private ObjectNode readProjection() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException(
                    "Generated Platform Canary PEP is absent from the runtime classpath.");
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated Platform Canary PEP must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException("Generated Platform Canary PEP cannot be read.", exception);
        }
    }

    private Map<String, JsonNode> index(ObjectNode root, String field, String keyField) {
        return indexArray(requiredArray(root, field), keyField, field);
    }

    private Map<String, Set<String>> indexRoutePredicates(ObjectNode projection) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            Set<String> keys = new LinkedHashSet<>();
            route.path("accessProfiles").forEach(profile ->
                    keys.addAll(textValues(profile.path("predicatePolicyKeys"))));
            String routeKey = route.path("routeContractKey").asText();
            require(result.putIfAbsent(routeKey, Set.copyOf(keys)) == null,
                    "Duplicate Platform Canary predicate route " + routeKey);
        }
        return Map.copyOf(result);
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

    private JsonNode requireDescriptor(Map<String, JsonNode> values, String key, String label) {
        JsonNode descriptor = values.get(key);
        if (descriptor == null) throw new IllegalStateException(
                "Unknown Platform Canary " + label + " " + key);
        return descriptor;
    }

    private Set<String> textValues(JsonNode value) {
        require(value.isArray(), "Expected a generated string array");
        Set<String> result = new LinkedHashSet<>();
        value.forEach(item -> require(item.isTextual() && result.add(item.asText()),
                "Generated string array contains an invalid or duplicate value"));
        return Set.copyOf(result);
    }

    private String sha256(JsonNode value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical(value));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Platform Canary PEP checksum failed.", exception);
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

    private static boolean positiveLong(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException | NullPointerException exception) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record RequestEvidence(
            String method,
            String path,
            Set<String> permissions,
            String resourceRoles,
            String supportSessionId,
            Set<String> supportScopes,
            String actorTenantId,
            String trustedRouteContractKey) {

        public RequestEvidence {
            method = method == null ? "" : method.toUpperCase(Locale.ROOT);
            path = path == null ? "" : path;
            permissions = normalize(permissions);
            resourceRoles = resourceRoles == null ? "" : resourceRoles;
            supportScopes = normalize(supportScopes);
        }

        private static Set<String> normalize(Set<String> values) {
            if (values == null || values.isEmpty()) return Set.of();
            return values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public record Decision(boolean allowed, String denialCode, List<String> routeContractKeys) {
        static Decision allowed(List<String> routeKeys) {
            return new Decision(true, null, routeKeys);
        }

        static Decision denied(String code) {
            return new Decision(false, code, List.of());
        }
    }

    public record BindingContract(
            String routeContractKey,
            String routeKind,
            String productKey,
            String surfaceKey,
            String method,
            String publicPath,
            String servicePath) {
    }

    private record CompiledProjection(
            List<CompiledBinding> bindings,
            List<BindingContract> contracts) {
    }

    private record CompiledBinding(
            String routeContractKey,
            String routeKind,
            String authorizationEquivalenceKey,
            String productKey,
            String surfaceKey,
            String method,
            PathTemplate template,
            ArrayNode profiles) {

        boolean matches(String path) {
            return template.matches(path);
        }
    }

    private record PathTemplate(
            Pattern pattern,
            List<String> parameters,
            Map<String, Set<String>> allowlists) {

        static PathTemplate compile(String template, JsonNode constraints) {
            require(template != null && template.startsWith("/"),
                    "Generated service path is invalid");
            List<String> parameters = new ArrayList<>();
            StringBuilder expression = new StringBuilder("^");
            Matcher matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}").matcher(template);
            int offset = 0;
            while (matcher.find()) {
                expression.append(Pattern.quote(template.substring(offset, matcher.start())));
                expression.append(parameterExpression(matcher.group(1), constraints));
                parameters.add(matcher.group(1));
                offset = matcher.end();
            }
            expression.append(Pattern.quote(template.substring(offset))).append('$');
            Map<String, Set<String>> allowlists = new LinkedHashMap<>();
            if (constraints != null && constraints.isObject()) {
                constraints.properties().forEach(entry -> {
                    String kind = entry.getValue().path("kind").asText();
                    require(parameters.contains(entry.getKey())
                                    && Set.of("FIXED", "ALLOWLIST").contains(kind),
                            "Generated path constraint is invalid");
                    Set<String> values = new LinkedHashSet<>();
                    if ("FIXED".equals(kind)) {
                        require(entry.getValue().path("value").isTextual()
                                        && !entry.getValue().path("value").asText().isBlank(),
                                "Generated fixed path constraint is invalid");
                        values.add(entry.getValue().path("value").asText());
                    } else {
                        entry.getValue().path("values").forEach(value ->
                                require(value.isTextual() && values.add(value.asText()),
                                        "Generated path allowlist is invalid"));
                    }
                    require(!values.isEmpty(), "Generated path allowlist is empty");
                    allowlists.put(entry.getKey(), Set.copyOf(values));
                });
            }
            return new PathTemplate(
                    Pattern.compile(expression.toString()),
                    List.copyOf(parameters),
                    Map.copyOf(allowlists));
        }

        boolean matches(String path) {
            String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (normalized.contains("%2f") || normalized.contains("%5c")) return false;
            Matcher matcher = pattern.matcher(path);
            if (!matcher.matches()) return false;
            for (int index = 0; index < parameters.size(); index++) {
                Set<String> values = allowlists.get(parameters.get(index));
                if (values != null && !values.contains(matcher.group(index + 1))) return false;
            }
            return true;
        }

        private static String parameterExpression(String parameter, JsonNode constraints) {
            JsonNode constraint = constraints == null ? null : constraints.get(parameter);
            if (constraint == null) return "([^/]+)";
            if ("FIXED".equals(constraint.path("kind").asText())) {
                String value = constraint.path("value").asText();
                require(!value.isBlank(), "Generated fixed path constraint is invalid");
                return "(" + Pattern.quote(value) + ")";
            }
            require("ALLOWLIST".equals(constraint.path("kind").asText()),
                    "Generated path constraint is invalid");
            List<String> values = new ArrayList<>();
            constraint.path("values").forEach(value -> {
                require(value.isTextual() && !value.asText().isBlank(),
                        "Generated path allowlist is invalid");
                values.add(Pattern.quote(value.asText()));
            });
            require(!values.isEmpty(), "Generated path allowlist is empty");
            return "(" + String.join("|", values) + ")";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }
}
