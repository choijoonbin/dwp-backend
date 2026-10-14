package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Revalidates the exact generated People-v42 route decision at the owner boundary. */
@Component
final class HcmPerformanceReviewAuthorityAdapter
        implements PerformanceReviewAuthorityPort {

    static final String GET_ROUTE = "route.hcm.performance.assigned-review.data";
    static final String PATCH_ROUTE =
            "route.hcm.performance.assigned-review-draft-update.action";
    static final String SUBMIT_ROUTE =
            "route.hcm.performance.assigned-review-submit.action";
    static final String ASSIGNED_CAPABILITY = "hcm.team.performance.review";
    static final String SELF_CAPABILITY = "per.review.self.submit";
    static final String PURPOSE = "PERFORMANCE_REVIEW";
    static final String RESOURCE_PERMISSION = "DATA.HRIS_PERFORMANCE_REVIEW:UPDATE";
    static final String PRODUCT_PERMISSION = "APP.HCM:VIEW";
    private static final String PREDICATE =
            "predicate.hcm-performance-review-assignment.v1";
    private static final String RESPONSE_SCHEMA = "AssignedReviewV1";
    private static final Map<String, OperationContract> OPERATIONS = Map.of(
            PerformanceReviewService.GET_OPERATION,
            new OperationContract(GET_ROUTE, "DATA", "GET", true),
            PerformanceReviewService.SAVE_OPERATION,
            new OperationContract(PATCH_ROUTE, "ACTION", "PATCH", false),
            PerformanceReviewService.SUBMIT_OPERATION,
            new OperationContract(SUBMIT_ROUTE, "ACTION", "POST", false));

    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;
    private final Supplier<PeopleRequestContext.Actor> actorSupplier;
    private final Clock clock;

    HcmPerformanceReviewAuthorityAdapter() {
        this(HcmPepContext::current, PeopleRequestContext::require, Clock.systemUTC());
    }

    HcmPerformanceReviewAuthorityAdapter(
            Supplier<HcmPepContext.Evidence> evidenceSupplier,
            Supplier<PeopleRequestContext.Actor> actorSupplier,
            Clock clock) {
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
        this.actorSupplier = Objects.requireNonNull(actorSupplier);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuthorityEvidence authorize(AuthorityRequest request) {
        if (request == null || request.tenantId() <= 0 || request.actorId() <= 0
                || request.subjectPrincipalPublicId() == null || request.reviewId() == null) {
            throw unavailable("Assigned-review authority request is incomplete.");
        }
        if (!PURPOSE.equals(request.purposeCode())) {
            throw forbidden("Exact assigned-review purpose authority is required.");
        }
        OperationContract contract = OPERATIONS.get(request.operation());
        if (contract == null) {
            throw unavailable("Assigned-review operation is not sealed.");
        }
        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence evidence;
        try {
            actor = actorSupplier.get();
            evidence = evidenceSupplier.get();
        } catch (RuntimeException exception) {
            throw unavailable("Current assigned-review authority is unavailable.");
        }
        if (actor == null || evidence == null || evidence.authority() == null) {
            throw unavailable("Current assigned-review authority is unavailable.");
        }
        if (actor.tenantId() == null || actor.userId() == null
                || actor.tenantId() != request.tenantId()
                || actor.userId() != request.actorId()
                || !request.subjectPrincipalPublicId().equals(actor.personPublicId())) {
            throw forbidden("Assigned-review subject authority does not match.");
        }
        if (!actor.permissions().contains(PRODUCT_PERMISSION)
                || !actor.permissions().contains(RESOURCE_PERMISSION)) {
            throw forbidden("Assigned-review entitlement is not granted.");
        }
        if (evidence.revalidateAt() == null
                || !evidence.revalidateAt().toInstant().isAfter(clock.instant())
                || evidence.rolloutState() == null
                || !evidence.rolloutState().matches("[01]1[01]")
                || evidence.contextKey() == null
                || !evidence.contextKey().matches("psc-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(evidence.scopeKey())
                || evidence.decisionRevision() == null
                || !evidence.decisionRevision().matches("psr-[0-9a-f]{64}")) {
            throw unavailable("Current assigned-review authority is stale or incomplete.");
        }
        if (!contract.readOnly()
                && !evidence.decisionRevision().equals(request.expectedDecisionRevision())) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "Assigned-review authority changed after the client decision.");
        }

        HcmV3PepRegistry.RouteAuthority route = evidence.authority();
        Profile profile = profile(route.profileKey());
        if (!contract.routeKey().equals(route.routeContractKey())
                || !contract.routeKind().equals(route.routeKind())
                || !contract.method().equals(route.method())
                || contract.readOnly() != route.readOnly()
                || !profile.capability().equals(route.capabilityContractKey())
                || !profile.populationKind().equals(request.populationKind())
                || !route.predicatePolicyKeys().contains(PREDICATE)
                || !route.targetBindingKinds().contains("OBJECT")
                || !route.targetBindingKinds().contains(profile.populationKind())) {
            throw forbidden("Exact assigned-review route and assignment authority are required.");
        }
        String projection = route.projectionPolicyKey();
        if (contract.readOnly()) {
            String expectedProjection = GET_ROUTE + '.' + profile.profileKey()
                    + ".projection.v1";
            if (!expectedProjection.equals(projection)
                    || !RESPONSE_SCHEMA.equals(route.responseSchemaKey())) {
                throw forbidden("Exact assigned-review field projection is required.");
            }
        } else if (projection != null || route.responseSchemaKey() != null) {
            throw forbidden("Assigned-review mutation authority cannot carry a read projection.");
        }

        String populationDigest = digest(
                "assigned-review-population", request.tenantId(), request.actorId(),
                request.subjectPrincipalPublicId(), request.reviewId(), profile.profileKey(),
                profile.populationKind(), evidence.contextKey(), evidence.scopeKey(),
                route.routeContractKey(), route.predicatePolicyKeys(),
                route.targetBindingKinds());
        long fieldRevision = positiveRevision(
                "assigned-review-field", route.routeContractKey(), profile.profileKey(),
                projection, route.responseSchemaKey());
        long authorizationRevision = positiveRevision(
                "assigned-review-authorization", evidence.decisionRevision(),
                evidence.contextKey(), evidence.scopeKey(), evidence.revalidateAt());
        return new AuthorityEvidence(
                request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                request.reviewId(), request.operation(), PURPOSE, profile.populationKind(),
                profile.profileKey(), route.routeContractKey(), projection,
                populationDigest, fieldRevision, authorizationRevision);
    }

    private Profile profile(String profileKey) {
        return switch (profileKey == null ? "" : profileKey) {
            case "self-reviewer" ->
                    new Profile("self-reviewer", SELF_CAPABILITY, "SELF");
            case "assigned-reviewer" ->
                    new Profile("assigned-reviewer", ASSIGNED_CAPABILITY, "TARGET_POPULATION");
            default -> throw forbidden("Assigned-review profile is not permitted.");
        };
    }

    private long positiveRevision(String domain, Object... values) {
        return Long.parseLong(digest(domain, values).substring(0, 15), 16) + 1;
    }

    private String digest(String domain, Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            for (Object value : values) {
                digest.update((byte) 0);
                if (value instanceof Set<?> set) {
                    set.stream().map(String::valueOf).sorted()
                            .forEach(item -> update(digest, item));
                } else {
                    update(digest, String.valueOf(value));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private record OperationContract(
            String routeKey,
            String routeKind,
            String method,
            boolean readOnly) {
    }

    private record Profile(
            String profileKey,
            String capability,
            String populationKind) {
    }
}
