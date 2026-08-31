package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Machine-generated PRODUCT public binding projection used by every Gateway PEP. */
@Component
public final class GeneratedProductRouteCatalog {

    /**
     * Products introduced through the representative PAGE/DATA/ACTION v4 closure. Their
     * generated bindings are intentionally incremental, so unmodeled sibling routes remain
     * protected by the existing service PEP until the product registry is exhaustive.
     */
    private static final Set<String> INCREMENTAL_PRODUCT_KEYS = Set.of(
            "calendar",
            "dwaion",
            "mail",
            "meetings",
            "messaging",
            "notifications",
            "spaces",
            "workplace");

    private static final Set<String> AUTHORITY_ENDPOINT_FIELDS = Set.of(
            "endpointKey", "method", "publicPath", "serviceKey", "servicePath",
            "requiresAuthentication", "requiresCsrf",
            "expectedDecisionRevisionHeader");
    /**
     * Temporary, code-owned compatibility boundary for legacy People APIs that predate the
     * immutable PRODUCT v3 registry. Keep this list exact and non-configurable: an operator must
     * not be able to widen a claimed PRODUCT namespace at runtime.
     */
    private static final List<LegacyExemptBinding> LEGACY_EXEMPT_BINDINGS = List.of(
            LegacyExemptBinding.exact(
                    "GET", "/api/people/v1/admin/workforce/access-policies"),
            LegacyExemptBinding.exact(
                    "GET", "/api/people/v1/admin/workforce/access-policies/organizations"),
            LegacyExemptBinding.exact(
                    "POST", "/api/people/v1/admin/workforce/access-policies"),
            LegacyExemptBinding.singleSegment(
                    "PATCH", "/api/people/v1/admin/workforce/access-policies/", "/revoke"));

    private final List<Route> routes;
    private final Map<String, Set<String>> ownedNamespaces;
    private final List<AuthorityEndpoint> authorityEndpoints;

    public GeneratedProductRouteCatalog(
            ObjectMapper objectMapper,
            @Value("${dwp.product-surface.candidate-catalog-location:"
                    + "classpath:product-authorization/product-surfaces-v1.generated.json}")
            Resource registry) {
        Loaded loaded = load(objectMapper, registry);
        this.routes = loaded.routes();
        this.ownedNamespaces = loaded.ownedNamespaces();
        this.authorityEndpoints = loaded.authorityEndpoints();
    }

    /**
     * Resolves an exact generated binding. Governed candidate and mature-product namespace drift
     * is INVALID; incremental products leave unmodeled siblings to the existing service PEP.
     */
    public Match match(String method, String path) {
        return match(method, path, null);
    }

    public Match match(String method, String path, String rawQuery) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = path == null ? "" : path;
        Map<String, List<String>> query;
        try {
            query = parseQuery(rawQuery);
        } catch (IllegalArgumentException exception) {
            return new Match(MatchStatus.INVALID, List.of());
        }
        List<Route> structural = routes.stream()
                .filter(route -> route.structuralPattern().matcher(normalizedPath).matches())
                .toList();
        List<Route> methodStructural = structural.stream()
                .filter(route -> route.method().equals(normalizedMethod))
                .toList();
        List<Route> exact = methodStructural.stream()
                .filter(route -> route.exactPattern().matcher(normalizedPath).matches())
                .filter(route -> queryMatches(route.queryConstraints(), query))
                .toList();
        if (exact.isEmpty()) {
            if (legacyExempt(normalizedMethod, normalizedPath)) {
                return new Match(MatchStatus.LEGACY_EXEMPT, List.of());
            }
            boolean governedCandidateDrift = !methodStructural.isEmpty();
            boolean matureProductMethodDrift = structural.stream()
                    .map(Route::productKey)
                    .anyMatch(product -> !INCREMENTAL_PRODUCT_KEYS.contains(product));
            return new Match(
                    !governedCandidateDrift
                                    && !matureProductMethodDrift
                                    && !claimedNamespace(normalizedPath)
                            ? MatchStatus.UNGOVERNED : MatchStatus.INVALID,
                    List.of());
        }
        Set<String> products = exact.stream()
                .map(Route::productKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Match(products.size() == 1 ? MatchStatus.GOVERNED : MatchStatus.AMBIGUOUS,
                exact);
    }

