package com.dwp.services.people.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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

/**
 * Exact HCM service PEP. The feature switch is a server-readiness latch only;
 * the tenant rollout state remains authoritative for enforcement selection.
 * Provider-support profiles remain fail-closed because the trusted runtime
 * evidence does not yet carry the contract's legal-entity population scope.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class HcmProductSurfacePepFilter extends OncePerRequestFilter {

    static final String RESOURCE_ROLES_HEADER = "X-DWP-Resource-Roles";
    static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String ROUTE_CONTRACT_HEADER = PeopleSecurityHeaders.ROUTE_CONTRACT;
    static final String CURRENT_DECISION_REVISION_HEADER =
            "X-DWP-Current-Decision-Revision";
    static final String CURRENT_DECISION_REVALIDATE_AT_HEADER =
            "X-DWP-Current-Revalidate-At";
    static final String CURRENT_CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String CURRENT_SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String EXPECTED_DECISION_REVISION_HEADER =
            "X-DWP-Expected-Decision-Revision";
    static final String RESPONSE_DECISION_REVISION_HEADER = "X-DWP-Decision-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_STATE_HEADER = PeopleSecurityHeaders.ROLLOUT_STATE;

    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> TENANT_ACCESS_MODES = Set.of("NORMAL", "ELEVATED");

    private final boolean productAuthorizationV3Enabled;
    private final HcmV3PepRegistry registry;
    private final HcmScopeSelectionValidator scopeValidator;
    private final ObjectMapper objectMapper;

    public HcmProductSurfacePepFilter(
            @Value("${dwp.people.product-authorization-v3-enabled:false}")
            boolean productAuthorizationV3Enabled,
            HcmV3PepRegistry registry,
            HcmScopeSelectionValidator scopeValidator,
            ObjectMapper objectMapper) {
        this.productAuthorizationV3Enabled = productAuthorizationV3Enabled;
        this.registry = registry;
        this.scopeValidator = scopeValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !registry.owns(
                request.getMethod(), request.getRequestURI(), request.getQueryString());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        HcmPepContext.clear();
        boolean supportHeaderPresent =
                request.getHeader(PeopleSecurityHeaders.SUPPORT_SESSION) != null;
        String supportSession = exactHeader(
                request, PeopleSecurityHeaders.SUPPORT_SESSION);
        if (supportHeaderPresent && supportSession == null) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM support-session evidence is invalid.");
            return;
        }
        if (supportSession != null) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM provider-support population evidence is unavailable.");
            return;
        }
        String state = exactHeader(request, ROLLOUT_STATE_HEADER);
        if (state == null && !productAuthorizationV3Enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        String revision = exactHeader(request, ROLLOUT_REVISION_HEADER);
        String cohort = exactHeader(request, ROLLOUT_COHORT_HEADER);
        if (!validRolloutEvidence(state, revision, cohort)) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM rollout evidence is missing or invalid.");
            return;
        }
        boolean exactEnforcement = state.charAt(1) == '1';
        if (!exactEnforcement) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!productAuthorizationV3Enabled) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HCM product authorization v3 is not ready for enforcement.");
            return;
        }

        String trustedRoute = exactHeader(request, ROUTE_CONTRACT_HEADER);
        String trustedContext = exactHeader(request, CURRENT_CONTEXT_HEADER);
        String trustedScope = exactHeader(request, CURRENT_SCOPE_HEADER);
        String activeAccessMode = exactHeader(request, ACTIVE_ACCESS_MODE_HEADER);
        if (!trustedText(trustedRoute) || !validContext(trustedContext)
                || !trustedText(trustedScope) || activeAccessMode == null) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM route, context, scope, and access mode evidence is invalid.");
            return;
        }
        Set<String> roles = parse(request.getHeader(PeopleSecurityHeaders.ROLES));
        if (!TENANT_ACCESS_MODES.contains(activeAccessMode)
                || RolePlaneBoundary.isProviderIdentity(roles)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Provider support cannot assume normal HCM authority.");
            return;
        }
        Set<String> permissions = parse(request.getHeader(PeopleSecurityHeaders.PERMISSIONS));
        HcmV3PepRegistry.Decision decision = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        request.getMethod(), request.getRequestURI(), permissions,
                        request.getHeader(RESOURCE_ROLES_HEADER),
                        activeAccessMode,
                        parse(request.getHeader(PeopleSecurityHeaders.SUPPORT_SCOPES)),
                        trustedRoute, request.getQueryString()));
        if (!decision.allowed()) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact HCM route authority required for this operation is missing.");
            return;
        }
        String currentRevision = exactHeader(request, CURRENT_DECISION_REVISION_HEADER);
        OffsetDateTime currentRevalidateAt = validUntil(
                exactHeader(request, CURRENT_DECISION_REVALIDATE_AT_HEADER));
        if (!validCurrentDecision(currentRevision, currentRevalidateAt)) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current HCM authority evidence is missing or expired.");
            return;
        }
        if ("ACTION".equals(decision.authority().routeKind())) {
            String expected = exactHeader(request, EXPECTED_DECISION_REVISION_HEADER);
            if (expected == null || expected.length() > 200
                    || !currentRevision.equals(expected)) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "HCM authority changed after the client decision.");
                return;
            }
        }

        HcmPepContext.set(new HcmPepContext.Evidence(
                decision.authority(), currentRevision, currentRevalidateAt,
                trustedContext, trustedScope, state));
        try {
            scopeValidator.validate(decision.authority());
        } catch (BaseException exception) {
            HcmPepContext.clear();
            ErrorCode code = exception.getErrorCode() == ErrorCode.FORBIDDEN
                    || exception.getErrorCode() == ErrorCode.NOT_FOUND
                    ? ErrorCode.FORBIDDEN : ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            writeError(response, code, code == ErrorCode.FORBIDDEN
                    ? "The selected HCM owner scope is no longer valid."
                    : "The HCM owner scope could not be revalidated.");
            return;
        } catch (RuntimeException exception) {
            HcmPepContext.clear();
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The HCM owner scope could not be revalidated.");
            return;
        }
        request.setAttribute(HcmV3PepRegistry.class.getName() + ".authority",
                decision.authority());
        response.setHeader(RESPONSE_DECISION_REVISION_HEADER, currentRevision);
        try {
            filterChain.doFilter(request, response);
        } finally {
            HcmPepContext.clear();
        }
    }

    private boolean validCurrentDecision(String revision, OffsetDateTime validUntil) {
        return revision != null && revision.matches("psr-[a-f0-9]{64}")
                && validUntil != null && validUntil.isAfter(OffsetDateTime.now());
    }

    private boolean validRolloutEvidence(String state, String revision, String cohort) {
        return state != null && cohort != null && ROLLOUT_STATES.contains(state)
                && ROLLOUT_COHORTS.contains(cohort) && revision != null
                && revision.matches("rollout-[a-f0-9]{64}");
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
        if (values.hasMoreElements() || !trustedText(value)
                || !value.equals(value.trim())) return null;
        return value;
    }

    private boolean validContext(String value) {
        return value != null && value.matches("psc-[a-f0-9]{64}");
    }

    private boolean trustedText(String value) {
        return value != null && !value.isBlank() && value.length() <= 200
                && value.indexOf(',') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private OffsetDateTime validUntil(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private void writeError(
            HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode, message));
    }
}
