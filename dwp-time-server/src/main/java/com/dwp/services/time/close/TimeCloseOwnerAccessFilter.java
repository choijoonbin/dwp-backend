package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Default-off fail-closed boundary for the eight exact v50 Time close routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 23)
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimeCloseOwnerAccessFilter extends OncePerRequestFilter {

    public static final String VERIFIED_REQUEST_ATTRIBUTE =
            "com.dwp.services.time.close.owner.verified-request";

    private final TimeCloseOwnerAuthoritySource authoritySource;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TimeCloseOwnerAccessFilter(
            TimeCloseOwnerAuthoritySource authoritySource,
            ObjectMapper objectMapper) {
        this(authoritySource, objectMapper, Clock.systemUTC());
    }

    TimeCloseOwnerAccessFilter(
            TimeCloseOwnerAuthoritySource authoritySource,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authoritySource = Objects.requireNonNull(authoritySource, "authoritySource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return Arrays.stream(TimeCloseOwnerRoute.values()).noneMatch(route ->
                route.matches(request.getMethod(), request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TimeCloseOwnerRoute route = Arrays.stream(TimeCloseOwnerRoute.values())
                .filter(candidate -> candidate.matches(
                        request.getMethod(), request.getRequestURI()))
                .findFirst().orElse(null);
        if (route == null) {
            filterChain.doFilter(request, response);
            return;
        }
        UUID correlation = canonicalUuid(request.getHeader("X-Correlation-ID"));
        if (correlation == null) correlation = UUID.randomUUID();
        try {
            VerifiedCloseRequest verified = authoritySource.verify(request, route)
                    .orElse(null);
            if (verified == null || verified.action() != route.action()
                    || !route.routeContractKey().equals(verified.routeContractKey())
                    || !route.accepts(verified.permissions(), verified.responsibilities())
                    || !route.purpose().equals(verified.purposeCode())
                    || !verified.revalidateAt().isAfter(clock.instant())
                    || (route.freshCommandStepUp()
                        && !"ELEVATED".equals(verified.accessMode()))) {
                deny(response, 403, "AUTHORIZATION_DENIED", correlation, false);
                return;
            }
            request.setAttribute(VERIFIED_REQUEST_ATTRIBUTE, verified);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Correlation-ID", verified.correlationId().toString());
            response.setHeader("X-DWP-Decision-Revision", verified.decisionRevision());
            filterChain.doFilter(request, response);
        } catch (BaseException failure) {
            if (failure.getErrorCode() == ErrorCode.DECISION_REVISION_CONFLICT) {
                deny(response, 409, "STALE_OR_STATE_CONFLICT", correlation, false);
            } else {
                deny(response, 503, "AUTHORITY_UNAVAILABLE", correlation, true);
            }
        } catch (RuntimeException unavailable) {
            deny(response, 503, "AUTHORITY_UNAVAILABLE", correlation, true);
        }
    }

    private void deny(
            HttpServletResponse response,
            int status,
            String code,
            UUID correlation,
            boolean retryable) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Correlation-ID", correlation.toString());
        if (retryable) response.setHeader("Retry-After", "1");
        LinkedHashMap<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:dwp:hris:time:problem:" + code.toLowerCase());
        problem.put("title", switch (status) {
            case 403 -> "Authorization denied";
            case 409 -> "Stale or state conflict";
            default -> "Authority unavailable";
        });
        problem.put("status", status);
        problem.put("code", code);
        problem.put("correlationId", correlation);
        problem.put("retryable", retryable);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID parsed = value == null ? null : UUID.fromString(value);
            return parsed != null && parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
