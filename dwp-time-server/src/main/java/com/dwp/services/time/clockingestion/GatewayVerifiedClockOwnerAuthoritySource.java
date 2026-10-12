package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockOwnerPolicyProjectionResolver.Member;
import com.dwp.services.time.clockingestion.ClockOwnerPolicyProjectionResolver.PopulationProjection;
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

/** Projects one trusted Gateway v36 PAGE/ACTION decision into TIM owner predicates. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class GatewayVerifiedClockOwnerAuthoritySource implements ClockOwnerAuthoritySource {

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
    static final String STEP_UP_CHALLENGE_HEADER = "X-DWP-Step-Up-Challenge";
    static final String EXPECTED_OBJECT_VERSION_HEADER = "X-DWP-Expected-Object-Version";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String CORRELATION_HEADER = "X-Correlation-ID";
    static final String SUBJECT_HEADER = "X-DWP-Subject-Principal-Public-ID";
    static final String FIELD_POLICY_REVISION_HEADER = "X-DWP-Field-Policy-Revision";
    static final String AUTHORIZATION_REVISION_HEADER = "X-DWP-Authorization-Revision";
    static final String MASKED_FIELD_GROUPS_HEADER = "X-DWP-Masked-Field-Groups";
    static final String OMITTED_FIELD_GROUPS_HEADER = "X-DWP-Omitted-Field-Groups";
    static final String SOURCE_SCOPE_HEADER = "X-DWP-Clock-Source-Public-IDs";
    static final String TARGET_WORKER_HEADER = "X-DWP-Target-Worker-Public-ID";
    static final String TARGET_ASSIGNMENT_HEADER = "X-DWP-Target-Assignment-Public-ID";
    static final String TARGET_ASSIGNMENT_REVISION_HEADER =
            "X-DWP-Target-Assignment-Revision";

    private static final String CORRECT_CAPABILITY = "DATA.HR_TIME:UPDATE";
    private static final Set<String> ENFORCED_ROLLOUT_STATES = Set.of("110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> ACCESS_MODES = Set.of("NORMAL", "ELEVATED");
    private static final Set<String> CLOCK_GROUPS = Set.of(
            "CLOCK_EVENT", "INGESTION_STATUS", "QUALITY_FINDING");
    private static final Set<String> EXCEPTION_GROUPS = Set.of(
            "EXCEPTION", "OWNER_DEEP_LINK", "RECOVERY_METADATA");

    private final String serviceToken;
    private final boolean productAuthorizationEnabled;
    private final Clock clock;
    private final ClockOwnerPolicyProjectionResolver projectionResolver;

    @Autowired
    GatewayVerifiedClockOwnerAuthoritySource(
            @Value("${dwp.time.service-token:}") String serviceToken,
            @Value("${dwp.time.product-authorization-enabled:true}")
            boolean productAuthorizationEnabled,
            ClockOwnerPolicyProjectionResolver projectionResolver) {
        this(serviceToken, productAuthorizationEnabled, Clock.systemUTC(), projectionResolver);
    }

    GatewayVerifiedClockOwnerAuthoritySource(
            String serviceToken,
            boolean productAuthorizationEnabled,
            Clock clock,
            ClockOwnerPolicyProjectionResolver projectionResolver) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.productAuthorizationEnabled = productAuthorizationEnabled;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.projectionResolver = Objects.requireNonNull(
                projectionResolver, "projectionResolver must not be null");
    }

    @Override
    public Optional<VerifiedClockRequest> verify(
            HttpServletRequest request, ClockOwnerRoute route) {
        if (request == null || route == null || !productAuthorizationEnabled
                || serviceToken.isBlank()) {
            throw unavailable("TIM clock authority is not configured.");
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
                || fieldPolicyRevision == null || authorizationRevision == null) {
            throw unavailable("Current TIM clock route authority is absent, stale or mismatched.");
        }
        if (request.getHeader(SUPPORT_SESSION_HEADER) != null) return Optional.empty();

        Set<String> permissions = exactTokens(request, PERMISSIONS_HEADER, 100, true);
        if (permissions == null || !permissions.contains(route.capability())) {
            return Optional.empty();
        }
        if (route.action() != ClockAction.VIEW
                && !decisionRevision.equals(single(request, EXPECTED_REVISION_HEADER, 500))) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "Current TIM clock command decision is mismatched.");
        }
        if (route.stepUpRequired()) {
            if (!"ELEVATED".equals(accessMode)
                    || single(request, STEP_UP_CHALLENGE_HEADER, 16_384) == null
                    || single(request, EXPECTED_OBJECT_VERSION_HEADER, 32) == null) {
                throw new BaseException(
                        ErrorCode.STEP_UP_REQUIRED,
                        "Command-bound TIM step-up evidence is required.");
            }
            if (positiveOrZeroLong(
                    single(request, EXPECTED_OBJECT_VERSION_HEADER, 32)) == null) {
                throw new BaseException(
                        ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                        "The TIM expected object version is invalid.");
            }
        }

        PopulationProjection projection = projectionResolver.resolve(
                        tenantId, actorId, gatewayScopeKey, now)
                .orElseThrow(() -> unavailable(
                        "Current TIM clock population projection is unavailable."));
        if (projection.tenantId() != tenantId
                || projection.actorId() != actorId
                || !projection.gatewayScopeKey().equals(gatewayScopeKey)
                || !projection.validUntil().isAfter(now)) {
            throw unavailable("Current TIM clock population projection is stale or mismatched.");
        }

        Set<String> allowedGroups = route == ClockOwnerRoute.WORKBENCH
                ? CLOCK_GROUPS
                : route == ClockOwnerRoute.TIME_EXCEPTIONS ? EXCEPTION_GROUPS : Set.of();
        Set<String> masked = optionalTokens(
                request, MASKED_FIELD_GROUPS_HEADER, allowedGroups.size());
        Set<String> omitted = optionalTokens(
                request, OMITTED_FIELD_GROUPS_HEADER, allowedGroups.size());
        if (!allowedGroups.containsAll(masked)
                || !allowedGroups.containsAll(omitted)
                || !Collections.disjoint(masked, omitted)
                || (!allowedGroups.isEmpty() && omitted.containsAll(allowedGroups))) {
            throw unavailable("Current TIM clock field projection is invalid.");
        }

        Member target = route.action() == ClockAction.VIEW
                ? projection.members().getFirst()
                : exactTarget(request, projection.members());
        if (target == null) {
            return Optional.empty();
        }
        Set<UUID> sourceScope = route == ClockOwnerRoute.RECORD
                ? uuidTokens(request, SOURCE_SCOPE_HEADER, 100)
                : Set.of();
        if (route == ClockOwnerRoute.RECORD && sourceScope.isEmpty()) {
            throw unavailable("Current TIM clock source predicate is unavailable.");
        }

        Set<UUID> workers = projection.members().stream()
                .map(Member::workerPublicId)
                .collect(Collectors.toUnmodifiableSet());
        String memberEvidence = projection.members().stream()
                .map(member -> member.workerPublicId() + ":"
                        + member.peopleAssignmentPublicId() + ":"
                        + member.peopleAssignmentRevision() + ":"
                        + member.membershipRevision() + ":"
                        + member.membershipDigest())
                .collect(Collectors.joining("|"));
        String populationScopeDigest = ClockDigests.sha256(
                "ClockPopulationScope.v1", tenantId, projection.populationPublicId(),
                projection.populationRevision(), projection.grantRevision(),
                projection.populationDigest(), projection.grantDigest(), memberEvidence);
        String membershipDigest = route.action() == ClockAction.VIEW
                ? ClockDigests.sha256("ClockMembershipSet.v1", memberEvidence)
                : target.membershipDigest();
        long membershipRevision = route.action() == ClockAction.VIEW
                ? projection.members().stream().mapToLong(Member::membershipRevision).max()
                        .orElseThrow()
                : target.membershipRevision();
        String queryScopeRevision = "scope-" + ClockDigests.sha256(
                "ClockQueryScope.v1", populationScopeDigest, fieldPolicyRevision,
                authorizationRevision, decisionRevision);
        EnumSet<ClockAction> actions = EnumSet.of(route.action());
        if (route.action() == ClockAction.VIEW && permissions.contains(CORRECT_CAPABILITY)) {
            actions.add(ClockAction.CORRECT);
        }
        Instant effectiveRevalidateAt = revalidateAt.isBefore(projection.validUntil())
                ? revalidateAt : projection.validUntil();
        return Optional.of(new VerifiedClockRequest(
                tenantId,
                actorId,
                subjectId,
                actions,
                projection.populationPublicId(),
                projection.populationRevision(),
                projection.grantRevision(),
                membershipRevision,
                target.workerPublicId(),
                target.peopleAssignmentPublicId(),
                target.peopleAssignmentRevision(),
                route.authorityContractKey(),
                contextKey,
                gatewayScopeKey,
                populationScopeDigest,
                projection.populationDigest(),
                membershipDigest,
                projection.grantDigest(),
                fieldPolicyRevision,
                route.requiredPurpose(),
                authorizationRevision,
                decisionRevision,
                effectiveRevalidateAt,
                correlationId,
                workers,
                queryScopeRevision,
                masked,
                omitted,
                sourceScope,
                route.stepUpRequired() && "ELEVATED".equals(accessMode)));
    }

    private Member exactTarget(HttpServletRequest request, List<Member> members) {
        UUID workerId = uuid(single(request, TARGET_WORKER_HEADER, 500));
        UUID assignmentId = uuid(single(request, TARGET_ASSIGNMENT_HEADER, 500));
        Long assignmentRevision = positiveOrZeroLong(
                single(request, TARGET_ASSIGNMENT_REVISION_HEADER, 500));
        if (workerId == null || assignmentId == null || assignmentRevision == null) return null;
        List<Member> matches = members.stream()
                .filter(member -> member.workerPublicId().equals(workerId))
                .filter(member -> member.peopleAssignmentPublicId().equals(assignmentId))
                .filter(member -> member.peopleAssignmentRevision() == assignmentRevision)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private Set<String> optionalTokens(
            HttpServletRequest request, String name, int maximumItems) {
        if (request.getHeader(name) == null) return Set.of();
        Set<String> values = exactTokens(request, name, maximumItems, false);
        if (values == null) throw unavailable("Current TIM field predicate is malformed.");
        return values;
    }

    private Set<UUID> uuidTokens(HttpServletRequest request, String name, int maximumItems) {
        Set<String> values = exactTokens(request, name, maximumItems, false);
        if (values == null) return Set.of();
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (String value : values) {
            UUID parsed = uuid(value);
            if (parsed == null || !result.add(parsed)) return Set.of();
        }
        return Set.copyOf(result);
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
        Long parsed = positiveOrZeroLong(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private Long positiveOrZeroLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
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

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
