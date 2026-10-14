package com.dwp.services.payroll.security;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollCalculationSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String ACTOR = "X-DWP-User-ID";
    static final String SUBJECT = "X-DWP-Subject-Principal-Public-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ROUTE = "X-DWP-Route-Contract-Key";
    static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    static final String CONTEXT = "X-DWP-Context-Key";
    static final String SCOPE = "X-DWP-Context-Scope-Key";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    static final String POPULATION_KIND = "X-DWP-Population-Kind";
    static final String PURPOSE = "X-Purpose-Code";
    static final String EXPECTED_DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String EXPECTED_VERSION = "X-DWP-Expected-Object-Version";
    private final String serviceToken;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PayrollV41PepRegistry registry;

    @Autowired
    PayrollCalculationSecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            ObjectMapper mapper,
            PayrollV41PepRegistry registry) {
        this(serviceToken, mapper, Clock.systemUTC(), registry);
    }

    PayrollCalculationSecurityFilter(
            String serviceToken,
            ObjectMapper mapper,
            Clock clock,
            PayrollV41PepRegistry registry) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.mapper = mapper;
        this.clock = clock;
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return registry.resolve(
                request.getMethod(), request.getRequestURI()).isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        PayrollCalculationRequestContext.clear();
        if (serviceToken.isBlank()
                || !constantTimeEquals(serviceToken, singleHeader(request, SERVICE_TOKEN))) {
            error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Verified Payroll Gateway identity is unavailable.");
            return;
        }
        String authorization = singleHeader(request, "Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            error(response, ErrorCode.UNAUTHORIZED, "Authorization is required.");
            return;
        }
        PayrollV41PepRegistry.Binding binding = registry.resolve(
                request.getMethod(), request.getRequestURI()).orElseThrow();
        PayrollCalculationRoute route = binding.route();
        if (!request.getParameterMap().isEmpty()) {
            error(response, ErrorCode.INVALID_INPUT_VALUE,
                    "Payroll calculation routes have closed query schemas.");
            return;
        }
        Long tenantId = positiveLong(singleHeader(request, TENANT));
        Long actorId = positiveLong(singleHeader(request, ACTOR));
        UUID subject = uuid(singleHeader(request, SUBJECT));
        String decisionRevision = safeToken(singleHeader(request, DECISION_REVISION), 200);
        Instant revalidateAt = instant(singleHeader(request, REVALIDATE_AT));
        String context = safeToken(singleHeader(request, CONTEXT), 160);
        String scope = singleHeader(request, SCOPE);
        String accessMode = singleHeader(request, ACCESS_MODE);
        Long fieldPolicyRevision = positiveLong(singleHeader(request, FIELD_POLICY_REVISION));
        Long authorizationRevision = positiveLong(
                singleHeader(request, AUTHORIZATION_REVISION));
        String populationKind = singleHeader(request, POPULATION_KIND);
        String purpose = singleHeader(request, PURPOSE);
        boolean populationValid = route.populationAllowed(populationKind);
        if (tenantId == null || actorId == null || subject == null
                || decisionRevision == null || revalidateAt == null
                || !revalidateAt.isAfter(clock.instant()) || context == null
                || !HcmEligibilityScopeKey.isCanonical(scope)
                || !Set.of("NORMAL", "ELEVATED").contains(accessMode)
                || fieldPolicyRevision == null || authorizationRevision == null
                || !populationValid || !route.purposeCode().equals(purpose)
                || !route.routeContractKey().equals(singleHeader(request, ROUTE))) {
            error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current generated Payroll authority is incomplete.");
            return;
        }
        if (hasForbiddenOverrides(request)) {
            error(response, ErrorCode.FORBIDDEN,
                    "Caller-controlled authority or field projection overrides are forbidden.");
            return;
        }
        Set<String> permissions = exactTokens(request, PERMISSIONS);
        if (permissions == null || !permissions.contains("APP.HCM:VIEW")
                || !binding.authorizedBy(permissions)) {
            error(response, ErrorCode.FORBIDDEN,
                    "The exact generated Payroll capability is not present.");
            return;
        }
        if (route.command()) {
            String idempotency = singleHeader(request, IDEMPOTENCY);
            Long expectedVersion = nonnegativeLong(singleHeader(request, EXPECTED_VERSION));
            if (idempotency == null || idempotency.length() > 200 || expectedVersion == null) {
                error(response, ErrorCode.INVALID_INPUT_VALUE,
                        "Idempotency-Key and X-DWP-Expected-Object-Version are required.");
                return;
            }
        }
        if (binding.highRisk()) {
            String expectedDecision = singleHeader(request, EXPECTED_DECISION_REVISION);
            String challenge = singleHeader(request, STEP_UP, 16_384);
            if (!binding.activeAccessModes().contains(accessMode)
                    || challenge == null) {
                error(response, ErrorCode.STEP_UP_REQUIRED,
                        "A fresh command-bound Payroll step-up challenge is required.");
                return;
            }
            if (!decisionRevision.equals(expectedDecision)) {
                error(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "The Payroll decision revision changed.");
                return;
            }
        }

        PayrollCalculationRequestContext.set(new PayrollCalculationAuthority.Subject(
                tenantId, actorId, subject, route.action(), route.routeContractKey(),
                binding.matchedCapabilityContract(permissions), purpose,
                context, scope, populationKind,
                fieldPolicyRevision, authorizationRevision, decisionRevision,
                revalidateAt, accessMode, permissions));
        try {
            chain.doFilter(request, response);
        } finally {
            PayrollCalculationRequestContext.clear();
        }
    }

    private boolean hasForbiddenOverrides(HttpServletRequest request) {
        return request.getHeader("X-Tenant-Override") != null
                || request.getHeader("X-Actor-Override") != null
                || request.getHeader("X-DWP-Masked-Field-Groups") != null
                || request.getHeader("X-DWP-Omitted-Field-Groups") != null
                || request.getHeader("If-Match") != null
                || request.getHeader("X-Step-Up-Session") != null;
    }

    private Set<String> exactTokens(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.isBlank() || value.length() > 4_000
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip()) || !result.add(token)) {
                return null;
            }
        }
        return Set.copyOf(result);
    }

    private String singleHeader(HttpServletRequest request, String name) {
        return singleHeader(request, name, 1_024);
    }

    private String singleHeader(HttpServletRequest request, String name, int maximum) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 ? null : value;
    }

    private String safeToken(String value, int maximum) {
        return value != null && value.length() <= maximum
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*") ? value : null;
    }

    private Long positiveLong(String value) {
        Long parsed = nonnegativeLong(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private Long nonnegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
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

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void error(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
