package com.dwp.services.platform.servicecenter;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.RolePlaneBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Platform-owned Services route PEP layered in front of the immutable Platform v1 PEP.
 *
 * <p>It always protects the active HCM v3 personal-services route. Draft Services v4 routes remain
 * feature-gated. Enforced requests validate the final route and trusted owner scope, then bridge
 * compatible routes to their immutable v1 identity for the downstream Platform filter.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public final class ServicesProductSurfacePepFilter extends OncePerRequestFilter {

    static final String HOME_PAGE_ROUTE = "route.services.work.home.page";
    static final String REQUEST_DETAIL_DATA_ROUTE =
            "route.services.work.request-detail.data";
    static final String REQUEST_CREATE_ACTION_ROUTE =
            "route.services.work.request-create.action";
    static final String HCM_PERSONAL_SERVICES_PAGE_ROUTE =
            "route.hcm.personal.services.page";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final Set<String> HCM_BRIDGE_QUERY_PARAMETERS = Set.of("surface");

    static final Binding HOME_PAGE = new Binding(
            HOME_PAGE_ROUTE,
            HOME_PAGE_ROUTE,
            RouteKind.PAGE,
            "GET",
            "/api/platform/v1/services/catalog",
            "/v1/services/catalog",
            Map.of(),
            Set.of("surface", "view"),
            "services",
            "services.work",
            false);
    static final Binding REQUEST_DETAIL_DATA = new Binding(
            REQUEST_DETAIL_DATA_ROUTE,
            "route.services.work.my-detail.page",
            RouteKind.DATA,
            "GET",
            "/api/platform/v1/services/requests/{requestId}",
            "/v1/services/requests/{requestId}",
            Map.of("view", "detail"),
            Set.of(),
            "services",
            "services.work",
            false);
    static final Binding REQUEST_CREATE_ACTION = new Binding(
            REQUEST_CREATE_ACTION_ROUTE,
            REQUEST_CREATE_ACTION_ROUTE,
            RouteKind.ACTION,
            "POST",
            "/api/platform/v1/services/requests",
            "/v1/services/requests",
            Map.of(),
            Set.of(),
            "services",
            "services.work",
            false);
    static final Binding HCM_CATALOG_PAGE = new Binding(
            HCM_PERSONAL_SERVICES_PAGE_ROUTE,
            HOME_PAGE_ROUTE,
            RouteKind.PAGE,
            "GET",
            "/api/platform/v1/services/catalog",
            "/v1/services/catalog",
            Map.of("surface", "hcm"),
            Set.of(),
            "hcm",
            "hcm.personal",
            true);
    static final Binding HCM_REQUESTS_PAGE = new Binding(
            HCM_PERSONAL_SERVICES_PAGE_ROUTE,
            HOME_PAGE_ROUTE,
            RouteKind.PAGE,
            "GET",
            "/api/platform/v1/services/requests",
            "/v1/services/requests",
            Map.of("surface", "hcm"),
            Set.of(),
            "hcm",
            "hcm.personal",
            true);
    private static final List<Binding> BINDINGS = List.of(
            HOME_PAGE, REQUEST_DETAIL_DATA, REQUEST_CREATE_ACTION,
            HCM_CATALOG_PAGE, HCM_REQUESTS_PAGE);
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");

    private final boolean enabled;
    private final String serviceToken;
    private final ObjectMapper objectMapper;

    @Autowired
    public ServicesProductSurfacePepFilter(
            @Value("${dwp.platform.product-authorization-services-v4-enabled:false}")
            boolean enabled,
            @Value("${dwp.platform.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
    }

    ServicesProductSurfacePepFilter(boolean enabled, ObjectMapper objectMapper) {
        this(enabled, "trusted", objectMapper);
    }

    List<Binding> bindingContracts() {
        return BINDINGS;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        List<Binding> candidates = candidateBindings(request);
        return candidates.isEmpty() || (!enabled && candidates.stream()
                .noneMatch(Binding::requiresHcmApplication));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Platform service identity is not configured.");
            return;
        }
        if (!constantTimeEquals(
                serviceToken, exactHeader(request, SERVICE_TOKEN_HEADER))) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted Platform service identity is required.");
            return;
        }
        String rolloutState = exactHeader(request, "X-DWP-Rollout-State");
        if (!validRollout(
                rolloutState,
                exactHeader(request, "X-DWP-Rollout-Revision"),
                exactHeader(request, "X-DWP-Rollout-Cohort"))) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Employee Services rollout evidence is invalid.");
            return;
        }
        if (rolloutState.charAt(1) == '0') {
            filterChain.doFilter(request, response);
            return;
        }
        String routeKey = exactHeader(request, "X-DWP-Route-Contract-Key");
        Binding binding = candidateBindings(request).stream()
                .filter(candidate -> candidate.routeContractKey().equals(routeKey))
                .findFirst()
                .orElse(null);
        if (binding == null) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact Employee Services route authority is required.");
            return;
        }

        Decision decision = authorize(request, binding);
        if (decision == Decision.UNAVAILABLE) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Employee Services owner authority is unavailable.");
            return;
        }
        if (decision == Decision.DENIED) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The selected Employee Services owner scope is not authorized.");
            return;
        }

        String revision = exactHeader(request, "X-DWP-Current-Decision-Revision");
        OffsetDateTime revalidateAt = instant(
                exactHeader(request, "X-DWP-Current-Revalidate-At"));
        if (revision == null || !revision.matches("psr-[a-f0-9]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current Employee Services authority is missing or expired.");
            return;
        }
        if (binding.routeKind() == RouteKind.ACTION
                && !revision.equals(exactHeader(
                request, "X-DWP-Expected-Decision-Revision"))) {
            writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                    "Employee Services authority changed after the client decision.");
            return;
        }

        HttpServletRequest downstream = binding.routeContractKey().equals(
                binding.platformV1RouteContractKey())
                ? request
                : new RouteContractBridgeRequest(
                request,
                binding.platformV1RouteContractKey(),
                binding.requiresHcmApplication()
                        ? HCM_BRIDGE_QUERY_PARAMETERS : Set.of());
        filterChain.doFilter(downstream, response);
    }

    private Decision authorize(HttpServletRequest request, Binding binding) {
        long tenantId = positiveLong(request.getHeader("X-DWP-Tenant-ID"));
        long actorId = positiveLong(request.getHeader("X-DWP-User-ID"));
        String contextKey = exactHeader(request, "X-DWP-Context-Key");
        String scopeKey = exactHeader(request, "X-DWP-Context-Scope-Key");
        if (tenantId < 1 || actorId < 1 || contextKey == null
                || !contextKey.matches("psc-[a-f0-9]{64}") || scopeKey == null) {
            return Decision.UNAVAILABLE;
        }
        Set<String> roles = values(request.getHeader("X-DWP-Roles"));
        String mode = exactHeader(request, "X-DWP-Active-Access-Mode");
        if (!("NORMAL".equals(mode) || "ELEVATED".equals(mode))
                || request.getHeader("X-DWP-Support-Session-ID") != null
                || RolePlaneBoundary.isProviderIdentity(roles)) {
            return Decision.DENIED;
        }
        Set<String> permissions = upperValues(request.getHeader("X-DWP-Permissions"));
        if (!permissions.contains("APP.EMPLOYEE_SERVICES:VIEW")) {
            return Decision.DENIED;
        }
        if (binding.requiresHcmApplication()
                && !permissions.contains("APP.HCM:VIEW")
                && !permissions.contains("APP.HRIS:VIEW")) {
            return Decision.DENIED;
        }
        if (binding.requiresHcmApplication()) {
            // HCM eligibility replaces Auth's SELF selector with a People-derived scope.
            // The exact route/current-decision evidence is Gateway-only and this owner PEP also
            // verifies its service identity before accepting the canonical derived selector.
            if (!HcmEligibilityScopeKey.isCanonical(scopeKey)) {
                return Decision.DENIED;
            }
        } else {
            String expectedScope = ProductSurfaceScopeKey.key(
                    tenantId,
                    actorId,
                    binding.productKey(),
                    binding.surfaceKey(),
                    "SELF",
                    "SELF");
            if (!constantTimeEquals(expectedScope, scopeKey)) return Decision.DENIED;
        }
        return binding.routeContractKey().equals(
                exactHeader(request, "X-DWP-Route-Contract-Key"))
                ? Decision.ALLOWED : Decision.DENIED;
    }

    private List<Binding> candidateBindings(HttpServletRequest request) {
        return BINDINGS.stream()
                .filter(binding -> binding.matches(request))
                .toList();
    }

    private boolean validRollout(String state, String revision, String cohort) {
        return state != null && ROLLOUT_STATES.contains(state)
                && revision != null && revision.matches("rollout-[a-f0-9]{64}")
                && cohort != null && ROLLOUT_COHORTS.contains(cohort);
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1;
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private OffsetDateTime instant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String exactHeader(HttpServletRequest request, String name) {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) return null;
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 200 || !value.equals(value.trim())
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    private Set<String> values(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(",", -1))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> upperValues(String header) {
        return values(header);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(
            HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }

    enum RouteKind {
        PAGE,
        DATA,
        ACTION
    }

    enum Decision {
        ALLOWED,
        DENIED,
        UNAVAILABLE
    }

    record Binding(
            String routeContractKey,
            String platformV1RouteContractKey,
            RouteKind routeKind,
            String method,
            String gatewayPath,
            String servicePath,
            Map<String, String> fixedQuery,
            Set<String> absentQuery,
            String productKey,
            String surfaceKey,
            boolean requiresHcmApplication) {

        boolean matches(HttpServletRequest request) {
            if (!method.equals(request.getMethod())
                    || !pathPattern(servicePath).matcher(request.getRequestURI()).matches()) {
                return false;
            }
            if (fixedQuery.entrySet().stream().anyMatch(entry ->
                    !singleQuery(request, entry.getKey(), entry.getValue()))) {
                return false;
            }
            return absentQuery.stream().allMatch(name ->
                    request.getParameterValues(name) == null);
        }

        private static boolean singleQuery(
                HttpServletRequest request, String name, String expected) {
            String[] values = request.getParameterValues(name);
            return values != null && values.length == 1 && expected.equals(values[0]);
        }

        private static Pattern pathPattern(String template) {
            Matcher matcher = Pattern.compile("\\{[A-Za-z][A-Za-z0-9]*}").matcher(template);
            StringBuilder expression = new StringBuilder("^");
            int offset = 0;
            while (matcher.find()) {
                expression.append(Pattern.quote(template.substring(offset, matcher.start())))
                        .append("[^/]+");
                offset = matcher.end();
            }
            expression.append(Pattern.quote(template.substring(offset))).append('$');
            return Pattern.compile(expression.toString());
        }
    }

    private static final class RouteContractBridgeRequest extends HttpServletRequestWrapper {

        private static final String ROUTE_HEADER = "X-DWP-Route-Contract-Key";
        private final String downstreamRouteKey;
        private final Set<String> removedQueryParameters;

        private RouteContractBridgeRequest(
                HttpServletRequest request,
                String downstreamRouteKey,
                Set<String> removedQueryParameters) {
            super(request);
            this.downstreamRouteKey = downstreamRouteKey;
            this.removedQueryParameters = Set.copyOf(removedQueryParameters);
        }

        @Override
        public String getHeader(String name) {
            return ROUTE_HEADER.equalsIgnoreCase(name)
                    ? downstreamRouteKey : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return ROUTE_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(downstreamRouteKey))
                    : super.getHeaders(name);
        }

        @Override
        public String getParameter(String name) {
            return removedQueryParameters.contains(name) ? null : super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            if (removedQueryParameters.contains(name)) return null;
            String[] values = super.getParameterValues(name);
            return values == null ? null : values.clone();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> retained = new LinkedHashMap<>();
            super.getParameterMap().forEach((name, values) -> {
                if (!removedQueryParameters.contains(name)) {
                    retained.put(name, values == null ? null : values.clone());
                }
            });
            return Collections.unmodifiableMap(retained);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(getParameterMap().keySet());
        }

        @Override
        public String getQueryString() {
            String raw = super.getQueryString();
            if (raw == null || raw.isEmpty() || removedQueryParameters.isEmpty()) return raw;
            String retained = Arrays.stream(raw.split("&", -1))
                    .filter(pair -> !removedQueryParameters.contains(queryName(pair)))
                    .collect(Collectors.joining("&"));
            return retained.isEmpty() ? null : retained;
        }

        private String queryName(String pair) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            try {
                return URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return rawName;
            }
        }
    }
}