    public AuthorityEndpoint authorityEndpoint(String method, String path) {
        List<AuthorityEndpoint> matches = authorityEndpoints.stream()
                .filter(endpoint -> endpoint.method().equals(normalizeMethod(method)))
                .filter(endpoint -> endpoint.publicPath().equals(path))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    List<Route> routesForTesting() {
        return routes;
    }

    List<AuthorityEndpoint> authorityEndpointsForTesting() {
        return authorityEndpoints;
    }

    private boolean claimedNamespace(String path) {
        return ownedNamespaces.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1)
                .filter(entry -> entry.getValue().stream()
                        .noneMatch(INCREMENTAL_PRODUCT_KEYS::contains))
                .anyMatch(entry -> path.startsWith(entry.getKey()));
    }

    private boolean legacyExempt(String method, String path) {
        return LEGACY_EXEMPT_BINDINGS.stream()
                .anyMatch(binding -> binding.matches(method, path));
    }

    private Loaded load(ObjectMapper objectMapper, Resource resource) {
        try (var input = resource.getInputStream()) {
            JsonNode bundle = objectMapper.readTree(input);
            if (!"product-surfaces".equals(bundle.path("bundleKey").asText())
                    || bundle.path("version").asLong() < 1
                    || !bundle.path("routes").isArray()
                    || !bundle.path("capabilities").isArray()) {
                throw new IllegalStateException("Generated PRODUCT route registry is invalid.");
            }
            Map<String, Boolean> highRiskCapabilities = highRiskCapabilities(bundle);
            List<Route> result = new ArrayList<>();
            Map<String, Set<String>> namespaces = new HashMap<>();
            for (JsonNode route : bundle.path("routes")) {
                JsonNode subject = route.path("subject");
                if (!"ACTIVE".equals(route.path("lifecycleState").asText())
                        || !"PRODUCT".equals(subject.path("type").asText())) continue;
                boolean stateChanging = "ACTION".equals(route.path("routeKind").asText())
                        && !route.path("sideEffectFree").asBoolean(false);
                boolean highRiskStepUp = highRiskStepUp(route, highRiskCapabilities);
                for (JsonNode binding : route.path("gatewayApiBindings")) {
                    String publicPath = binding.path("path").asText();
                    if (!publicPath.startsWith("/api/")) {
                        throw new IllegalStateException("A PRODUCT binding is not public API scoped.");
                    }
                    result.add(new Route(
                            route.path("routeContractKey").asText(),
                            textOrNull(route, "authorizationEquivalenceKey"),
                            subject.path("productKey").asText(),
                            subject.path("surfaceKey").asText(),
                            normalizeMethod(binding.path("method").asText()),
                            publicPath, route.path("routeKind").asText(), stateChanging,
                            highRiskStepUp, compile(publicPath, null),
                            compile(publicPath, binding.path("pathParameterConstraints")),
                            queryConstraints(binding.path("queryParameterConstraints"))));
                    namespaces.computeIfAbsent(serviceNamespace(publicPath), ignored -> new HashSet<>())
                            .add(subject.path("productKey").asText());
                }
            }
            if (result.isEmpty()) throw new IllegalStateException("PRODUCT routes are absent.");
            Map<String, Set<String>> immutableNamespaces = namespaces.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
            return new Loaded(
                    List.copyOf(result), immutableNamespaces, authorityEndpoints(bundle));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated PRODUCT route registry cannot be read.", exception);
        }
    }

    private Map<String, Boolean> highRiskCapabilities(JsonNode bundle) {
        Map<String, Boolean> result = new HashMap<>();
        for (JsonNode capability : bundle.path("capabilities")) {
            result.put(capability.path("contractKey").asText(),
                    "ACTIVE".equals(capability.path("lifecycleState").asText())
                            && Set.of("HIGH", "CRITICAL").contains(
                                    capability.path("riskTier").asText())
                            && capability.path("activationPolicy").asText().startsWith("STEPUP-"));
        }
        return result;
    }

