package com.dwp.services.auth.security;

import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.service.AccessReviewWorkService;
import com.dwp.services.auth.service.GovernedRouteAuthorityPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Auth-owned predicate adapter for non-product Identity governance routes. */
@Component
public class IdentityRoutePredicateEvaluator implements GovernedRouteAuthorityPort {

    static final String DETAIL_ROUTE =
            "route.context.work__work.review-detail.data";
    static final String DECISION_ROUTE =
            "route.context.work__work.review-decision.action";
    private static final String NAVIGATION_CONTEXT = "work.work";
    private static final String BUNDLE_KEY = "product-surfaces";
    private static final Set<ProductSurfaceAuthorityDtos.AccessMode> ACCESS_MODES = Set.of(
            ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
            ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);

    private final AccessReviewWorkService workService;
    private final ProductAuthorizationContractRepository contracts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public IdentityRoutePredicateEvaluator(
            AccessReviewWorkService workService,
            ProductAuthorizationContractRepository contracts,
            JdbcTemplate jdbc) {
        this(workService, contracts, jdbc, Clock.systemUTC());
    }

    IdentityRoutePredicateEvaluator(
            AccessReviewWorkService workService,
            ProductAuthorizationContractRepository contracts,
            JdbcTemplate jdbc,
            Clock clock) {
        this.workService = workService;
        this.contracts = contracts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public GovernedRouteAuthorityDtos.AuthorityResult evaluate(
            GovernedRouteAuthorityDtos.EvaluateRequest request) {
        boolean mutation = DECISION_ROUTE.equals(request.routeContractKey());
        if ((!DETAIL_ROUTE.equals(request.routeContractKey()) && !mutation)
                || !NAVIGATION_CONTEXT.equals(request.navigationContextId())
                || !ACCESS_MODES.contains(request.activeAccessMode())) {
            return denied(request, "ROUTE_DENIED", null);
        }
        UUID workItemRef = canonicalUuid(request.opaqueTargetRef());
        Long expectedVersion = mutation
                ? nonNegativeLong(request.expectedObjectVersion())
                : null;
        if (workItemRef == null || (mutation && expectedVersion == null)) {
            return denied(request, "ROUTE_TARGET_INVALID", null);
        }

        Optional<PolicyRevision> revision = policyRevision(request.tenantId(), request.actorId());
        if (revision.isEmpty()) {
            return GovernedRouteAuthorityDtos.AuthorityResult.unavailable(request);
        }
        AccessReviewWorkService.PredicateEvidence predicate = workService.predicateEvidence(
                request.tenantId(),
                request.actorId(),
                workItemRef,
                expectedVersion,
                mutation);
        if (predicate.state() != AccessReviewWorkService.PredicateState.ALLOWED) {
            String reason = switch (predicate.state()) {
                case STALE_VERSION -> "OBJECT_VERSION_STALE";
                case ALREADY_DECIDED -> "OBJECT_ALREADY_DECIDED";
                default -> "RESOURCE_NOT_AVAILABLE";
            };
            return denied(request, reason, revision.get());
        }

        OffsetDateTime dueAt = OffsetDateTime.ofInstant(predicate.dueAt(), ZoneOffset.UTC);
        OffsetDateTime revalidateAt = OffsetDateTime.ofInstant(
                earlier(predicate.dueAt(), clock.instant().plusSeconds(60)),
                ZoneOffset.UTC);
        PolicyRevision value = revision.get();
        return new GovernedRouteAuthorityDtos.AuthorityResult(
                GovernedRouteAuthorityDtos.Decision.ALLOWED,
                "NAMED_REVIEWER_ASSIGNED",
                value.authRevision(),
                value.policyRevision(),
                "work.review:" + workItemRef,
                request.navigationContextId(),
                ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP,
                request.activeAccessMode(),
                "named-reviewer:" + workItemRef + ":v" + predicate.version(),
                !mutation,
                dueAt,
                null,
                null,
                "identity.named-reviewer-access.v1",
                revalidateAt,
                "named-reviewer-assignment:" + workItemRef + ":v" + predicate.version());
    }

    private Optional<PolicyRevision> policyRevision(Long tenantId, Long actorId) {
        var pointer = contracts.findActivePointer(BUNDLE_KEY);
        var bundle = contracts.findActive(BUNDLE_KEY);
        if (pointer.isEmpty() || bundle.isEmpty()
                || !"ACTIVE".equals(bundle.get().bundleStatus())
                || !pointer.get().bundleId().equals(bundle.get().bundleId())) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT access_revision
                  FROM com_users
                 WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                """, (result, ignored) -> new PolicyRevision(
                        "auth:" + result.getLong("access_revision"),
                        "product-surfaces:v" + bundle.get().version()
                                + ":r" + pointer.get().revision()
                                + ':' + bundle.get().checksum()), tenantId, actorId)
                .stream().findFirst();
    }

    private GovernedRouteAuthorityDtos.AuthorityResult denied(
            GovernedRouteAuthorityDtos.EvaluateRequest request,
            String reason,
            PolicyRevision revision) {
        return new GovernedRouteAuthorityDtos.AuthorityResult(
                GovernedRouteAuthorityDtos.Decision.ROUTE_DENIED,
                reason,
                revision == null ? "auth:unresolved" : revision.authRevision(),
                revision == null ? "policy:unresolved" : revision.policyRevision(),
                null,
                request.navigationContextId(),
                null,
                request.activeAccessMode(),
                null,
                true,
                null,
                null,
                null,
                "identity.named-reviewer-access.v1",
                null,
                null);
    }

    private UUID canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }

    private Long nonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 && Long.toString(parsed).equals(value) ? parsed : null;
        } catch (NumberFormatException | NullPointerException ignored) {
            return null;
        }
    }

    private Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private record PolicyRevision(String authRevision, String policyRevision) {
    }
}
