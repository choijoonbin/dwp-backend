package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

/** Establishes the exact v48 HCM-management authority for the Foundation settings tab. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
@ConditionalOnProperty(
        name = {
                "dwp.hris.payroll-foundation.wave1.enabled",
                "dwp.hris.payroll-foundation.settings.enabled"
        },
        havingValue = "true",
        matchIfMissing = false)
class PayrollFoundationSettingsSecurityFilter extends OncePerRequestFilter {

    private static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String ACTOR = "X-DWP-User-ID";
    private static final String SUBJECT = "X-DWP-Subject-Principal-Public-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROUTE = "X-DWP-Route-Contract-Key";
    private static final String CONTEXT = "X-DWP-Context-Key";
    private static final String SCOPE = "X-DWP-Context-Scope-Key";
    private static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    private static final String CURRENT_REVISION = "X-DWP-Current-Decision-Revision";
    private static final String EXPECTED_REVISION = "X-DWP-Expected-Decision-Revision";
    private static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    private static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    private static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    private static final String PURPOSE = "X-Purpose-Code";
    private static final String ROLLOUT_STATE = "X-DWP-Rollout-State";
    private static final String ROLLOUT_REVISION = "X-DWP-Rollout-Revision";
    private static final String ROLLOUT_COHORT = "X-DWP-Rollout-Cohort";
    private static final String STEP_UP_CHALLENGE = "X-DWP-Step-Up-Challenge";
    private static final String RESPONSE_REVISION = "X-DWP-Decision-Revision";
    private static final String APP_ENTITLEMENT = "APP.HCM:VIEW";
    private static final Set<String> ENFORCED_STATES = Set.of("110", "111");
    private static final Set<String> ACCESS_MODES = Set.of("NORMAL", "ELEVATED");
    private static final Set<String> COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");

    private final String serviceToken;
    private final boolean productAuthorizationEnabled;
    private final PayrollFoundationSettingsV48PepRegistry registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    PayrollFoundationSettingsSecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            @Value("${dwp.payroll.product-authorization-enabled:true}")
            boolean productAuthorizationEnabled,
            PayrollFoundationSettingsV48PepRegistry registry,
            ObjectMapper objectMapper) {
        this(serviceToken, productAuthorizationEnabled, registry, objectMapper, Clock.systemUTC());
    }

    PayrollFoundationSettingsSecurityFilter(
            String serviceToken,
            boolean productAuthorizationEnabled,
            PayrollFoundationSettingsV48PepRegistry registry,
            ObjectMapper objectMapper,
            Clock clock) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.productAuthorizationEnabled = productAuthorizationEnabled;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PayrollFoundationSettingsRoute.PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        PayrollFoundationRequestContext.clear();
        if (!productAuthorizationEnabled || serviceToken.isBlank()
                || !constantTimeEquals(serviceToken, singleHeader(request, SERVICE_TOKEN))) {
            unavailable(response, "Verified payroll Gateway identity is unavailable.");
            return;
        }
        String authorization = singleHeader(request, "Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            writeError(response, ErrorCode.UNAUTHORIZED, "Authorization is required.");
            return;
        }
        PayrollFoundationSettingsRoute route = PayrollFoundationSettingsRoute.resolve(
                request.getMethod(), request.getRequestURI()).orElse(null);
        if (route == null) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact Payroll settings owner route authority is required.");
            return;
        }
        PayrollFoundationSettingsV48PepRegistry.Binding binding = registry.binding(route);

        Long tenantId = positiveLong(singleHeader(request, TENANT));
        Long actorId = positiveLong(singleHeader(request, ACTOR));
        UUID subjectId = uuid(singleHeader(request, SUBJECT));
        String contextKey = singleHeader(request, CONTEXT);
        String scopeKey = singleHeader(request, SCOPE);
        String accessMode = singleHeader(request, ACCESS_MODE);
        String decisionRevision = singleHeader(request, CURRENT_REVISION);
        Instant revalidateAt = instant(singleHeader(request, REVALIDATE_AT));
        String fieldPolicyRevision = revision(singleHeader(request, FIELD_POLICY_REVISION));
        String authorizationRevision = revision(singleHeader(request, AUTHORIZATION_REVISION));
        String rolloutState = singleHeader(request, ROLLOUT_STATE);
        String rolloutRevision = singleHeader(request, ROLLOUT_REVISION);
        String rolloutCohort = singleHeader(request, ROLLOUT_COHORT);
        if (tenantId == null || actorId == null || subjectId == null
                || !binding.routeContractKey().equals(singleHeader(request, ROUTE))
                || !canonicalContext(contextKey)
                || !HcmEligibilityScopeKey.isCanonical(scopeKey)
                || !ACCESS_MODES.contains(accessMode)
                || !canonicalDecision(decisionRevision)
                || revalidateAt == null || !revalidateAt.isAfter(clock.instant())
                || fieldPolicyRevision == null || authorizationRevision == null
                || !PayrollFoundationSettingsRoute.PURPOSE.equals(singleHeader(request, PURPOSE))
                || !ENFORCED_STATES.contains(rolloutState)
                || !canonicalRollout(rolloutRevision)
                || !COHORTS.contains(rolloutCohort)) {
            unavailable(response,
                    "Current Payroll settings authority is absent, stale or mismatched.");
            return;
        }
        if (request.getHeader("X-DWP-Support-Session-ID") != null
                || request.getHeader("X-Tenant-Override") != null
                || request.getHeader("X-Actor-Override") != null
                || request.getHeader("X-Formula-Class") != null
                || request.getHeader("X-Job-Method") != null
                || request.getHeader("If-Match") != null
                || request.getHeader("X-Step-Up-Session") != null) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Caller-controlled authority or executable overrides are forbidden.");
            return;
        }
        Set<String> permissions = exactTokens(request, PERMISSIONS);
        if (permissions == null || !permissions.contains(APP_ENTITLEMENT)
                || !permissions.contains(binding.capabilityCode())) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact Payroll settings capability is not present.");
            return;
        }
        if (route.command()) {
            String expected = singleHeader(request, EXPECTED_REVISION);
            if (!decisionRevision.equals(expected)) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "Payroll settings authority changed after the client decision.");
                return;
            }
        }
        if (route.elevated() && (!"ELEVATED".equals(accessMode)
                || singleHeader(request, STEP_UP_CHALLENGE) == null)) {
            writeError(response, ErrorCode.STEP_UP_REQUIRED,
                    "Fresh elevated authority is required for this Payroll settings command.");
            return;
        }

        PayrollFoundationRequestContext.set(
                new PayrollFoundationRequestContext.VerifiedSubject(
                        tenantId,
                        actorId,
                        route.action(),
                        PayrollFoundationSettingsRoute.projectedActions(permissions),
                        PayrollFoundationSettingsRoute.PURPOSE,
                        PayrollFoundationSettingsRoute.PURPOSE,
                        contextKey,
                        scopeKey,
                        rolloutRevision,
                        decisionRevision,
                        revalidateAt,
                        route.routeContractKey()));
        response.setHeader(RESPONSE_REVISION, decisionRevision);
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
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip()) || !result.add(token)) return null;
        }
        return Set.copyOf(result);
    }

    private String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > 1_024
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 ? null : value;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
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

    private String revision(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                ? value : null;
    }

    private boolean canonicalContext(String value) {
        return value != null && value.matches("psc-[0-9a-f]{64}");
    }

    private boolean canonicalDecision(String value) {
        return value != null && value.matches("psr-[0-9a-f]{64}");
    }

    private boolean canonicalRollout(String value) {
        return value != null && value.matches("rollout-[0-9a-f]{64}");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void unavailable(HttpServletResponse response, String message) throws IOException {
        writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private void writeError(
            HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
