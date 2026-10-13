package com.dwp.services.time.interpretation;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
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

/** Default-off and fail-closed boundary for the exact interpretation owner routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 23)
@ConditionalOnProperty(
        name = "dwp.hris.time.interpretation.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class InterpretationOwnerAccessFilter extends OncePerRequestFilter {

    public static final String VERIFIED_REQUEST_ATTRIBUTE =
            "com.dwp.services.time.interpretation.owner.verified-request";

    private final InterpretationOwnerAuthoritySource authoritySource;
    private final ObjectMapper objectMapper;

    public InterpretationOwnerAccessFilter(
            InterpretationOwnerAuthoritySource authoritySource, ObjectMapper objectMapper) {
        this.authoritySource = Objects.requireNonNull(authoritySource, "authoritySource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/v1/hris/workbench/time-interpretation")
                && !path.equals("/v1/interpretation-runs")
                && !path.startsWith("/v1/interpretation-runs/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        InterpretationOwnerRoute route = Arrays.stream(InterpretationOwnerRoute.values())
                .filter(candidate -> candidate.matches(
                        request.getMethod(), request.getRequestURI()))
                .findFirst()
                .orElse(null);
        if (route == null) {
            deny(response, ErrorCode.FORBIDDEN,
                    "Exact TIM interpretation route authority is required.");
            return;
        }

        VerifiedInterpretationRequest verified;
        try {
            verified = authoritySource.verify(request, route).orElse(null);
        } catch (BaseException failure) {
            ErrorCode code = failure.getErrorCode() == ErrorCode.DECISION_REVISION_CONFLICT
                    ? ErrorCode.DECISION_REVISION_CONFLICT
                    : ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            deny(response, code, code == ErrorCode.DECISION_REVISION_CONFLICT
                    ? "TIM interpretation authority changed after the client decision."
                    : "Trusted TIM interpretation authority is unavailable.");
            return;
        } catch (RuntimeException unavailable) {
            deny(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted TIM interpretation authority is unavailable.");
            return;
        }
        if (verified == null
                || verified.revalidateAt() == null
                || !verified.revalidateAt().isAfter(Instant.now())
                || !verified.actions().contains(route.action())
                || !route.accepts(verified.permissions())
                || !route.requiredPurpose().equals(verified.purposeCode())) {
            deny(response, ErrorCode.FORBIDDEN,
                    "The selected TIM interpretation scope is not authorized.");
            return;
        }

        request.setAttribute(VERIFIED_REQUEST_ATTRIBUTE, verified);
        response.setHeader("X-DWP-Decision-Revision", verified.decisionRevision());
        filterChain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
