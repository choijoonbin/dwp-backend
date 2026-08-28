package com.dwp.services.platform.communication;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.RolePlaneBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Communications-only v4 owner PEP layered in front of the immutable Platform v1 PEP.
 *
 * <p>The filter is disabled by default while the v4 bundle remains a draft. When enabled, it
 * validates the final v4 route and opaque SELF scope, then bridges the DATA route to its immutable
 * v1 compatibility identity for the downstream Platform filter. No other product is matched.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public final class CommunicationProductSurfacePepFilter extends OncePerRequestFilter {

    static final String HOME_PAGE_ROUTE = "route.communications.work.home.page";
    static final String STORY_DETAIL_DATA_ROUTE =
            "route.communications.work.story-detail.data";
    static final String ACKNOWLEDGEMENT_ACTION_ROUTE =
            "route.communications.work.acknowledgement.action";

    static final Binding HOME_PAGE = new Binding(
            HOME_PAGE_ROUTE,
            HOME_PAGE_ROUTE,
            RouteKind.PAGE,
            "GET",
            "/api/platform/v1/communications",
            "/v1/communications",
            Map.of(),
            Set.of());
    static final Binding STORY_DETAIL_DATA = new Binding(
            STORY_DETAIL_DATA_ROUTE,
            "route.communications.work.for-you-story.page",
            RouteKind.DATA,
            "GET",
            "/api/platform/v1/communications/{communicationId}",
            "/v1/communications/{communicationId}",
            Map.of("view", "detail"),
            Set.of());
    static final Binding ACKNOWLEDGEMENT_ACTION = new Binding(
            ACKNOWLEDGEMENT_ACTION_ROUTE,
            ACKNOWLEDGEMENT_ACTION_ROUTE,
            RouteKind.ACTION,
            "POST",
            "/api/platform/v1/communications/{communicationId}/acknowledgement",
            "/v1/communications/{communicationId}/acknowledgement",
            Map.of(),
            Set.of());
    private static final List<Binding> BINDINGS = List.of(
            HOME_PAGE, STORY_DETAIL_DATA, ACKNOWLEDGEMENT_ACTION);
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");

    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public CommunicationProductSurfacePepFilter(
            @Value("${dwp.platform.product-authorization-communications-v4-enabled:false}")
            boolean enabled,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    List<Binding> bindingContracts() {
        return BINDINGS;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || candidateBindings(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String routeKey = exactHeader(request, "X-DWP-Route-Contract-Key");
        Binding binding = candidateBindings(request).stream()
                .filter(candidate -> candidate.routeContractKey().equals(routeKey))
                .findFirst()
                .orElse(null);
        if (binding == null) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact Communications route authority is required.");
            return;
        }
        String rolloutState = exactHeader(request, "X-DWP-Rollout-State");
        if (!validRollout(
                rolloutState,
                exactHeader(request, "X-DWP-Rollout-Revision"),
                exactHeader(request, "X-DWP-Rollout-Cohort"))) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Communications rollout evidence is invalid.");
            return;
        }
        if (rolloutState.charAt(1) == '0') {
            filterChain.doFilter(request, response);
            return;
        }

        Decision decision = authorize(request, binding);
        if (decision == Decision.UNAVAILABLE) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Communications owner authority is unavailable.");
            return;
        }
        if (decision == Decision.DENIED) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The selected Communications owner scope is not authorized.");
            return;
        }

        String revision = exactHeader(request, "X-DWP-Current-Decision-Revision");
        OffsetDateTime revalidateAt = instant(
                exactHeader(request, "X-DWP-Current-Revalidate-At"));
        if (revision == null || !revision.matches("psr-[a-f0-9]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current Communications authority is missing or expired.");
            return;
        }
        if (binding.routeKind() == RouteKind.ACTION
                && !revision.equals(exactHeader(
                request, "X-DWP-Expected-Decision-Revision"))) {
            writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                    "Communications authority changed after the client decision.");
            return;
        }

        HttpServletRequest downstream = binding.routeContractKey().equals(
                binding.platformV1RouteContractKey())
                ? request
                : new RouteContractBridgeRequest(
                request, binding.platformV1RouteContractKey());
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
        if (!upperValues(request.getHeader("X-DWP-Permissions"))
                .contains("APP.COMMUNICATIONS:VIEW")) {
            return Decision.DENIED;
        }
        String expectedScope = ProductSurfaceScopeKey.key(
                tenantId,
                actorId,
                "communications",
                "communications.work",
                "SELF",
                "SELF");
        if (!constantTimeEquals(expectedScope, scopeKey)) return Decision.DENIED;
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
            Set<String> absentQuery) {

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

        private RouteContractBridgeRequest(
                HttpServletRequest request, String downstreamRouteKey) {
            super(request);
            this.downstreamRouteKey = downstreamRouteKey;
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
    }
}
