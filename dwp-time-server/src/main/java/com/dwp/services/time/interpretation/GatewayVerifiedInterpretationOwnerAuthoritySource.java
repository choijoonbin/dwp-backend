package com.dwp.services.time.interpretation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import com.dwp.services.time.interpretation.InterpretationPopulationProjectionResolver.Member;
import com.dwp.services.time.interpretation.InterpretationPopulationProjectionResolver.PopulationProjection;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Projects a sealed Gateway v37 route decision into owner-local interpretation predicates. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.interpretation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class GatewayVerifiedInterpretationOwnerAuthoritySource
        implements InterpretationOwnerAuthoritySource {

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
    static final String SUBJECT_HEADER = "X-DWP-Subject-Principal-Public-ID";
    static final String FIELD_POLICY_REVISION_HEADER = "X-DWP-Field-Policy-Revision";
    static final String AUTHORIZATION_REVISION_HEADER = "X-DWP-Authorization-Revision";
    static final String POPULATION_KIND_HEADER = "X-DWP-Population-Kind";
    static final String PURPOSE_HEADER = "X-Purpose-Code";
    static final String MASKED_FIELD_GROUPS_HEADER = "X-DWP-Masked-Field-Groups";
    static final String OMITTED_FIELD_GROUPS_HEADER = "X-DWP-Omitted-Field-Groups";

    private static final Set<String> APP_ENTITLEMENTS =
            Set.of("APP.HCM:VIEW", "APP.HRIS:VIEW", "APP.HCM:MANAGE", "APP.HRIS:MANAGE");
    private static final Set<String> ENFORCED_ROLLOUT_STATES = Set.of("110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> ACCESS_MODES = Set.of("NORMAL", "ELEVATED");
    private static final Set<String> IAQ_FIELD_GROUPS = Set.of(
            "INTERPRETATION_RESULT", "RULE_VERSION", "TRACE_SUMMARY");

    private final String serviceToken;
    private final boolean productAuthorizationEnabled;
    private final Clock clock;
    private final InterpretationPopulationProjectionResolver projectionResolver;

    @Autowired
    GatewayVerifiedInterpretationOwnerAuthoritySource(
            @Value("${dwp.time.service-token:}") String serviceToken,
            @Value("${dwp.time.product-authorization-enabled:true}")
            boolean productAuthorizationEnabled,
            InterpretationPopulationProjectionResolver projectionResolver) {
        this(serviceToken, productAuthorizationEnabled, Clock.systemUTC(), projectionResolver);
    }

    GatewayVerifiedInterpretationOwnerAuthoritySource(
            String serviceToken,
            boolean productAuthorizationEnabled,
            Clock clock,
            InterpretationPopulationProjectionResolver projectionResolver) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.productAuthorizationEnabled = productAuthorizationEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projectionResolver = Objects.requireNonNull(projectionResolver, "projectionResolver");
    }

    @Override
    public Optional<VerifiedInterpretationRequest> verify(
            HttpServletRequest request, InterpretationOwnerRoute route) {
        if (request == null || route == null || !productAuthorizationEnabled
                || serviceToken.isBlank()) {
            throw unavailable("TIM interpretation authority is not configured.");
        }
        if (!constantTimeEquals(serviceToken, single(request, SERVICE_TOKEN_HEADER, 500))) {
            throw unavailable("Verified TIM Gateway identity is unavailable.");
        }

        Instant now = clock.instant();
        Long tenantId = positiveLong(single(request, TENANT_HEADER, 500));
        Long actorId = positiveLong(single(request, USER_HEADER, 500));
        String routeKey = single(request, ROUTE_HEADER, 500);
        String contextKey = single(request, CONTEXT_HEADER, 500);
        String gatewayScopeKey = single(request, SCOPE_HEADER, 500);
        String accessMode = single(request, ACCESS_MODE_HEADER, 500);
        String decisionRevision = single(request, CURRENT_REVISION_HEADER, 500);
        Instant revalidateAt = instant(single(request, CURRENT_REVALIDATE_AT_HEADER, 500));
        String rolloutState = single(request, ROLLOUT_STATE_HEADER, 500);
        String rolloutRevision = single(request, ROLLOUT_REVISION_HEADER, 500);
        String rolloutCohort = single(request, ROLLOUT_COHORT_HEADER, 500);
        UUID correlationId = uuid(single(request, CORRELATION_HEADER, 500));
        UUID subjectId = uuid(single(request, SUBJECT_HEADER, 500));
        Long fieldPolicyRevision = positiveLong(
                single(request, FIELD_POLICY_REVISION_HEADER, 500));
        Long authorizationRevision = positiveLong(
                single(request, AUTHORIZATION_REVISION_HEADER, 500));
        PopulationKind populationKind = populationKind(
                single(request, POPULATION_KIND_HEADER, 100));
        String purpose = single(request, PURPOSE_HEADER, 100);
        if (tenantId == null || actorId == null
                || !route.authorityContractKey().equals(routeKey)
                || contextKey == null || !contextKey.matches("psc-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(gatewayScopeKey)
                || !ACCESS_MODES.contains(accessMode)
                || decisionRevision == null
                || !decisionRevision.matches("psr-[0-9a-f]{64}")
                || revalidateAt == null || !revalidateAt.isAfter(now)
                || !ENFORCED_ROLLOUT_STATES.contains(rolloutState)
                || rolloutRevision == null
                || !rolloutRevision.matches("rollout-[0-9a-f]{64}")
                || !ROLLOUT_COHORTS.contains(rolloutCohort)
                || correlationId == null || subjectId == null
                || fieldPolicyRevision == null || authorizationRevision == null
                || populationKind == null || !route.requiredPurpose().equals(purpose)) {
            throw unavailable(
                    "Current TIM interpretation route authority is absent, stale or mismatched.");
        }
        if (request.getHeader(SUPPORT_SESSION_HEADER) != null) return Optional.empty();

        Set<String> permissions = exactTokens(request, PERMISSIONS_HEADER, 100, true);
        if (permissions == null
                || permissions.stream().noneMatch(APP_ENTITLEMENTS::contains)
                || !route.accepts(permissions)) {
            return Optional.empty();
        }
        if (route.command()
                && !decisionRevision.equals(single(request, EXPECTED_REVISION_HEADER, 500))) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "Current TIM interpretation command decision is mismatched.");
        }
        if (route == InterpretationOwnerRoute.RUN
                && permissions.contains(InterpretationService.SELF_RUN_PERMISSION)
                && !permissions.contains(InterpretationService.RUN_PERMISSION)
                && populationKind != PopulationKind.SELF) {
            return Optional.empty();
        }

        PopulationProjection projection = projectionResolver.resolve(
                        tenantId, actorId, gatewayScopeKey, now)
                .orElseThrow(() -> unavailable(
                        "Current TIM interpretation population projection is unavailable."));
        if (projection.tenantId() != tenantId
                || projection.actorId() != actorId
                || !projection.gatewayScopeKey().equals(gatewayScopeKey)
                || !projection.validUntil().isAfter(now)
                || (populationKind == PopulationKind.SELF
                    && projection.members().size() != 1)) {
            throw unavailable(
                    "Current TIM interpretation population projection is stale or mismatched.");
        }

        Set<String> allowedGroups = route == InterpretationOwnerRoute.WORKBENCH
                ? IAQ_FIELD_GROUPS : Set.of();
        Set<String> masked = optionalTokens(
                request, MASKED_FIELD_GROUPS_HEADER, allowedGroups.size());
        Set<String> omitted = optionalTokens(
                request, OMITTED_FIELD_GROUPS_HEADER, allowedGroups.size());
        if (!allowedGroups.containsAll(masked)
                || !allowedGroups.containsAll(omitted)
                || !Collections.disjoint(masked, omitted)
                || (!allowedGroups.isEmpty() && omitted.containsAll(allowedGroups))) {
            throw unavailable("Current TIM interpretation field projection is invalid.");
        }

        String memberEvidence = projection.members().stream()
                .map(member -> member.workerPublicId() + ":"
                        + member.peopleAssignmentPublicId() + ":"
                        + member.peopleAssignmentRevision() + ":"
                        + member.membershipRevision() + ":"
                        + member.membershipDigest())
                .collect(Collectors.joining("|"));
        String populationScopeDigest = InterpretationDigests.sha256(
                "InterpretationPopulationScope.v1",
                tenantId,
                populationKind,
                projection.populationPublicId(),
                projection.populationRevision(),
                projection.grantRevision(),
                projection.populationDigest(),
                projection.grantDigest(),
                memberEvidence);
        Set<UUID> workers = projection.members().stream()
                .map(Member::workerPublicId)
                .collect(Collectors.toUnmodifiableSet());
        EnumSet<InterpretationAction> actions = EnumSet.of(route.action());
        if (route == InterpretationOwnerRoute.WORKBENCH
                && (permissions.contains(InterpretationService.RUN_PERMISSION)
                    || permissions.contains(InterpretationService.SELF_RUN_PERMISSION)
                    && populationKind == PopulationKind.SELF)) {
            actions.add(InterpretationAction.RUN);
        }
        Instant effectiveRevalidateAt = revalidateAt.isBefore(projection.validUntil())
                ? revalidateAt : projection.validUntil();
        return Optional.of(new VerifiedInterpretationRequest(
                tenantId,
                actorId,
                subjectId,
                permissions,
                actions,
                populationKind,
                projection.populationPublicId(),
                projection.populationRevision(),
                projection.grantRevision(),
                populationScopeDigest,
                fieldPolicyRevision,
                route.requiredPurpose(),
                authorizationRevision,
                decisionRevision,
                effectiveRevalidateAt,
                correlationId,
                workers,
                masked,
                omitted));
    }

    private Set<String> optionalTokens(
            HttpServletRequest request, String name, int maximumItems) {
        if (request.getHeader(name) == null) return Set.of();
        Set<String> values = exactTokens(request, name, maximumItems, false);
        if (values == null) {
            throw unavailable("Current TIM interpretation field predicate is malformed.");
        }
        return values;
    }

    private Set<String> exactTokens(
            HttpServletRequest request, String name, int maximumItems, boolean required) {
        List<String> headers = Collections.list(request.getHeaders(name));
        if (headers.isEmpty() && !required) return null;
        if (headers.size() != 1) return null;
        String value = headers.getFirst();
        if (value == null || value.isBlank() || value.length() > 8_000
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip()) || token.length() > 500
                    || !result.add(token) || result.size() > maximumItems) {
                return null;
            }
        }
        return Set.copyOf(result);
    }

    private String single(HttpServletRequest request, String name, int maximum) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > maximum
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
            UUID parsed = value == null ? null : UUID.fromString(value);
            return parsed == null || parsed.equals(new UUID(0, 0)) ? null : parsed;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private PopulationKind populationKind(String value) {
        try {
            return value == null ? null : PopulationKind.valueOf(value);
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
