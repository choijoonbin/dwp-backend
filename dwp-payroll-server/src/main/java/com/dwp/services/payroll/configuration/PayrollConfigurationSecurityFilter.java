package com.dwp.services.payroll.configuration;

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
import java.util.UUID;

/** Establishes exact current Gateway authority for IAQ-055 and PEP-PAY-004..008. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
@ConditionalOnProperty(
        name = "dwp.hris.payroll-configuration.wave2.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollConfigurationSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String ACTOR = "X-DWP-User-ID";
    static final String SUBJECT_PUBLIC_ID = "X-DWP-Subject-Principal-Public-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ROUTE_CONTRACT = "X-DWP-Route-Contract-Key";
    static final String CONTEXT_SCOPE = "X-DWP-Context-Scope-Key";
    static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    static final String POLICY_REVISION = "X-DWP-Rollout-Revision";
    static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String PURPOSE = "X-Purpose-Code";
    static final String CORRELATION = "X-Correlation-Id";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String STEP_UP_CHALLENGE = "X-DWP-Step-Up-Challenge";
    private static final String APP_ENTITLEMENT = "APP.HCM:VIEW";
    private static final String PURPOSE_VALUE = "PAYROLL_CONFIGURATION";

    private final String serviceToken;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    PayrollConfigurationSecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this(serviceToken, objectMapper, Clock.systemUTC());
    }

    PayrollConfigurationSecurityFilter(
            String serviceToken, ObjectMapper objectMapper, Clock clock) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PayrollConfigurationRoute.resolve(
                request.getMethod(), request.getRequestURI()).isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        PayrollConfigurationRequestContext.clear();
        if (serviceToken.isBlank()
                || !constantTimeEquals(serviceToken, singleHeader(request, SERVICE_TOKEN))) {
            unavailable(response, "Verified payroll Gateway identity is unavailable.");
            return;
        }
        String authorization = singleHeader(request, "Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Authorization is required.");
            return;
        }
        PayrollConfigurationRoute route = PayrollConfigurationRoute.resolve(
                request.getMethod(), request.getRequestURI()).orElseThrow();
        Long tenantId = positiveLong(singleHeader(request, TENANT));
        Long actorId = positiveLong(singleHeader(request, ACTOR));
        UUID subjectId = uuid(singleHeader(request, SUBJECT_PUBLIC_ID));
        String contextScopeKey = singleHeader(request, CONTEXT_SCOPE);
        String fieldPolicyRevision = revision(singleHeader(request, FIELD_POLICY_REVISION));
        String policyRevision = revision(singleHeader(request, POLICY_REVISION));
        String authorizationRevision = revision(
                singleHeader(request, AUTHORIZATION_REVISION));
        Instant revalidateAt = instant(singleHeader(request, REVALIDATE_AT));
        String purpose = singleHeader(request, PURPOSE);
        String accessMode = singleHeader(request, ACCESS_MODE);
        if (tenantId == null || actorId == null || subjectId == null
                || !route.authorityContractKey().equals(singleHeader(request, ROUTE_CONTRACT))
                || !HcmEligibilityScopeKey.isCanonical(contextScopeKey)
                || fieldPolicyRevision == null || policyRevision == null
                || authorizationRevision == null
                || revalidateAt == null || !revalidateAt.isAfter(clock.instant())
                || !PURPOSE_VALUE.equals(purpose)
                || !Set.of("NORMAL", "ELEVATED").contains(accessMode)) {
            unavailable(response, "Current payroll configuration authority is incomplete.");
            return;
        }
        if (request.getHeader("X-Tenant-Override") != null
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
                || !permissions.contains(route.permission())) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact PAY-006 permission is not present.");
            return;
        }
        if (route.command()) {
            if (uuid(singleHeader(request, IDEMPOTENCY)) == null
                    || uuid(singleHeader(request, CORRELATION)) == null) {
                writeError(response, ErrorCode.INVALID_INPUT_VALUE,
                        "Idempotency-Key and X-Correlation-Id must be UUIDs.");
                return;
            }
        }
        if (route.elevated()
                && (!"ELEVATED".equals(accessMode)
                || singleHeader(request, STEP_UP_CHALLENGE) == null)) {
            writeError(response, ErrorCode.STEP_UP_REQUIRED,
                    "A current X-DWP-Step-Up-Challenge is required.");
            return;
        }
        Set<PayrollConfigurationAccess.Action> projected =
                PayrollConfigurationRoute.projectedActions(permissions);
        PayrollConfigurationRequestContext.set(
                new PayrollConfigurationRequestContext.VerifiedSubject(
                        tenantId, actorId, subjectId, route.action(), projected,
                        purpose, contextScopeKey, fieldPolicyRevision,
                        policyRevision, authorizationRevision, revalidateAt,
                        route.authorityContractKey()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            PayrollConfigurationRequestContext.clear();
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
        return value == null || value.isBlank() || value.length() > 1_024
                || !value.equals(value.strip())
                || value.indexOf(',') >= 0
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
