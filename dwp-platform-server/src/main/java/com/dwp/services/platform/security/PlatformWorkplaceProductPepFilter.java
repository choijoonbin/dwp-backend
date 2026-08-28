package com.dwp.services.platform.security;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact Workplace owner-service PEP layered after Platform service authentication. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class PlatformWorkplaceProductPepFilter extends OncePerRequestFilter {

    static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> TENANT_ACCESS_MODES = Set.of("NORMAL", "ELEVATED");

    private final boolean productAuthorizationV4Enabled;
    private final PlatformWorkplaceProductPepRegistry registry;
    private final ObjectMapper objectMapper;

    public PlatformWorkplaceProductPepFilter(
            @Value("${dwp.platform.product-authorization-workplace-v4-enabled:false}")
            boolean productAuthorizationV4Enabled,
            PlatformWorkplaceProductPepRegistry registry,
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
        boolean rolloutHeaderPresent = request.getHeader(
                PlatformSecurityFilter.ROLLOUT_STATE_HEADER) != null;
        String rolloutState = exactHeader(
                request, PlatformSecurityFilter.ROLLOUT_STATE_HEADER);
        if (!rolloutHeaderPresent && !productAuthorizationV4Enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!validRollout(
                rolloutState,
                exactHeader(request, PlatformSecurityFilter.ROLLOUT_REVISION_HEADER),
                exactHeader(request, PlatformSecurityFilter.ROLLOUT_COHORT_HEADER))) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Workplace rollout evidence is missing or invalid.");
            return;
        }
        if (rolloutState.charAt(1) == '0') {
            filterChain.doFilter(request, response);
            return;
        }
        if (!productAuthorizationV4Enabled) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Workplace product authorization v4 is not ready for enforcement.");
            return;
        }

        Long tenantId = positiveLong(request.getHeader(PlatformSecurityFilter.TENANT_HEADER));
        Long actorId = positiveLong(request.getHeader(PlatformSecurityFilter.USER_HEADER));
        String accessMode = exactHeader(request, ACTIVE_ACCESS_MODE_HEADER);
        String context = exactHeader(request, PlatformSecurityFilter.CONTEXT_HEADER);
        String scope = exactHeader(request, PlatformSecurityFilter.SCOPE_HEADER);
        if (tenantId == null || actorId == null
                || context == null || !context.matches("psc-[a-f0-9]{64}")) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Workplace owner authority is unavailable.");
            return;
        }
        if (!TENANT_ACCESS_MODES.contains(accessMode)
                || request.getHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER) != null
                || RolePlaneBoundary.isProviderIdentity(
                values(request.getHeader(PlatformSecurityFilter.ROLES_HEADER)))) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Workplace provider-support authority is not configured.");
            return;
        }

        PlatformWorkplaceProductPepRegistry.Decision decision = registry.authorize(
                exactHeader(request, PlatformSecurityFilter.ROUTE_CONTRACT_HEADER),
                request.getMethod(),
                request.getRequestURI(),
                upperValues(request.getHeader(PlatformSecurityFilter.PERMISSIONS_HEADER)));
        if (!decision.allowed()
                || !scopeMatches(tenantId, actorId, scope, decision.binding().surfaceKey())
                || !responsibilityMatches(request, decision.binding().surfaceKey())) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact Workplace route and scope authority is required.");
            return;
        }

        String revision = exactHeader(
                request, PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER);
        OffsetDateTime revalidateAt = instant(exactHeader(
                request, PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER));
        if (revision == null || !revision.matches("psr-[a-f0-9]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current Workplace authority is missing or expired.");
            return;
        }
        if ("ACTION".equals(decision.binding().routeKind())
                && !revision.equals(exactHeader(
                request, PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER))) {
            writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                    "Workplace authority changed after the client decision.");
            return;
        }

        response.setHeader(PlatformSecurityFilter.RESPONSE_DECISION_REVISION_HEADER, revision);
        request.setAttribute(
                PlatformWorkplaceProductPepRegistry.class.getName() + ".binding",
                decision.binding());
        filterChain.doFilter(request, response);
    }

    private boolean scopeMatches(
            long tenantId,
            long actorId,
            String actualScope,
            String surfaceKey) {
        if (actualScope == null) return false;
        String expectedScope = "workplace.management".equals(surfaceKey)
                ? ProductSurfaceScopeKey.resourceSet(
                        tenantId, actorId, "workplace", surfaceKey, "APP_WORKPLACE")
                : ProductSurfaceScopeKey.key(
                        tenantId, actorId, "workplace", surfaceKey, "SELF", "SELF");
        return MessageDigest.isEqual(
                expectedScope.getBytes(StandardCharsets.UTF_8),
                actualScope.getBytes(StandardCharsets.UTF_8));
    }

    private boolean responsibilityMatches(HttpServletRequest request, String surfaceKey) {
        return !"workplace.management".equals(surfaceKey)
                || ResourceRoleAuthorization.has(
                request.getHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER),
                "APP_CONFIG_ADMIN", "APP_WORKPLACE");
    }

    private boolean validRollout(String state, String revision, String cohort) {
        return state != null && ROLLOUT_STATES.contains(state)
                && revision != null && revision.matches("rollout-[a-f0-9]{64}")
                && cohort != null && ROLLOUT_COHORTS.contains(cohort);
    }

    private Set<String> values(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(",", -1))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> upperValues(String header) {
        return values(header).stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 500 || !value.equals(value.trim())
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
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
