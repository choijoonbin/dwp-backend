package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import static com.dwp.gateway.productsurface.ProductRouteTopologyRules.*;
import com.dwp.gateway.productsurface.ProductRouteTopologyRules.V59PerformanceFeedbackTopology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads the generated PRODUCT public binding projection used by every Gateway PEP. */
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

    /**
     * Public service roots that host unrelated APIs as well as PRODUCT-owned route families.
     * Claiming one of these roots wholesale would turn an isolated binding such as
     * {@code /api/auth/hris/...} into a fail-closed takeover of every {@code /api/auth/...}
     * endpoint. Keep the ownership boundary at the first route-family segment instead.
     */
    private static final Set<String> SHARED_SERVICE_ROOTS = Set.of("auth", "platform");

    private static final Map<String, String> V47_CANONICAL_PAGE_FIRST_READ_SOURCES = Map.of(
            "route.hcm.operations.time-work-plans.page",
                    "route.hcm.operations.work-plans-list.data",
            "route.hcm.operations.performance-goal-cycles.page",
                    "route.hcm.operations.performance-cycles-list.data",
            "route.hcm.team.leave.page", "route.hcm.team.absence.page",
            "route.hcm.operations.time-leave-operations.page",
                    "route.hcm.operations.absence.page",
            "route.hcm.operations.home.page", "route.hcm.operations.overview.page");

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

    /**
     * Shared runtime catalog reads that are consumed outside a PRODUCT surface. The HCM product
     * intentionally owns only its allowlisted values on the same parameterized endpoint. Keep
     * these exact so a new code set cannot silently bypass generated product-route governance.
     */
    private static final Set<String> NON_PRODUCT_SHARED_RUNTIME_GETS = Set.of(
            "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.WINDOW",
            "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER",
            "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.HTTP_METHOD_FILTER",
            "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.OUTCOME_FILTER",
            "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.COLOR_MODE",
            "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.DENSITY",
            "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.TIME_ZONE",
            "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.DATE_FORMAT",
            "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.TIME_FORMAT",
            "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK",
            "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.NUMBER_FORMAT",
            "/api/platform/v1/catalog/code-sets/PLATFORM.HOME_WIDGET",
            "/api/platform/v1/catalog/code-sets/PLATFORM.AUDIT.WINDOW",
            "/api/platform/v1/catalog/code-sets/PLATFORM.AUDIT.CATEGORY_FILTER",
            "/api/platform/v1/catalog/code-sets/PLATFORM.AUDIT.SEVERITY_FILTER",
            "/api/platform/v1/catalog/code-sets/PLATFORM.AUDIT.OUTCOME_FILTER",
            "/api/platform/v1/catalog/code-sets/PLATFORM.EVENT_ENVELOPE.DOMAIN",
            "/api/platform/v1/catalog/code-sets/PLATFORM.EVENT_ENVELOPE.CLASSIFICATION",
            "/api/platform/v1/catalog/code-sets/PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT");

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
        if ("GET".equals(normalizedMethod)
                && NON_PRODUCT_SHARED_RUNTIME_GETS.contains(normalizedPath)) {
            return new Match(MatchStatus.UNGOVERNED, List.of());
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
        exact = mostSpecificPathBindings(exact);
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

    private List<Route> mostSpecificPathBindings(List<Route> matches) {
        if (matches.size() < 2) return matches;
        int fewestParameters = matches.stream()
                .mapToInt(route -> pathParameterCount(route.publicPath()))
                .min()
                .orElse(0);
        return matches.stream()
                .filter(route -> pathParameterCount(route.publicPath()) == fewestParameters)
                .toList();
    }

    private int pathParameterCount(String template) {
        int count = 0;
        java.util.regex.Matcher matcher = Pattern.compile("\\{([^/{}]+)}").matcher(template);
        while (matcher.find()) count++;
        return count;
    }

    public AuthorityEndpoint authorityEndpoint(String method, String path) {
        List<AuthorityEndpoint> matches = authorityEndpoints.stream()
                .filter(endpoint -> endpoint.method().equals(normalizeMethod(method)))
                .filter(endpoint -> endpoint.publicPath() != null)
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
            long bundleVersion = bundle.path("version").asLong();
            Map<String, Boolean> highRiskCapabilities = highRiskCapabilities(bundle);
            List<Route> result = new ArrayList<>();
            Map<String, Set<String>> namespaces = new HashMap<>();
            Set<String> genericSessionRoutes = new HashSet<>();
            Set<String> performanceFeedbackRoutes = new HashSet<>();
            Set<String> sys008Routes = new HashSet<>();
            for (JsonNode route : bundle.path("routes")) {
                String routeKey = route.path("routeContractKey").asText();
                Sys008GatewayRoutes.Binding sys008Binding =
                        Sys008GatewayRoutes.binding(routeKey);
                if (sys008Binding != null) {
                    if (bundleVersion < 60) {
                        throw new IllegalStateException(
                                "A PRODUCT v60 SYS-008 route was introduced early: " + routeKey);
                    }
                    validateV60Sys008Topology(route, sys008Binding);
                    if (!sys008Routes.add(routeKey)) {
                        throw new IllegalStateException(
                                "A PRODUCT v60 SYS-008 route is duplicated: " + routeKey);
                    }
                }
                V59PerformanceFeedbackTopology performanceFeedbackTopology =
                        V59_PERFORMANCE_FEEDBACK_TOPOLOGIES.get(routeKey);
                if (performanceFeedbackTopology != null) {
                    if (bundleVersion < 59) {
                        throw new IllegalStateException(
                                "A PRODUCT v59 performance-feedback route was introduced early: "
                                        + routeKey);
                    }
                    validateV59PerformanceFeedbackTopology(
                            route, performanceFeedbackTopology);
                    if (!performanceFeedbackRoutes.add(routeKey)) {
                        throw new IllegalStateException(
                                "A PRODUCT v59 performance-feedback route is duplicated: "
                                        + routeKey);
                    }
                }
                JsonNode subject = route.path("subject");
                if (!"ACTIVE".equals(route.path("lifecycleState").asText())
                        || !"PRODUCT".equals(subject.path("type").asText())) continue;
                if ("AUTHORIZATION_ONLY".equals(route.path("bindingMode").asText())) {
                    if (!"PAGE".equals(route.path("routeKind").asText())
                            || !route.path("gatewayApiBindings").isArray()
                            || !route.path("gatewayApiBindings").isEmpty()
                            || !route.path("servicePepBindings").isArray()
                            || !route.path("servicePepBindings").isEmpty()) {
                        throw new IllegalStateException(
                                "An authorization-only PRODUCT PAGE has network bindings.");
                    }
                    continue;
                }
                boolean stateChanging = "ACTION".equals(route.path("routeKind").asText())
                        && !route.path("sideEffectFree").asBoolean(false);
                boolean highRiskStepUp = highRiskStepUp(route, highRiskCapabilities);
                boolean commandBoundStepUp = route.path("stepUpCommandBindings").isArray()
                        && !route.path("stepUpCommandBindings").isEmpty();
                boolean genericSessionStepUp = genericSessionStepUp(
                        route, bundleVersion, highRiskStepUp, commandBoundStepUp);
                if (highRiskStepUp && !commandBoundStepUp && !genericSessionStepUp
                        && !OTHER_EXACT_NO_COMMAND_STEP_UP_ROUTE_KEYS.contains(routeKey)) {
                    throw new IllegalStateException(
                            "A PRODUCT generic session step-up route is not sealed.");
                }
                if (genericSessionStepUp) genericSessionRoutes.add(routeKey);
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
                            highRiskStepUp, commandBoundStepUp, genericSessionStepUp,
                            compile(publicPath, null),
                            compile(publicPath, binding.path("pathParameterConstraints")),
                            queryConstraints(binding.path("queryParameterConstraints"))));
                    namespaces.computeIfAbsent(serviceNamespace(publicPath), ignored -> new HashSet<>())
                            .add(subject.path("productKey").asText());
                }
            }
            if (result.isEmpty()) throw new IllegalStateException("PRODUCT routes are absent.");
            Set<String> expectedGenericSessionRoutes = new HashSet<>(
                    GENERIC_SESSION_STEP_UP_TOPOLOGIES.keySet());
            if (bundleVersion < 54) {
                expectedGenericSessionRoutes.removeAll(
                        V54_GENERIC_SESSION_STEP_UP_ROUTE_KEYS);
            }
            if (bundleVersion < 56) {
                expectedGenericSessionRoutes.removeAll(
                        V56_GENERIC_SESSION_STEP_UP_ROUTE_KEYS);
            }
            if (bundleVersion < 58) {
                expectedGenericSessionRoutes.removeAll(
                        V58_GENERIC_SESSION_STEP_UP_ROUTE_KEYS);
            }
            if (bundleVersion < 60) {
                expectedGenericSessionRoutes.removeAll(
                        V60_GENERIC_SESSION_STEP_UP_ROUTE_KEYS);
            }
            if (bundleVersion >= 49
                    && !genericSessionRoutes.equals(expectedGenericSessionRoutes)) {
                throw new IllegalStateException(
                        "The PRODUCT generic session step-up closed set is incomplete.");
            }
            if (bundleVersion >= 59
                    && !performanceFeedbackRoutes.equals(
                            V59_PERFORMANCE_FEEDBACK_TOPOLOGIES.keySet())) {
                throw new IllegalStateException(
                        "The PRODUCT v59 performance-feedback topology closed set is incomplete.");
            }
            if (bundleVersion >= 60 && !sys008Routes.equals(Sys008GatewayRoutes.ROUTE_KEYS)) {
                throw new IllegalStateException(
                        "The PRODUCT v60 SYS-008 topology closed set is incomplete.");
            }
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

    private void validateV60Sys008Topology(
            JsonNode route,
            Sys008GatewayRoutes.Binding binding) {
        String routeKey = binding.routeContractKey();
        String bindingKey = routeKey + ".binding.01";
        JsonNode subject = route.path("subject");
        JsonNode gateways = route.path("gatewayApiBindings");
        JsonNode services = route.path("servicePepBindings");
        JsonNode profiles = route.path("accessProfiles");
        JsonNode stepUpBindings = route.path("stepUpCommandBindings");
        Sys008GatewayRoutes.StepUpTarget stepUp =
                Sys008GatewayRoutes.commandBoundStepUp(routeKey);
        boolean exact = "ACTIVE".equals(route.path("lifecycleState").asText())
                && route.path("policyVersion").asLong() == 1
                && "PRODUCT".equals(subject.path("type").asText())
                && "hcm".equals(subject.path("productKey").asText())
                && "hcm.management".equals(subject.path("surfaceKey").asText())
                && "hcm.management".equals(route.path("navigationContextId").asText())
                && binding.routeKind().equals(route.path("routeKind").asText())
                && ("DATA".equals(binding.routeKind())
                        ? route.path("sideEffectFree").asBoolean(false)
                        : route.path("sideEffectFree").isNull())
                && gateways.isArray() && gateways.size() == 1
                && services.isArray() && services.size() == 1
                && profiles.isArray() && profiles.size() == 1;
        if (exact) {
            JsonNode gateway = gateways.get(0);
            JsonNode service = services.get(0);
            JsonNode profile = profiles.get(0);
            exact = bindingKey.equals(gateway.path("bindingKey").asText())
                    && binding.method().equals(gateway.path("method").asText())
                    && binding.browserPath().equals(gateway.path("path").asText())
                    && gateway.path("pathParameterConstraints").isObject()
                    && bindingKey.equals(service.path("bindingKey").asText())
                    && "platform".equals(service.path("serviceKey").asText())
                    && binding.method().equals(service.path("method").asText())
                    && binding.servicePath().equals(service.path("path").asText())
                    && service.path("pathParameterConstraints").isObject()
                    && gateway.path("pathParameterConstraints")
                            .equals(service.path("pathParameterConstraints"))
                    && "CAPABILITY".equals(
                            profile.path("requiredAccess").path("type").asText())
                    && binding.capability().equals(profile.path("requiredAccess")
                            .path("capabilityContractKey").asText());
        }
        if (exact) {
            if (stepUp == null) {
                exact = stepUpBindings.isMissingNode()
                        || stepUpBindings.isArray() && stepUpBindings.isEmpty();
            } else {
                JsonNode commandBinding = stepUpBindings.isArray()
                        && stepUpBindings.size() == 1 ? stepUpBindings.get(0) : null;
                exact = commandBinding != null
                        && bindingKey.equals(commandBinding.path("bindingKey").asText())
                        && "COMMAND_HEADER".equals(commandBinding
                                .path("expectedObjectVersionSource").asText())
                        && "If-Match".equals(commandBinding
                                .path("expectedObjectVersionName").asText())
                        && "platform".equals(commandBinding.path("ownerServiceKey").asText())
                        && "dwp-platform-server".equals(
                                commandBinding.path("audience").asText())
                        && stepUp.targetType().equals(commandBinding.path("targetType").asText())
                        && stepUp.targetIdPathParameter().equals(
                                commandBinding.path("targetIdPathParameter").asText());
            }
        }
        if (!exact) {
            throw new IllegalStateException(
                    "A PRODUCT v60 SYS-008 topology is invalid: " + routeKey);
        }
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
                    textOrNull(endpoint, "publicPath"),
                    endpoint.path("serviceKey").asText(),
                    endpoint.path("servicePath").asText(),
                    endpoint.path("requiresAuthentication").asBoolean(),
                    endpoint.path("requiresCsrf").asBoolean(),
                    textOrNull(endpoint, "expectedDecisionRevisionHeader"));
            boolean exactInternalOnly = isExactInternalOnlyAuthorityEndpoint(value);
            boolean validPublic = value.publicPath() != null
                    && value.publicPath().startsWith("/api/")
                    && value.expectedDecisionRevisionHeader() != null;
            if (!value.endpointKey().matches("^[a-z][a-z0-9.-]{2,159}$")
                    || !value.method().matches("^[A-Z]+$")
                    || !value.serviceKey().matches("^[a-z][a-z0-9-]{1,79}$")
                    || !value.servicePath().startsWith("/")
                    || !(validPublic || exactInternalOnly)
                    || result.stream().anyMatch(existing -> existing.endpointKey()
                            .equals(value.endpointKey())
                            || value.publicPath() != null
                            && existing.publicPath() != null
                            && existing.method().equals(value.method())
                            && existing.publicPath().equals(value.publicPath()))) {
                throw new IllegalStateException("Generated authority endpoint is invalid.");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private boolean isExactInternalOnlyAuthorityEndpoint(AuthorityEndpoint endpoint) {
        return "hris-navigation-policy-decisions.evaluate".equals(endpoint.endpointKey())
                && "POST".equals(endpoint.method())
                && endpoint.publicPath() == null
                && "auth".equals(endpoint.serviceKey())
                && "/internal/auth/v1/hris-navigation-policy-decisions/evaluate"
                        .equals(endpoint.servicePath())
                && !endpoint.requiresAuthentication()
                && !endpoint.requiresCsrf()
                && endpoint.expectedDecisionRevisionHeader() == null;
    }

    private String serviceNamespace(String path) {
        String[] parts = path.split("/", -1);
        if (parts.length < 3 || !"api".equals(parts[1]) || parts[2].isBlank()) {
            throw new IllegalStateException("Invalid PRODUCT public binding path.");
        }
        if (SHARED_SERVICE_ROOTS.contains(parts[2])) {
            if (parts.length < 4 || parts[3].isBlank()) {
                throw new IllegalStateException(
                        "A shared-service PRODUCT binding must declare a route family.");
            }
            return "/api/" + parts[2] + "/" + parts[3] + "/";
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
                case "REQUIRED" -> new QueryConstraint("REQUIRED", Set.of());
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
            } else if ("REQUIRED".equals(constraint.kind())) {
                if (values.size() != 1 || values.getFirst().isBlank()) return false;
            } else if (values.size() != 1 || !constraint.values().contains(values.getFirst())) {
                return false;
            }
        }
        return true;
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
            Route sourceRoute = v47FirstReadSource(routes);
            if (sourceRoute != null) return sourceRoute;
            String equivalenceKey = routes.getFirst().authorizationEquivalenceKey();
            if (equivalenceKey == null || routes.stream().anyMatch(
                    route -> !equivalenceKey.equals(route.authorizationEquivalenceKey()))) {
                return null;
            }
            return routes.stream().min(java.util.Comparator.comparing(Route::routeContractKey))
                    .orElse(null);
        }
    }

    private static Route v47FirstReadSource(List<Route> routes) {
        if (routes.size() != 2) return null;
        Set<String> routeKeys = routes.stream()
                .map(Route::routeContractKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return V47_CANONICAL_PAGE_FIRST_READ_SOURCES.entrySet().stream()
                .filter(entry -> routeKeys.equals(Set.of(entry.getKey(), entry.getValue())))
                .findFirst()
                .flatMap(entry -> routes.stream()
                        .filter(route -> entry.getValue().equals(route.routeContractKey()))
                        .findFirst())
                .orElse(null);
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
            boolean commandBoundStepUp,
            boolean genericSessionStepUp,
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