    private List<AuthorityEndpoint> authorityEndpoints(JsonNode bundle) {
        JsonNode endpoints = bundle.get("authorityEndpoints");
        if (endpoints == null) return List.of();
        if (!endpoints.isArray() || endpoints.isEmpty()) {
            throw new IllegalStateException("Generated authority endpoints are absent.");
        }
        List<AuthorityEndpoint> result = new ArrayList<>();
        for (JsonNode endpoint : endpoints) {
            Set<String> fields = new HashSet<>();
            if (!endpoint.isObject()) {
                throw new IllegalStateException("Generated authority endpoint is invalid.");
            }
            endpoint.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(AUTHORITY_ENDPOINT_FIELDS)
                    || !endpoint.path("requiresAuthentication").isBoolean()
                    || !endpoint.path("requiresCsrf").isBoolean()) {
                throw new IllegalStateException("Generated authority endpoint is invalid.");
            }
            AuthorityEndpoint value = new AuthorityEndpoint(
                    endpoint.path("endpointKey").asText(),
                    normalizeMethod(endpoint.path("method").asText()),
                    endpoint.path("publicPath").asText(),
                    endpoint.path("serviceKey").asText(),
                    endpoint.path("servicePath").asText(),
                    endpoint.path("requiresAuthentication").asBoolean(),
                    endpoint.path("requiresCsrf").asBoolean(),
                    endpoint.path("expectedDecisionRevisionHeader").asText());
            if (!value.endpointKey().matches("^[a-z][a-z0-9.-]{2,159}$")
                    || !value.method().matches("^[A-Z]+$")
                    || !value.publicPath().startsWith("/api/")
                    || !value.serviceKey().matches("^[a-z][a-z0-9-]{1,79}$")
                    || !value.servicePath().startsWith("/")
                    || value.expectedDecisionRevisionHeader().isBlank()
                    || result.stream().anyMatch(existing -> existing.endpointKey()
                            .equals(value.endpointKey())
                            || existing.method().equals(value.method())
                            && existing.publicPath().equals(value.publicPath()))) {
                throw new IllegalStateException("Generated authority endpoint is invalid.");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private boolean highRiskStepUp(JsonNode route, Map<String, Boolean> capabilities) {
        for (JsonNode profile : route.path("accessProfiles")) {
            JsonNode required = profile.path("requiredAccess");
            if ("CAPABILITY".equals(required.path("type").asText())
                    && capabilities.getOrDefault(
                            required.path("capabilityContractKey").asText(), false)) return true;
        }
        return false;
    }

    private Pattern compile(String template, JsonNode constraints) {
        StringBuilder regex = new StringBuilder("^");
        java.util.regex.Matcher matcher = Pattern.compile("\\{([^/{}]+)}").matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(template.substring(cursor, matcher.start())));
            regex.append(parameterPattern(matcher.group(1), constraints));
            cursor = matcher.end();
        }
        regex.append(Pattern.quote(template.substring(cursor))).append('$');
        return Pattern.compile(regex.toString());
    }

    private String parameterPattern(String parameter, JsonNode constraints) {
        if (constraints == null || constraints.isMissingNode() || !constraints.isObject()) {
            return "[^/]+";
        }
        JsonNode constraint = constraints.path(parameter);
        return switch (constraint.path("kind").asText()) {
            case "FIXED" -> Pattern.quote(constraint.path("value").asText());
            case "ALLOWLIST" -> allowlistPattern(constraint.path("values"));
            default -> "[^/]+";
        };
    }

