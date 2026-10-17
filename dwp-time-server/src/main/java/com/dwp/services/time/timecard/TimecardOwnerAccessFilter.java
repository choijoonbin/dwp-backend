package com.dwp.services.time.timecard;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Default-off fail-closed boundary for the five exact Timecard owner routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 24)
@ConditionalOnProperty(
        name = "dwp.hris.time.timecard.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimecardOwnerAccessFilter extends OncePerRequestFilter {

    public static final String VERIFIED_REQUEST_ATTRIBUTE =
            "com.dwp.services.time.timecard.owner.verified-request";

    private final TimecardOwnerAuthoritySource authoritySource;
    private final ObjectMapper objectMapper;

    @Autowired
    public TimecardOwnerAccessFilter(
            TimecardOwnerAuthoritySource authoritySource,
            ObjectMapper objectMapper) {
        this.authoritySource = Objects.requireNonNull(authoritySource, "authoritySource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/timecards/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TimecardOwnerRoute route = Arrays.stream(TimecardOwnerRoute.values())
                .filter(candidate -> candidate.matches(
                        request.getMethod(), request.getRequestURI()))
                .findFirst().orElse(null);
        if (route == null) {
            deny(request, response,
                    ErrorCode.FORBIDDEN,
                    "Exact TIM Timecard route authority is required.");
            return;
        }

        VerifiedTimecardRequest verified;
        try {
            verified = authoritySource.verify(request, route).orElse(null);
        } catch (BaseException failure) {
            ErrorCode code = failure.getErrorCode() == ErrorCode.DECISION_REVISION_CONFLICT
                    ? ErrorCode.DECISION_REVISION_CONFLICT
                    : ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            deny(request, response, code, code == ErrorCode.DECISION_REVISION_CONFLICT
                    ? "TIM Timecard authority changed after the client decision."
                    : "Trusted TIM Timecard authority is unavailable.");
            return;
        } catch (RuntimeException unavailable) {
            deny(request, response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted TIM Timecard authority is unavailable.");
            return;
        }
        if (verified == null || verified.revalidateAt() == null
                || !verified.revalidateAt().isAfter(Instant.now())
                || !verified.actions().contains(route.action())
                || !route.accepts(verified.populationKind(), verified.permissions())
                || !route.requiredPurpose().equals(verified.purposeCode())) {
            deny(request, response, ErrorCode.FORBIDDEN,
                    "The selected TIM Timecard scope is not authorized.");
            return;
        }

        request.setAttribute(VERIFIED_REQUEST_ATTRIBUTE, verified);
        response.setHeader("X-DWP-Decision-Revision", verified.decisionRevision());
        filterChain.doFilter(request, response);
    }

    private void deny(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode code,
            String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
