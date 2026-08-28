package com.dwp.services.space.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.RolePlaneBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact owner-service PEP for the proposed Spaces v4 DRAFT contract. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class SpaceProductSurfacePepFilter extends OncePerRequestFilter {

    static final String RESOURCE_ROLES_HEADER = "X-DWP-Resource-Roles";
    static final String SUPPORT_SESSION_HEADER = "X-DWP-Support-Session-ID";
    static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String ROUTE_CONTRACT_HEADER = "X-DWP-Route-Contract-Key";
    static final String CURRENT_DECISION_REVISION_HEADER =
            "X-DWP-Current-Decision-Revision";
    static final String CURRENT_REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_DECISION_REVISION_HEADER =
            "X-DWP-Expected-Decision-Revision";
    static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String RESPONSE_DECISION_REVISION_HEADER = "X-DWP-Decision-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";

    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> TENANT_ACCESS_MODES = Set.of("NORMAL", "ELEVATED");

    private final boolean productAuthorizationV4Enabled;
    private final SpaceProductSurfacePepRegistry registry;
    private final ObjectMapper objectMapper;

    public SpaceProductSurfacePepFilter(
            @Value("${dwp.space.product-authorization-v4-enabled:false}")
            boolean productAuthorizationV4Enabled,
            SpaceProductSurfacePepRegistry registry,
            ObjectMapper objectMapper) {
        this.productAuthorizationV4Enabled = productAuthorizationV4Enabled;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !registry.ownsOwner(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        boolean rolloutHeaderPresent = request.getHeader(ROLLOUT_STATE_HEADER) != null;
        String state = exactHeader(request, ROLLOUT_STATE_HEADER);
        if (!rolloutHeaderPresent && !productAuthorizationV4Enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!validRollout(state,
                exactHeader(request, ROLLOUT_REVISION_HEADER),
                exactHeader(request, ROLLOUT_COHORT_HEADER))) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Spaces rollout evidence is missing or invalid.");
            return;
        }
        if (state.charAt(1) != '1') {
            filterChain.doFilter(request, response);
            return;
        }
        if (!productAuthorizationV4Enabled) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Spaces product authorization v4 is not ready for enforcement.");
            return;
        }

        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        String accessMode = exactHeader(request, ACTIVE_ACCESS_MODE_HEADER);
        if (!TENANT_ACCESS_MODES.contains(accessMode)
                || request.getHeader(SUPPORT_SESSION_HEADER) != null
                || RolePlaneBoundary.isProviderIdentity(subject.roles())) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Spaces provider-support authority is not configured.");
            return;
        }
        String route = exactHeader(request, ROUTE_CONTRACT_HEADER);
        String context = exactHeader(request, CONTEXT_HEADER);
        String scope = exactHeader(request, SCOPE_HEADER);
        String currentRevision = exactHeader(request, CURRENT_DECISION_REVISION_HEADER);
        OffsetDateTime revalidateAt = instant(
                exactHeader(request, CURRENT_REVALIDATE_AT_HEADER));
        if (route == null || context == null
                || !context.matches("psc-[a-f0-9]{64}") || scope == null
                || currentRevision == null
                || !currentRevision.matches("psr-[a-f0-9]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Spaces route, context, scope, and revision evidence is invalid.");
            return;
        }

        SpaceProductSurfacePepRegistry.Decision decision = registry.authorize(
                route, request.getMethod(), request.getRequestURI(),
                parse(request.getHeader(SpaceSecurityFilter.PERMISSIONS_HEADER)));
        if (!decision.allowed() || !scopeMatches(subject, scope, decision.binding().surfaceKey())
                || !responsibilityMatches(request, decision.binding().surfaceKey())) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact Spaces route and scope authority is required.");
            return;
        }
        if ("ACTION".equals(decision.binding().routeKind())) {
            String expected = exactHeader(request, EXPECTED_DECISION_REVISION_HEADER);
            if (!currentRevision.equals(expected)) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "Spaces authority changed after the client decision.");
                return;
            }
        }

        response.setHeader(RESPONSE_DECISION_REVISION_HEADER, currentRevision);
        filterChain.doFilter(request, response);
    }

    private boolean scopeMatches(
            SpaceRequestContext.Subject subject,
            String actual,
            String surfaceKey) {
        String expected = "spaces.management".equals(surfaceKey)
                ? ProductSurfaceScopeKey.resourceSet(
                        subject.tenantId(), subject.userId(), "spaces",
                        surfaceKey, "APP_SPACES")
                : ProductSurfaceScopeKey.key(
                        subject.tenantId(), subject.userId(), "spaces",
                        surfaceKey, "SELF", "SELF");
        return expected.equals(actual);
    }

    private boolean responsibilityMatches(HttpServletRequest request, String surfaceKey) {
        if (!"spaces.management".equals(surfaceKey)) return true;
        return parse(request.getHeader(RESOURCE_ROLES_HEADER))
                .contains("APP_CONFIG_ADMIN@APP_SPACES");
    }

    private boolean validRollout(String state, String revision, String cohort) {
        return state != null && ROLLOUT_STATES.contains(state)
                && revision != null && revision.matches("rollout-[a-f0-9]{64}")
                && cohort != null && ROLLOUT_COHORTS.contains(cohort);
    }

    private Set<String> parse(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || !trustedText(value)) return null;
        return value;
    }

    private boolean trustedText(String value) {
        return value != null && !value.isBlank() && value.length() <= 500
                && value.equals(value.trim())
                && value.indexOf(',') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private OffsetDateTime instant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private void writeError(
            HttpServletResponse response,
            ErrorCode errorCode,
            String message) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(), ApiResponse.error(errorCode, message));
    }
}
