package com.dwp.services.messaging.security;

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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Owner-service PEP for the exact Messaging PAGE, DATA and ACTION candidate routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 22)
public final class MessagingProductSurfacePepFilter extends OncePerRequestFilter {

    static final String ROUTE_CONTRACT_HEADER = "X-DWP-Route-Contract-Key";
    static final String CURRENT_DECISION_REVISION_HEADER =
            "X-DWP-Current-Decision-Revision";
    static final String CURRENT_DECISION_REVALIDATE_AT_HEADER =
            "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_DECISION_REVISION_HEADER =
            "X-DWP-Expected-Decision-Revision";
    static final String RESPONSE_DECISION_REVISION_HEADER = "X-DWP-Decision-Revision";
    static final String CURRENT_CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String CURRENT_SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String SUPPORT_SESSION_HEADER = "X-DWP-Support-Session-ID";

    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> WORK_ACCESS_MODES = Set.of("NORMAL", "ELEVATED");

    private final boolean productAuthorizationV4Enabled;
    private final MessagingProductSurfaceContract contract;
    private final MessagingProductSurfaceScopeGuard scopeGuard;
    private final ObjectMapper objectMapper;

    public MessagingProductSurfacePepFilter(
            @Value("${dwp.messaging.product-authorization-v4-enabled:false}")
            boolean productAuthorizationV4Enabled,
            MessagingProductSurfaceContract contract,
            MessagingProductSurfaceScopeGuard scopeGuard,
            ObjectMapper objectMapper) {
        this.productAuthorizationV4Enabled = productAuthorizationV4Enabled;
        this.contract = contract;
        this.scopeGuard = scopeGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !contract.ownsOwner(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String state = exactHeader(request, ROLLOUT_STATE_HEADER);
        if (state == null && !productAuthorizationV4Enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        String rolloutRevision = exactHeader(request, ROLLOUT_REVISION_HEADER);
        String cohort = exactHeader(request, ROLLOUT_COHORT_HEADER);
        if (!validRolloutEvidence(state, rolloutRevision, cohort)) {
            unavailable(response, "Trusted Messaging rollout evidence is missing or invalid.");
            return;
        }
        if (state.charAt(1) != '1') {
            filterChain.doFilter(request, response);
            return;
        }
        if (!productAuthorizationV4Enabled) {
            unavailable(response, "Messaging product authorization v4 is not ready for enforcement.");
            return;
        }

        var resolved = contract.resolveOwner(request.getMethod(), request.getRequestURI())
                .orElse(null);
        String route = exactHeader(request, ROUTE_CONTRACT_HEADER);
        String context = exactHeader(request, CURRENT_CONTEXT_HEADER);
        String scope = exactHeader(request, CURRENT_SCOPE_HEADER);
        String accessMode = exactHeader(request, ACTIVE_ACCESS_MODE_HEADER);
        if (resolved == null || !resolved.routeContractKey().equals(route)
                || !validContext(context) || !trustedText(scope) || accessMode == null) {
            unavailable(response,
                    "Trusted Messaging route, context, scope and access mode are invalid.");
            return;
        }
        if (!WORK_ACCESS_MODES.contains(accessMode)
                || request.getHeader(SUPPORT_SESSION_HEADER) != null) {
            forbidden(response, "Provider support cannot assume normal Messaging authority.");
            return;
        }

        MessagingRequestContext.Subject subject;
        try {
            subject = MessagingRequestContext.get();
        } catch (IllegalStateException exception) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified Messaging identity is unavailable.");
            return;
        }
        if (!subject.has("APP.MESSAGING", resolved.requiredAction())) {
            forbidden(response, "The exact Messaging route authority is missing.");
            return;
        }

        String currentRevision = exactHeader(request, CURRENT_DECISION_REVISION_HEADER);
        OffsetDateTime revalidateAt = validUntil(
                exactHeader(request, CURRENT_DECISION_REVALIDATE_AT_HEADER));
        if (!validCurrentDecision(currentRevision, revalidateAt)) {
            unavailable(response, "Trusted current Messaging authority is missing or expired.");
            return;
        }
        if ("ACTION".equals(resolved.routeKind())) {
            String expectedRevision = exactHeader(request, EXPECTED_DECISION_REVISION_HEADER);
            if (!currentRevision.equals(expectedRevision)) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "Messaging authority changed after the client decision.");
                return;
            }
        }
        boolean scopeAllowed;
        try {
            scopeAllowed = scopeGuard.allows(resolved, subject, scope);
        } catch (RuntimeException exception) {
            unavailable(response, "The Messaging owner scope could not be revalidated.");
            return;
        }
        if (!scopeAllowed) {
            forbidden(response, "The selected Messaging owner scope is no longer valid.");
            return;
        }

        response.setHeader(RESPONSE_DECISION_REVISION_HEADER, currentRevision);
        filterChain.doFilter(request, response);
    }

    private boolean validRolloutEvidence(String state, String revision, String cohort) {
        return state != null && ROLLOUT_STATES.contains(state)
                && revision != null && revision.matches("rollout-[a-f0-9]{64}")
                && cohort != null && ROLLOUT_COHORTS.contains(cohort);
    }

    private boolean validCurrentDecision(String revision, OffsetDateTime revalidateAt) {
        return revision != null && revision.matches("psr-[a-f0-9]{64}")
                && revalidateAt != null && revalidateAt.isAfter(OffsetDateTime.now());
    }

    private OffsetDateTime validUntil(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || !trustedText(value)
                || !value.equals(value.trim())) return null;
        return value;
    }

    private boolean validContext(String value) {
        return value != null && value.matches("psc-[a-f0-9]{64}");
    }

    private boolean trustedText(String value) {
        return value != null && !value.isBlank() && value.length() <= 200
                && value.indexOf(',') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private void forbidden(HttpServletResponse response, String message) throws IOException {
        writeError(response, ErrorCode.FORBIDDEN, message);
    }

    private void unavailable(HttpServletResponse response, String message) throws IOException {
        writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private void writeError(
            HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
