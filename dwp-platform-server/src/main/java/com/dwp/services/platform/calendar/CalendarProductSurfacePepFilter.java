package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
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
import java.util.Arrays;
import java.util.Set;

/** Calendar owner-service PEP layered after the generated Platform route PEP. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class CalendarProductSurfacePepFilter extends OncePerRequestFilter {

    static final String ROUTE_CONTRACT_HEADER = "X-DWP-Route-Contract-Key";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String CURRENT_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    static final String CURRENT_REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_REVISION_HEADER = "X-DWP-Expected-Decision-Revision";
    static final String RESPONSE_REVISION_HEADER = "X-DWP-Decision-Revision";
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");

    private final boolean productAuthorizationV4Enabled;
    private final CalendarProductSurfaceContract contract;
    private final CalendarProductSurfaceAccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    public CalendarProductSurfacePepFilter(
            @Value("${dwp.platform.product-authorization-calendar-v4-enabled:false}")
            boolean productAuthorizationV4Enabled,
            CalendarProductSurfaceContract contract,
            CalendarProductSurfaceAccessPolicy accessPolicy,
            ObjectMapper objectMapper) {
        this.productAuthorizationV4Enabled = productAuthorizationV4Enabled;
        this.contract = contract;
        this.accessPolicy = accessPolicy;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return contract.resolveOwner(request.getMethod(), request.getRequestURI()).isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        boolean rolloutHeaderPresent = request.getHeader(ROLLOUT_STATE_HEADER) != null;
        String rolloutState = exactHeader(request, ROLLOUT_STATE_HEADER);
        if (!rolloutHeaderPresent && !productAuthorizationV4Enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!validRolloutEvidence(
                rolloutState,
                exactHeader(request, ROLLOUT_REVISION_HEADER),
                exactHeader(request, ROLLOUT_COHORT_HEADER))) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Calendar rollout evidence is invalid.");
            return;
        }
        if (rolloutState.charAt(1) == '0') {
            filterChain.doFilter(request, response);
            return;
        }
        if (!productAuthorizationV4Enabled) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Calendar product authorization v4 is not ready for enforcement.");
            return;
        }

        CalendarProductSurfaceContract.Binding binding = contract.resolveOwner(
                request.getMethod(), request.getRequestURI()).orElse(null);
        String routeContractKey = exactHeader(request, ROUTE_CONTRACT_HEADER);
        if (binding == null || !binding.routeContractKey().equals(routeContractKey)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact Calendar route authority is required.");
            return;
        }
        CalendarProductSurfaceAccessPolicy.Decision decision = accessPolicy.authorize(
                new CalendarProductSurfaceAccessPolicy.Evidence(
                        positiveLong(request.getHeader("X-DWP-Tenant-ID")),
                        positiveLong(request.getHeader("X-DWP-User-ID")),
                        values(request.getHeader("X-DWP-Roles")),
                        request.getHeader("X-DWP-Support-Session-ID") != null,
                        exactHeader(request, ACTIVE_ACCESS_MODE_HEADER),
                        exactHeader(request, CONTEXT_HEADER),
                        exactHeader(request, SCOPE_HEADER),
                        upperValues(request.getHeader("X-DWP-Permissions")),
                        binding));
        if (decision.status() == CalendarProductSurfaceAccessPolicy.Status.UNAVAILABLE) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Calendar owner authority is unavailable.");
            return;
        }
        if (decision.status() == CalendarProductSurfaceAccessPolicy.Status.DENIED) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The selected Calendar owner scope is not authorized.");
            return;
        }

        String revision = exactHeader(request, CURRENT_REVISION_HEADER);
        java.time.OffsetDateTime revalidateAt = instant(
                exactHeader(request, CURRENT_REVALIDATE_AT_HEADER));
        if (revision == null || !revision.matches("psr-[a-f0-9]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(java.time.OffsetDateTime.now())) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current Calendar authority is missing or expired.");
            return;
        }
        if (binding.routeKind() == CalendarProductSurfaceContract.RouteKind.ACTION
                && !revision.equals(exactHeader(request, EXPECTED_REVISION_HEADER))) {
            writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                    "Calendar authority changed after the client decision.");
            return;
        }

        response.setHeader(RESPONSE_REVISION_HEADER, revision);
        request.setAttribute(
                CalendarProductSurfaceAccessPolicy.class.getName() + ".decision", decision);
        filterChain.doFilter(request, response);
    }

    private boolean validRolloutEvidence(String state, String revision, String cohort) {
        return state != null && ROLLOUT_STATES.contains(state)
                && revision != null && revision.matches("rollout-[a-f0-9]{64}")
                && cohort != null && ROLLOUT_COHORTS.contains(cohort);
    }

    private Set<String> values(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(",", -1))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<String> upperValues(String header) {
        return values(header).stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> headers = request.getHeaders(name);
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

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1;
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private java.time.OffsetDateTime instant(String value) {
        try {
            return value == null ? null : java.time.OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            return null;
        }
    }

    private void writeError(
            HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