    private String allowlistPattern(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) return "(?!)";
        List<String> encoded = new ArrayList<>();
        values.forEach(value -> encoded.add(Pattern.quote(value.asText())));
        return "(?:" + String.join("|", encoded) + ")";
    }

    private String serviceNamespace(String path) {
        String[] parts = path.split("/", -1);
        if (parts.length < 3 || !"api".equals(parts[1]) || parts[2].isBlank()) {
            throw new IllegalStateException("Invalid PRODUCT public binding path.");
        }
        return "/api/" + parts[2] + "/";
    }

    private Map<String, QueryConstraint> queryConstraints(JsonNode constraints) {
        if (constraints == null || constraints.isMissingNode() || constraints.isNull()) {
            return Map.of();
        }
        if (!constraints.isObject()) {
            throw new IllegalStateException("Generated query constraints must be an object.");
        }
        Map<String, QueryConstraint> result = new HashMap<>();
        constraints.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            QueryConstraint constraint = switch (value.path("kind").asText()) {
                case "ABSENT" -> new QueryConstraint("ABSENT", Set.of());
                case "FIXED" -> new QueryConstraint(
                        "FIXED", Set.of(value.path("value").asText()));
                case "ALLOWLIST" -> new QueryConstraint(
                        "ALLOWLIST", textSet(value.path("values")));
                default -> throw new IllegalStateException(
                        "Generated query constraint kind is invalid.");
            };
            if (result.putIfAbsent(entry.getKey(), constraint) != null) {
                throw new IllegalStateException("Duplicate generated query constraint.");
            }
        });
        return Map.copyOf(result);
    }

    private Set<String> textSet(JsonNode values) {
        Set<String> result = new HashSet<>();
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("Generated query allowlist is empty.");
        }
        values.forEach(value -> {
            if (!value.isTextual() || value.asText().isEmpty() || !result.add(value.asText())) {
                throw new IllegalStateException("Generated query allowlist is invalid.");
            }
        });
        return Set.copyOf(result);
    }

    private boolean queryMatches(
            Map<String, QueryConstraint> constraints,
            Map<String, List<String>> query) {
        for (Map.Entry<String, QueryConstraint> entry : constraints.entrySet()) {
            List<String> values = query.getOrDefault(entry.getKey(), List.of());
            QueryConstraint constraint = entry.getValue();
            if ("ABSENT".equals(constraint.kind())) {
                if (!values.isEmpty()) return false;
            } else if (values.size() != 1 || !constraint.values().contains(values.getFirst())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, List<String>> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) throw new IllegalArgumentException("Empty query component");
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            if (key.isEmpty() || key.indexOf('\u0000') >= 0 || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("Invalid query component");
            }
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return result.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid query encoding", exception);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }

    private String normalizeMethod(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public enum MatchStatus {
        GOVERNED,
        LEGACY_EXEMPT,
        UNGOVERNED,
        INVALID,
        AMBIGUOUS
    }

    public record Match(MatchStatus status, List<Route> routes) {

        public Match {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }

        public String productKey() {
            return routes.isEmpty() ? null : routes.getFirst().productKey();
        }

        public Route uniqueRoute() {
            if (status != MatchStatus.GOVERNED || routes.isEmpty()) return null;
            if (routes.size() == 1) return routes.getFirst();
            String equivalenceKey = routes.getFirst().authorizationEquivalenceKey();
            if (equivalenceKey == null || routes.stream().anyMatch(
                    route -> !equivalenceKey.equals(route.authorizationEquivalenceKey()))) {
                return null;
            }
            return routes.stream().min(java.util.Comparator.comparing(Route::routeContractKey))
                    .orElse(null);
        }
    }

    public record Route(
            String routeContractKey,
            String authorizationEquivalenceKey,
            String productKey,
            String surfaceKey,
            String method,
            String publicPath,
            String routeKind,
            boolean stateChanging,
            boolean highRiskStepUp,
            Pattern structuralPattern,
            Pattern exactPattern,
            Map<String, QueryConstraint> queryConstraints) {
    }

    public record QueryConstraint(String kind, Set<String> values) {
    }

    private record LegacyExemptBinding(String method, Pattern pathPattern) {

        private static LegacyExemptBinding exact(String method, String path) {
            return new LegacyExemptBinding(method, Pattern.compile("^" + Pattern.quote(path) + "$"));
        }

        private static LegacyExemptBinding singleSegment(
                String method,
                String prefix,
                String suffix) {
            return new LegacyExemptBinding(
                    method,
                    Pattern.compile("^" + Pattern.quote(prefix) + "[^/]+"
                            + Pattern.quote(suffix) + "$"));
        }

        private boolean matches(String requestMethod, String requestPath) {
            return method.equals(requestMethod) && pathPattern.matcher(requestPath).matches();
        }
    }

    public record AuthorityEndpoint(
            String endpointKey,
            String method,
            String publicPath,
            String serviceKey,
            String servicePath,
            boolean requiresAuthentication,
            boolean requiresCsrf,
            String expectedDecisionRevisionHeader) {
    }

    private record Loaded(
            List<Route> routes,
            Map<String, Set<String>> ownedNamespaces,
            List<AuthorityEndpoint> authorityEndpoints) {
    }
}
