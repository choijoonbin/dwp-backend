package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Default-off, fail-closed boundary for the exact clock owner routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 24)
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class ClockOwnerAccessFilter extends OncePerRequestFilter {

    public static final String VERIFIED_REQUEST_ATTRIBUTE =
            "com.dwp.services.time.clockingestion.owner.verified-request";

    private final ClockOwnerAuthoritySource authoritySource;
    private final ObjectMapper objectMapper;

    public ClockOwnerAccessFilter(
            ClockOwnerAuthoritySource authoritySource, ObjectMapper objectMapper) {
        this.authoritySource = Objects.requireNonNull(
                authoritySource, "authoritySource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/v1/clock-events")
                && !path.startsWith("/v1/clock-events/")
                && !path.equals("/v1/hris/workbench/clock-ingestion")
                && !path.equals("/v1/hris/workbench/time-exceptions");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        ClockOwnerRoute route = Arrays.stream(ClockOwnerRoute.values())
                .filter(candidate -> candidate.matches(
                        request.getMethod(), request.getRequestURI()))
                .findFirst()
                .orElse(null);
        if (route == null) {
            deny(response, ErrorCode.FORBIDDEN, "Exact TIM clock route authority is required.");
            return;
        }

        VerifiedClockRequest verified;
        try {
            verified = authoritySource.verify(request, route).orElse(null);
        } catch (BaseException failure) {
            ErrorCode code = failure.getErrorCode() == ErrorCode.DECISION_REVISION_CONFLICT
                    ? ErrorCode.DECISION_REVISION_CONFLICT
                    : failure.getErrorCode() == ErrorCode.STEP_UP_REQUIRED
                            ? ErrorCode.STEP_UP_REQUIRED
                            : ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            deny(response, code, "Trusted TIM clock authority is unavailable.");
            return;
        } catch (RuntimeException unavailable) {
            deny(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted TIM clock authority is unavailable.");
            return;
        }
        if (verified == null
                || verified.revalidateAt() == null
                || !verified.revalidateAt().isAfter(Instant.now())
                || !verified.actions().contains(route.action())
                || !route.requiredPurpose().equals(verified.purposeCode())) {
            deny(response, ErrorCode.FORBIDDEN, "The selected clock scope is not authorized.");
            return;
        }
        if (route.stepUpRequired() && !verified.stepUpSatisfied()) {
            deny(response, ErrorCode.STEP_UP_REQUIRED,
                    "Fresh step-up authority is required for a corrective void.");
            return;
        }

        request.setAttribute(VERIFIED_REQUEST_ATTRIBUTE, verified);
        response.setHeader("X-DWP-Decision-Revision", verified.authorizationRevision());
        filterChain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
