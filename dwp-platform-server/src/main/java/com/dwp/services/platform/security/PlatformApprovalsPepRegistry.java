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
import java.util.stream.Collectors;

/** Generated W1a v2 Platform PEP projection for fixed approval-home bindings. */
@Component
public final class PlatformApprovalsPepRegistry {

    static final String RESOURCE =
            "product-authorization/platform-approvals-pep-v2.generated.json";
    static final String W1A_V2_CHECKSUM =
            "5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c";

    private final ObjectMapper objectMapper;
    private final Set<String> entitlements;
    private final List<Binding> bindings;

    public PlatformApprovalsPepRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode projection = readProjection();
        validateEnvelope(projection);
        this.entitlements = entitlementClosure(projection);
        this.bindings = compile(projection);
        require(entitlements.equals(Set.of("APP.APPROVALS:VIEW")),
                "Platform Approvals entitlement closure changed");
    }

    public boolean ownsPath(String path) {
        return path != null && bindings.stream().anyMatch(binding -> binding.path().matches(path));
    }

    public boolean governsPathFamily(String path) {
        String root = "/v1/home-preferences/surfaces/approval-home";
        return path != null && (path.equals(root) || path.startsWith(root + "/"));
    }

    public Decision authorize(RequestEvidence evidence) {
        if (!ownsPath(evidence.path())) return Decision.denied("NOT_APPROVAL_HOME_ROUTE");
        if (evidence.trustedRouteContractKey() == null
                || evidence.trustedRouteContractKey().isBlank()) {
            return Decision.denied("TRUSTED_ROUTE_KEY_REQUIRED");
        }
        List<String> routes = bindings.stream()
                .filter(binding -> binding.method().equals(evidence.method()))
                .filter(binding -> binding.path().matches(evidence.path()))
                .filter(binding -> binding.routeContractKey()
                        .equals(evidence.trustedRouteContractKey()))
                .filter(binding -> evidence.permissions().containsAll(entitlements))
                .map(Binding::routeContractKey)
                .distinct()
                .toList();
        return routes.isEmpty()
                ? Decision.denied("EXACT_ROUTE_AUTHORITY_REQUIRED")
                : Decision.allowed(routes);
    }

    public List<BindingContract> bindingContracts() {
        return bindings.stream().map(binding -> new BindingContract(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.publicPath(), binding.path().template(), binding.path().fixedValues()))
                .toList();
    }

    private Set<String> entitlementClosure(ObjectNode projection) {
        JsonNode policy = requiredArray(projection, "accessPolicies").get(0);
        JsonNode expression = requiredArray(projection, "entitlementExpressions").get(0);
        require("approvals.work-access.v1".equals(policy.path("accessPolicyKey").asText())
                        && policy.path("entitlementExpressionKey").asText()
                        .equals(expression.path("expressionKey").asText()),
                "Platform Approvals policy closure mismatch");
        JsonNode leaf = expression.path("expression");
        require("LEAF".equals(leaf.path("type").asText()),
                "Platform Approvals entitlement must remain a leaf");
        return Set.of(leaf.path("entitlement").asText().toUpperCase(Locale.ROOT));
    }

    private List<Binding> compile(ObjectNode projection) {
        List<Binding> result = new ArrayList<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            Map<String, JsonNode> gateway = index(
                    route.path("gatewayApiBindings"), "bindingKey", routeKey);
            for (JsonNode service : requiredArray(route, "servicePepBindings")) {
                require("platform".equals(service.path("serviceKey").asText()),
                        routeKey + ": wrong Platform PEP owner");
                JsonNode publicBinding = gateway.get(service.path("bindingKey").asText());
                require(publicBinding != null
                                && service.path("method").asText()
                                .equals(publicBinding.path("method").asText()),
                        routeKey + ": public/service binding mismatch");
                require(requiredArray(route, "accessProfiles").size() == 1
                                && "POLICY".equals(route.path("accessProfiles").get(0)
                                .path("requiredAccess").path("type").asText())
                                && "approvals.work-access.v1".equals(route.path("accessProfiles").get(0)
                                .path("requiredAccess").path("accessPolicyKey").asText())
                                && textValues(route.path("accessProfiles").get(0)
                                .path("predicatePolicyKeys"))
                                .equals(Set.of("predicate.platform.self-preference.v1")),
                        routeKey + ": access profile closure mismatch");
                result.add(new Binding(
                        routeKey, route.path("routeKind").asText(),
                        service.path("method").asText(), publicBinding.path("path").asText(),
                        PathTemplate.compile(service.path("path").asText(),
                                service.path("pathParameterConstraints"))));
            }
        }
        require(result.size() == 2, "Platform Approvals binding count changed");
        return List.copyOf(result);
    }

    private void validateEnvelope(ObjectNode projection) {
        JsonNode registry = projection.path("registryRef");
        require(projection.path("schemaVersion").asInt() == 1
                        && "platform-approvals-pep-v2".equals(projection.path("projectionKey").asText())
                        && "platform".equals(projection.path("ownerServiceKey").asText())
                        && "product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 2
                        && W1A_V2_CHECKSUM.equals(registry.path("sha256").asText()),
                "Unexpected Platform Approvals PEP envelope");
        require(projection.path("sourceRegistryRouteCount").asInt() == 76
                        && projection.path("projectedRouteContractCount").asInt() == 2
                        && projection.path("bindingPairCount").asInt() == 2
                        && requiredArray(projection, "capabilities").isEmpty()
                        && requiredArray(projection, "accessPolicies").size() == 1
                        && requiredArray(projection, "entitlementExpressions").size() == 1
                        && requiredArray(projection, "predicatePolicies").size() == 1,
                "Platform Approvals release counts changed");
        JsonNode predicate = requiredArray(projection, "predicatePolicies").get(0);
        require("predicate.platform.self-preference.v1".equals(
                        predicate.path("predicatePolicyKey").asText())
                        && "platform".equals(predicate.path("ownerServiceKey").asText()),
                "Platform self-preference predicate closure mismatch");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null && checksum.asText().equals(sha256(payload)),
                "Platform Approvals PEP checksum mismatch");
    }

    private ObjectNode readProjection() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Generated Platform Approvals PEP is absent.");
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated Platform Approvals PEP must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException("Generated Platform Approvals PEP cannot be read.", exception);
        }
    }

    private Map<String, JsonNode> index(JsonNode values, String keyField, String label) {
        require(values.isArray(), label + " must be an array");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        values.forEach(value -> {
            String key = value.path(keyField).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    label + ": duplicate or empty key");
        });
        return Map.copyOf(result);
    }

    private Set<String> textValues(JsonNode value) {
        require(value.isArray(), "Expected a generated string array");
        Set<String> result = new LinkedHashSet<>();
        value.forEach(item -> require(item.isTextual() && result.add(item.asText()),
                "Invalid generated string array"));
        return Set.copyOf(result);
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Platform Approvals checksum failed.", exception);
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

    public record Decision(boolean allowed, String denialCode, List<String> routeContractKeys) {
        static Decision allowed(List<String> routes) {
            return new Decision(true, null, List.copyOf(routes));
        }

        static Decision denied(String code) {
            return new Decision(false, code, List.of());
        }
    }

    public record BindingContract(
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            String servicePath,
            Map<String, String> fixedPathValues) {
    }

    private record Binding(
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            PathTemplate path) {
    }

    private record PathTemplate(Pattern pattern, String template, Map<String, String> fixedValues) {
        static PathTemplate compile(String template, JsonNode constraints) {
            StringBuilder expression = new StringBuilder("^");
            Matcher matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}").matcher(template);
            List<String> parameters = new ArrayList<>();
            int offset = 0;
            while (matcher.find()) {
                expression.append(Pattern.quote(template.substring(offset, matcher.start())))
                        .append("([^/]+)");
                parameters.add(matcher.group(1));
                offset = matcher.end();
            }
            expression.append(Pattern.quote(template.substring(offset))).append('$');
            Map<String, String> fixed = new LinkedHashMap<>();
            if (constraints != null && constraints.isObject()) {
                constraints.properties().forEach(entry -> {
                    require(parameters.contains(entry.getKey())
                                    && "FIXED".equals(entry.getValue().path("kind").asText())
                                    && entry.getValue().path("value").isTextual(),
                            "Invalid fixed Platform Approvals path constraint");
                    fixed.put(entry.getKey(), entry.getValue().path("value").asText());
                });
            }
            require(fixed.equals(Map.of("surfaceKey", "approval-home")),
                    "Platform Approvals fixed surface changed");
            String constrained = expression.toString();
            for (int index = 0; index < parameters.size(); index++) {
                String value = fixed.get(parameters.get(index));
                if (value != null) constrained = constrained.replaceFirst(
                        Pattern.quote("([^/]+)"),
                        Matcher.quoteReplacement(Pattern.quote(value)));
            }
            return new PathTemplate(Pattern.compile(constrained), template, Map.copyOf(fixed));
        }

        boolean matches(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            return !normalized.contains("%2f") && !normalized.contains("%5c")
                    && pattern.matcher(value).matches();
        }
    }
}
