package com.dwp.services.people.hr.performance.populationfreeze;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Converts the Gateway-verified PEP-PER-009 decision into immutable command
 * evidence. Until Control publishes the exact generated route binding, every
 * request fails closed before repository access.
 */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.performance.population-freeze.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HcmPopulationFreezeAuthorityAdapter implements PopulationFreezeAuthorityPort {

    static final String FREEZE_ROUTE_CONTRACT =
            "route.hcm.operations.performance-population-freeze.action";
    static final String REVIEW_OPERATIONS_ROUTE_CONTRACT =
            "route.hcm.operations.performance-review-operations.page";
    static final String FREEZE_CAPABILITY_CONTRACT = "hcm.operations.performance.update";
    static final String REVIEW_OPERATIONS_CAPABILITY_CONTRACT =
            "hcm.operations.performance.view";
    private static final String APPLICATION = "APP.HRIS";
    private static final String RESOURCE = "DATA.HR_TALENT";
    private static final Map<String, OperationContract> OPERATIONS = Map.of(
            "performance.population.freeze",
            new OperationContract(
                    "UPDATE", "PERFORMANCE_CONFIGURATION", FREEZE_ROUTE_CONTRACT,
                    "ACTION", false, FREEZE_CAPABILITY_CONTRACT),
            "performance.review-operations.query",
            new OperationContract(
                    "VIEW", "PERFORMANCE_OPERATIONS", REVIEW_OPERATIONS_ROUTE_CONTRACT,
                    "PAGE", true, REVIEW_OPERATIONS_CAPABILITY_CONTRACT));
    private static final String POPULATION_PREDICATE =
            "predicate.hcm-domain-target-population.v1";
    private static final String TARGET_POPULATION = "TARGET_POPULATION";

    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;
    private final Supplier<PeopleRequestContext.Actor> actorSupplier;
    private final Clock clock;

    HcmPopulationFreezeAuthorityAdapter() {
        this(HcmPepContext::current, PeopleRequestContext::require, Clock.systemUTC());
    }

    HcmPopulationFreezeAuthorityAdapter(
            Supplier<HcmPepContext.Evidence> evidenceSupplier,
            Supplier<PeopleRequestContext.Actor> actorSupplier,
            Clock clock) {
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
        this.actorSupplier = Objects.requireNonNull(actorSupplier);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuthorityEvidence authorize(AuthorityRequest request) {
        if (request == null) {
            throw unavailable("PER population authority request is unavailable.");
        }
        OperationContract contract = OPERATIONS.get(request.operation());
        if (contract == null
                || !APPLICATION.equals(request.applicationEntitlement())
                || !RESOURCE.equals(request.resource())
                || !contract.action().equals(request.action())
                || !contract.purposeCode().equals(request.purposeCode())) {
            throw unavailable("PER population authority contract is unavailable.");
        }

        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence evidence;
        try {
            actor = actorSupplier.get();
            evidence = evidenceSupplier.get();
        } catch (RuntimeException exception) {
            throw unavailable("Current PER population authority is unavailable.");
        }
        if (actor == null || evidence == null) {
            throw unavailable("Current PER population authority is unavailable.");
        }
        if (actor.tenantId() == null || actor.userId() == null
                || actor.personPublicId() == null
                || actor.tenantId() != request.tenantId()
                || actor.userId() != request.actorId()
                || !actor.personPublicId().equals(request.subjectPrincipalPublicId())) {
            throw forbidden("PER population subject authority does not match the request.");
        }
        if (!actor.hasPermission(RESOURCE, contract.action(), "MANAGE")) {
            throw forbidden("PER population permission is not granted.");
        }
        if (evidence.revalidateAt() == null
                || !evidence.revalidateAt().toInstant().isAfter(clock.instant())
                || evidence.rolloutState() == null
                || !evidence.rolloutState().matches("[01]1[01]")
                || !canonicalContext(evidence.contextKey())
                || !HcmEligibilityScopeKey.isCanonical(evidence.scopeKey())
                || !canonicalDecision(evidence.decisionRevision())) {
            throw unavailable("Current PER population authority is stale or incomplete.");
        }

        HcmV3PepRegistry.RouteAuthority route = evidence.authority();
        if (route == null
                || !contract.routeContractKey().equals(route.routeContractKey())
                || !contract.routeKind().equals(route.routeKind())
                || contract.readOnly() != route.readOnly()
                || !contract.capabilityContractKey().equals(route.capabilityContractKey())
                || !route.predicatePolicyKeys().contains(POPULATION_PREDICATE)
                || !route.targetBindingKinds().contains(TARGET_POPULATION)
                || (contract.readOnly()
                && (route.projectionPolicyKey() == null
                        || route.projectionPolicyKey().isBlank()
                        || route.responseSchemaKey() == null
                        || route.responseSchemaKey().isBlank()))) {
            throw forbidden("Exact PER route and population authority are required.");
        }

        boolean freezeOperation = "performance.population.freeze".equals(request.operation());
        if (freezeOperation && request.cycleId() == null) {
            throw unavailable("The PER population target is unavailable.");
        }
        String targetBinding = freezeOperation
                ? request.cycleId().toString() : "REVIEW_OPERATIONS_COLLECTION";
        String populationDigest = digest(
                "population-freeze", request.tenantId(), request.actorId(),
                evidence.contextKey(), evidence.scopeKey(), route.routeContractKey(),
                targetBinding);
        long fieldPolicyRevision = positiveRevision(
                "field-policy", route.routeContractKey(), route.profileKey(),
                route.projectionPolicyKey(), route.responseSchemaKey(),
                route.predicatePolicyKeys(), route.targetBindingKinds());
        long authorizationRevision = positiveRevision(
                "authorization", evidence.decisionRevision(), evidence.contextKey(),
                evidence.scopeKey(), evidence.revalidateAt());
        return new AuthorityEvidence(
                request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                request.applicationEntitlement(), request.resource(), request.action(),
                request.operation(), request.purposeCode(), populationDigest,
                fieldPolicyRevision, authorizationRevision);
    }

    private boolean canonicalContext(String value) {
        return value != null && value.matches("psc-[0-9a-f]{64}");
    }

    private boolean canonicalDecision(String value) {
        return value != null && value.matches("psr-[0-9a-f]{64}");
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
            String action,
            String purposeCode,
            String routeContractKey,
            String routeKind,
            boolean readOnly,
            String capabilityContractKey) {
    }
}
