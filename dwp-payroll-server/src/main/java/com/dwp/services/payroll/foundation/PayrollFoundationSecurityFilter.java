package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Establishes the exact Gateway-owned route decision before PAY authority is projected. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollFoundationSecurityFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/v1/hris/payroll/foundation";
    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String SUPPORT_SESSION_HEADER = "X-DWP-Support-Session-ID";
    static final String ROUTE_HEADER = "X-DWP-Route-Contract-Key";
    static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String CURRENT_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    static final String CURRENT_REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_REVISION_HEADER = "X-DWP-Expected-Decision-Revision";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String RESPONSE_REVISION_HEADER = "X-DWP-Decision-Revision";

    private static final Set<String> ENFORCED_ROLLOUT_STATES = Set.of("110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> ACCESS_MODES = Set.of("NORMAL", "ELEVATED");
    private static final String APP_ENTITLEMENT = "APP.HCM:VIEW";

    private final String serviceToken;
    private final boolean productAuthorizationEnabled;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    PayrollFoundationSecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            @Value("${dwp.payroll.product-authorization-enabled:true}")
            boolean productAuthorizationEnabled,
            ObjectMapper objectMapper) {
        this(serviceToken, productAuthorizationEnabled, objectMapper, Clock.systemUTC());
    }

    /** Test-only constructor retaining the production verifier with a system clock. */
    PayrollFoundationSecurityFilter(String serviceToken, ObjectMapper objectMapper) {
        this(serviceToken, true, objectMapper, Clock.systemUTC());
    }

    PayrollFoundationSecurityFilter(
            String serviceToken,
            boolean productAuthorizationEnabled,
            ObjectMapper objectMapper,
            Clock clock) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.productAuthorizationEnabled = productAuthorizationEnabled;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        PayrollFoundationRequestContext.clear();
        if (!productAuthorizationEnabled || serviceToken.isBlank()) {
            unavailable(response, "Payroll product authority is not configured.");
            return;
        }
        if (!constantTimeEquals(serviceToken, singleHeader(request, SERVICE_TOKEN_HEADER))) {
            unavailable(response, "Verified payroll Gateway identity is unavailable.");
            return;
        }

        PayrollFoundationRoute route = PayrollFoundationRoute.resolve(
                request.getMethod(), request.getRequestURI()).orElse(null);
        if (route == null) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact payroll owner route authority is required.");
            return;
        }

        Long tenantId = positiveLong(singleHeader(request, TENANT_HEADER));
        Long actorId = positiveLong(singleHeader(request, USER_HEADER));
        String routeKey = singleHeader(request, ROUTE_HEADER);
        String contextKey = singleHeader(request, CONTEXT_HEADER);
        String contextScopeKey = singleHeader(request, SCOPE_HEADER);
        String accessMode = singleHeader(request, ACCESS_MODE_HEADER);
        String decisionRevision = singleHeader(request, CURRENT_REVISION_HEADER);
        Instant revalidateAt = instant(singleHeader(request, CURRENT_REVALIDATE_AT_HEADER));
        String rolloutState = singleHeader(request, ROLLOUT_STATE_HEADER);
        String rolloutRevision = singleHeader(request, ROLLOUT_REVISION_HEADER);
        String rolloutCohort = singleHeader(request, ROLLOUT_COHORT_HEADER);
        if (tenantId == null || actorId == null
                || !route.routeContractKey().equals(routeKey)
                || !canonicalContext(contextKey)
                || !canonicalScope(contextScopeKey)
                || !ACCESS_MODES.contains(accessMode)
                || !canonicalDecision(decisionRevision)
                || revalidateAt == null || !revalidateAt.isAfter(clock.instant())
                || !ENFORCED_ROLLOUT_STATES.contains(rolloutState)
                || rolloutRevision == null
                || !rolloutRevision.matches("rollout-[0-9a-f]{64}")
                || !ROLLOUT_COHORTS.contains(rolloutCohort)) {
            unavailable(response, "Current payroll route authority is absent, stale or mismatched.");
            return;
        }
        if (request.getHeader(SUPPORT_SESSION_HEADER) != null) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Provider support cannot assume payroll owner authority.");
            return;
        }
        Set<String> permissions = exactTokens(request, PERMISSIONS_HEADER);
        if (permissions == null
                || !permissions.contains(APP_ENTITLEMENT)
                || !permissions.contains(route.capability())) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact payroll capability is not present in verified identity evidence.");
            return;
        }
        if (route.command()) {
            String expected = singleHeader(request, EXPECTED_REVISION_HEADER);
            if (!decisionRevision.equals(expected)) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "Payroll authority changed after the client decision.");
                return;
            }
        }
        if (route.requiresElevatedAccess() && !"ELEVATED".equals(accessMode)) {
            writeError(response, ErrorCode.STEP_UP_REQUIRED,
                    "Fresh elevated authority is required for this payroll command.");
            return;
        }

        PayrollFoundationRequestContext.set(
                new PayrollFoundationRequestContext.VerifiedSubject(
                        tenantId,
                        actorId,
                        route.action(),
                        route.action() == PayrollFoundationModels.FoundationAction.VIEW
                                ? "PAYROLL_AUDIT" : "PAYROLL_CONFIGURATION",
                        contextKey,
                        contextScopeKey,
                        rolloutRevision,
                        decisionRevision,
                        revalidateAt,
                        route.routeContractKey()));
        response.setHeader(RESPONSE_REVISION_HEADER, decisionRevision);
        try {
            filterChain.doFilter(request, response);
        } finally {
            PayrollFoundationRequestContext.clear();
        }
    }

    private Set<String> exactTokens(HttpServletRequest request, String name) {
        List<String> headers = Collections.list(request.getHeaders(name));
        if (headers.size() != 1) return null;
        String value = headers.getFirst();
        if (value == null || value.isBlank() || value.length() > 4_000
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip()) || !result.add(token)) {
                return null;
            }
        }
        return Set.copyOf(result);
    }

    private String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > 500
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? null : value;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private boolean canonicalContext(String value) {
        return value != null && value.matches("psc-[0-9a-f]{64}");
    }

    private boolean canonicalScope(String value) {
        return HcmEligibilityScopeKey.isCanonical(value);
    }

    private boolean canonicalDecision(String value) {
        return value != null && value.matches("psr-[0-9a-f]{64}");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void unavailable(HttpServletResponse response, String message) throws IOException {
        writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
