package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotContract;
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
 * Projects the exact, owner-revalidated HCM route decision into the PER command receipt
 * authority contract. The adapter never infers authority from role names. It accepts only the
 * current request's Gateway decision after {@code HcmProductSurfacePepFilter} has matched the
 * generated service binding and {@code HcmScopeSelectionValidator} has revalidated the selected
 * population.
 */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.performance.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HcmPerformanceCycleAuthorityAdapter implements PerformanceCycleAuthorityPort {

    static final String LIST_ROUTE =
            "route.hcm.operations.performance-cycles-list.data";
    static final String DETAIL_ROUTE =
            "route.hcm.operations.performance-cycle-detail.data";
    static final String CREATE_ROUTE =
            "route.hcm.operations.performance-cycle-create.action";
    static final String UPDATE_ROUTE =
            "route.hcm.operations.performance-cycle-update.action";
    static final String VALIDATE_ROUTE =
            "route.hcm.operations.performance-cycle-validate.action";
    static final String PREVIEW_ROUTE =
            WorkforceSnapshotContract.PERFORMANCE_PREVIEW_ROUTE;
    static final String PUBLISH_ROUTE =
            "route.hcm.operations.performance-cycle-publish.action";
    static final String RECEIPT_ROUTE =
            "route.hcm.operations.performance-cycle-command-receipt.data";

    private static final String APPLICATION = "APP.HRIS";
    private static final String RESOURCE = "DATA.HR_TALENT";
    private static final String PURPOSE = "HRIS_PERFORMANCE_CYCLE";
    private static final String READ_CAPABILITY = "hcm.operations.talent.read";
    private static final String CREATE_CAPABILITY = "hcm.operations.talent.create";
    private static final String UPDATE_CAPABILITY =
            WorkforceSnapshotContract.PERFORMANCE_PREVIEW_CAPABILITY;
    static final String APPROVE_CAPABILITY = "hcm.operations.talent.approve";
    private static final String POPULATION_PREDICATE =
            "predicate.hcm-domain-target-population.v1";
    private static final String TARGET_POPULATION = "TARGET_POPULATION";
    private static final Map<String, OperationContract> OPERATIONS = Map.of(
            "performance.cycle.list",
            new OperationContract("VIEW", LIST_ROUTE, "DATA", READ_CAPABILITY),
            "performance.cycle.read",
            new OperationContract("VIEW", DETAIL_ROUTE, "DATA", READ_CAPABILITY),
            "performance.cycle.create",
            new OperationContract("CREATE", CREATE_ROUTE, "ACTION", CREATE_CAPABILITY),
            "performance.cycle.update",
            new OperationContract("UPDATE", UPDATE_ROUTE, "ACTION", UPDATE_CAPABILITY),
            "performance.cycle.validate",
            new OperationContract("UPDATE", VALIDATE_ROUTE, "ACTION", UPDATE_CAPABILITY),
            "performance.cycle.preview",
            new OperationContract("UPDATE", PREVIEW_ROUTE, "ACTION", UPDATE_CAPABILITY),
            "performance.cycle.publish",
            new OperationContract("APPROVE", PUBLISH_ROUTE, "ACTION", APPROVE_CAPABILITY),
            "performance.cycle.receipt.read",
            new OperationContract("VIEW", RECEIPT_ROUTE, "DATA", READ_CAPABILITY));

    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;
    private final Supplier<PeopleRequestContext.Actor> actorSupplier;
    private final Clock clock;

    HcmPerformanceCycleAuthorityAdapter() {
        this(HcmPepContext::current, PeopleRequestContext::require, Clock.systemUTC());
    }

    HcmPerformanceCycleAuthorityAdapter(
            Supplier<HcmPepContext.Evidence> evidenceSupplier,
            Supplier<PeopleRequestContext.Actor> actorSupplier,
            Clock clock) {
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
        this.actorSupplier = Objects.requireNonNull(actorSupplier);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuthorityEvidence authorize(AuthorityRequest request) {
        if (request == null) throw unavailable("PER authority request is unavailable.");
        OperationContract contract = OPERATIONS.get(request.operation());
        if (contract == null
                || !APPLICATION.equals(request.applicationEntitlement())
                || !RESOURCE.equals(request.resource())
                || !PURPOSE.equals(request.purposeCode())) {
            throw unavailable("PER authority contract is not registered.");
        }
        if (!contract.action().equals(request.action())) {
            throw forbidden("PER action does not match its exact route contract.");
        }

        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence evidence;
        try {
            actor = actorSupplier.get();
            evidence = evidenceSupplier.get();
        } catch (RuntimeException exception) {
            throw unavailable("Current PER owner authority is unavailable.");
        }
        if (actor == null || evidence == null) {
            throw unavailable("Current PER owner authority is unavailable.");
        }
        if (actor.tenantId() == null || actor.userId() == null
                || actor.personPublicId() == null
                || actor.tenantId() != request.tenantId()
                || actor.userId() != request.actorId()
                || !actor.personPublicId().equals(request.subjectPrincipalPublicId())) {
            throw forbidden("PER subject authority does not match the verified request.");
        }
        if (!actor.hasPermission(RESOURCE, request.action(), "MANAGE")) {
            throw forbidden("PER data authority is not granted.");
        }
        if (evidence.revalidateAt() == null
                || !evidence.revalidateAt().toInstant().isAfter(clock.instant())
                || evidence.rolloutState() == null
                || !evidence.rolloutState().matches("[01]1[01]")
                || !canonicalContext(evidence.contextKey())
                || !canonicalScope(evidence.scopeKey())
                || !canonicalDecision(evidence.decisionRevision())) {
            throw unavailable("Current PER owner authority is stale or incomplete.");
        }

        HcmV3PepRegistry.RouteAuthority authority = evidence.authority();
        if (authority == null
                || !contract.routeContractKey().equals(authority.routeContractKey())
                || !contract.routeKind().equals(authority.routeKind())
                || !contract.capabilityContractKey().equals(authority.capabilityContractKey())
                || !authority.predicatePolicyKeys().contains(POPULATION_PREDICATE)
                || !authority.targetBindingKinds().contains(TARGET_POPULATION)) {
            throw forbidden("Exact PER route and population authority are required.");
        }

        String populationDigest = digest(
                "population", request.tenantId(), request.actorId(), evidence.contextKey(),
                evidence.scopeKey(), authority.routeContractKey());
        long fieldPolicyRevision = positiveRevision(
                "field-policy", authority.routeContractKey(), authority.profileKey(),
                authority.projectionPolicyKey(), authority.responseSchemaKey(),
                authority.predicatePolicyKeys(), authority.targetBindingKinds());
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

    private boolean canonicalScope(String value) {
        return value != null && value.matches("scope-[0-9a-f]{32}");
    }

    private boolean canonicalDecision(String value) {
        return value != null && value.matches("psr-[0-9a-f]{64}");
    }

    private long positiveRevision(String domain, Object... values) {
        String fingerprint = digest(domain, values);
        return Long.parseLong(fingerprint.substring(0, 15), 16) + 1;
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
            String routeContractKey,
            String routeKind,
            String capabilityContractKey) {
    }
}
