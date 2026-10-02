package com.dwp.services.time.workregime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.PopulationAccess;

/** Projects the exact Gateway product decision into the TIM owner authority model. */
@Component
@ConditionalOnProperty(name = "dwp.time.work-regime-api.enabled", havingValue = "true")
final class GatewayVerifiedWorkRegimeOwnerAuthoritySource
        implements WorkRegimeOwnerAuthoritySource {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String SUPPORT_SESSION_HEADER = "X-DWP-Support-Session-ID";
    static final String ROUTE_HEADER = "X-DWP-Route-Contract-Key";
    static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String CURRENT_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    static final String CURRENT_REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_REVISION_HEADER = "X-DWP-Expected-Decision-Revision";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String CORRELATION_HEADER = "X-Correlation-ID";

    private static final String APP_ENTITLEMENT = "APP.HCM:VIEW";
    private static final Set<String> ENFORCED_ROLLOUT_STATES = Set.of("110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> ACCESS_MODES = Set.of("NORMAL", "ELEVATED");

    private final String serviceToken;
    private final boolean productAuthorizationEnabled;
    private final Clock clock;
    private final WorkRegimeTargetPopulationResolver targetPopulationResolver;

    @Autowired
    GatewayVerifiedWorkRegimeOwnerAuthoritySource(
            @Value("${dwp.time.service-token:}") String serviceToken,
            @Value("${dwp.time.product-authorization-enabled:true}")
            boolean productAuthorizationEnabled,
            WorkRegimeTargetPopulationResolver targetPopulationResolver) {
        this(
                serviceToken,
                productAuthorizationEnabled,
                Clock.systemUTC(),
                targetPopulationResolver);
    }

    GatewayVerifiedWorkRegimeOwnerAuthoritySource(
            String serviceToken,
            boolean productAuthorizationEnabled,
            Clock clock,
            WorkRegimeTargetPopulationResolver targetPopulationResolver) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.productAuthorizationEnabled = productAuthorizationEnabled;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.targetPopulationResolver = Objects.requireNonNull(
                targetPopulationResolver, "targetPopulationResolver must not be null");
    }

    @Override
    public Optional<VerifiedRequest> verify(
            HttpServletRequest request, WorkRegimeOwnerRoute route) {
        if (request == null || route == null || !productAuthorizationEnabled
                || serviceToken.isBlank()) {
            throw unavailable("TIM product authority is not configured.");
        }
        if (!constantTimeEquals(serviceToken, single(request, SERVICE_TOKEN_HEADER))) {
            throw unavailable("Verified TIM Gateway identity is unavailable.");
        }

        Long tenantId = positiveLong(single(request, TENANT_HEADER));
        Long actorId = positiveLong(single(request, USER_HEADER));
        String routeKey = single(request, ROUTE_HEADER);
        String contextKey = single(request, CONTEXT_HEADER);
        String contextScopeKey = single(request, SCOPE_HEADER);
        String accessMode = single(request, ACCESS_MODE_HEADER);
        String decisionRevision = single(request, CURRENT_REVISION_HEADER);
        Instant revalidateAt = instant(single(request, CURRENT_REVALIDATE_AT_HEADER));
        String rolloutState = single(request, ROLLOUT_STATE_HEADER);
        String rolloutRevision = single(request, ROLLOUT_REVISION_HEADER);
        String rolloutCohort = single(request, ROLLOUT_COHORT_HEADER);
        UUID correlationId = uuid(single(request, CORRELATION_HEADER));
        if (tenantId == null || actorId == null
                || !route.routeContractKey().equals(routeKey)
                || contextKey == null || !contextKey.matches("psc-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(contextScopeKey)
                || !ACCESS_MODES.contains(accessMode)
                || decisionRevision == null
                || !decisionRevision.matches("psr-[0-9a-f]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(clock.instant())
                || !ENFORCED_ROLLOUT_STATES.contains(rolloutState)
                || rolloutRevision == null
                || !rolloutRevision.matches("rollout-[0-9a-f]{64}")
                || !ROLLOUT_COHORTS.contains(rolloutCohort)
                || correlationId == null) {
            throw unavailable("Current TIM route authority is absent, stale or mismatched.");
        }
        if (request.getHeader(SUPPORT_SESSION_HEADER) != null) {
            return Optional.empty();
        }
        PopulationAccess populationAccess = targetPopulationResolver.resolveActorAccess(
                        tenantId, actorId, contextScopeKey, clock.instant())
                .orElseThrow(() -> unavailable(
                        "Current TIM target-population projection is unavailable."));
        if (populationAccess.tenantId() != tenantId
                || populationAccess.actorId() != actorId
                || !populationAccess.gatewayScopeKey().equals(contextScopeKey)
                || populationAccess.validUntil().compareTo(clock.instant()) <= 0) {
            throw unavailable("Current TIM target-population grant is stale or mismatched.");
        }
        Set<String> permissions = exactTokens(request, PERMISSIONS_HEADER);
        if (permissions == null || !permissions.contains(APP_ENTITLEMENT)
                || !permissions.contains(route.capability())) {
            return Optional.empty();
        }
        if (route.command()
                && !decisionRevision.equals(single(request, EXPECTED_REVISION_HEADER))) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "Current TIM command decision is mismatched.");
        }

        boolean elevated = "ELEVATED".equals(accessMode);
        Authority authority = new Authority(
                tenantId,
                actorId,
                Set.of(route.projectedDuty()),
                Set.of(populationAccess.scopePublicRef()),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                decisionRevision,
                route.requiresElevatedAccess() && elevated,
                false);
        Instant effectiveRevalidateAt = revalidateAt.isBefore(populationAccess.validUntil())
                ? revalidateAt : populationAccess.validUntil();
        return Optional.of(new VerifiedRequest(
                authority,
                contextScopeKey,
                decisionRevision,
                effectiveRevalidateAt,
                correlationId));
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

    private String single(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > 500
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? null : value;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
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

    private UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
