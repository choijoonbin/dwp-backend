package com.dwp.services.time.workregime;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
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

/** Default-off, fail-closed request boundary for the TIM owner API. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
@ConditionalOnProperty(name = "dwp.time.work-regime-api.enabled", havingValue = "true")
public final class WorkRegimeOwnerAccessFilter extends OncePerRequestFilter {

    public static final String VERIFIED_REQUEST_ATTRIBUTE =
            "com.dwp.services.time.workregime.owner.verified-request";
    private static final String OWNER_PREFIX = "/v1/hris/work-plan";

    private final WorkRegimeOwnerAuthoritySource authoritySource;
    private final ObjectMapper objectMapper;

    public WorkRegimeOwnerAccessFilter(
            WorkRegimeOwnerAuthoritySource authoritySource, ObjectMapper objectMapper) {
        this.authoritySource = Objects.requireNonNull(
                authoritySource, "authoritySource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(OWNER_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        WorkRegimeOwnerRoute route = Arrays.stream(WorkRegimeOwnerRoute.values())
                .filter(candidate -> candidate.matches(
                        request.getMethod(), request.getRequestURI()))
                .findFirst()
                .orElse(null);
        if (route == null) {
            deny(response, ErrorCode.FORBIDDEN,
                    "The exact TIM owner route authority is required.");
            return;
        }

        VerifiedRequest verified;
        try {
            verified = authoritySource.verify(request, route).orElse(null);
        } catch (BaseException denied) {
            ErrorCode code = denied.getErrorCode() == ErrorCode.DECISION_REVISION_CONFLICT
                    ? ErrorCode.DECISION_REVISION_CONFLICT
                    : ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            deny(response, code, code == ErrorCode.DECISION_REVISION_CONFLICT
                    ? "TIM authority changed after the client decision."
                    : "Trusted TIM owner authority is unavailable.");
            return;
        } catch (RuntimeException unavailable) {
            deny(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted TIM owner authority is unavailable.");
            return;
        }
        if (!valid(verified, route)) {
            if (verified != null && route.requiresElevatedAccess()
                    && !verified.authority().stepUpSatisfied()) {
                deny(response, ErrorCode.STEP_UP_REQUIRED,
                        "Fresh elevated authority is required for this TIM command.");
                return;
            }
            deny(response, ErrorCode.FORBIDDEN,
                    "The selected TIM owner scope is not authorized.");
            return;
        }

        request.setAttribute(VERIFIED_REQUEST_ATTRIBUTE, verified);
        response.setHeader("X-DWP-Decision-Revision", verified.decisionRevision());
        filterChain.doFilter(request, response);
    }

    private static boolean valid(VerifiedRequest verified, WorkRegimeOwnerRoute route) {
        if (verified == null || verified.revalidateAt().compareTo(Instant.now()) <= 0) return false;
        var authority = verified.authority();
        return authority.decisionId().equals(verified.decisionRevision())
                && WorkRegimeLifecycleGuard.REQUIRED_PURPOSE.equals(authority.purpose())
                && !authority.revoked()
                && !authority.scopeRefs().isEmpty()
                && route.accepts(authority.duties())
                && (route != WorkRegimeOwnerRoute.PUBLISH || authority.stepUpSatisfied());
    }

    private void deny(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
