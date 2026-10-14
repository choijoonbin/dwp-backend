package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.people.hr.HcmPopulationScopeService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Converts the exact v39 Gateway decision into People-owned plan authority. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.performance.compensation-planning.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HcmCompensationPlanningAuthorityAdapter
        implements CompensationPlanningAuthorityPort {

    static final String PREDICATE =
            "predicate.hcm-compensation-planning-authority.v1";
    private static final String TARGET_POPULATION = "TARGET_POPULATION";
    private static final String OBJECT = "OBJECT";
    private static final Map<String, OperationContract> OPERATIONS = Map.ofEntries(
            operation(CompensationPlanningService.QUERY_CYCLES,
                    "VIEW", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.VIEW_CAPABILITY,
                    "route.hcm.operations.compensation-planning.page",
                    "PAGE", true, AggregateMode.FORBIDDEN, false),
            operation(CompensationPlanningService.QUERY_CYCLE,
                    "VIEW", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.VIEW_CAPABILITY,
                    "route.hcm.operations.compensation-cycle-detail.data",
                    "DATA", true, AggregateMode.REQUIRED, false),
            operation(CompensationPlanningService.QUERY_PROPOSALS,
                    "VIEW", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.VIEW_CAPABILITY,
                    "route.hcm.operations.compensation-proposals.data",
                    "DATA", true, AggregateMode.REQUIRED, false),
            operation(CompensationPlanningService.QUERY_PROPOSAL,
                    "VIEW", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.VIEW_CAPABILITY,
                    "route.hcm.operations.compensation-proposal-detail.data",
                    "DATA", true, AggregateMode.REQUIRED, false),
            operation(CompensationPlanningService.CREATE,
                    "UPDATE", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                    "route.hcm.operations.compensation-cycle-create.action",
                    "ACTION", false, AggregateMode.OPTIONAL, false),
            operation(CompensationPlanningService.REVISE,
                    "UPDATE", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                    "route.hcm.operations.compensation-cycle-revise.action",
                    "ACTION", false, AggregateMode.REQUIRED, false),
            operation(CompensationPlanningService.BUDGET,
                    "UPDATE", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                    "route.hcm.operations.compensation-budget-allocate.action",
                    "ACTION", false, AggregateMode.REQUIRED, false),
            operation(CompensationPlanningService.UPSERT_PROPOSAL,
                    "UPDATE", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                    "route.hcm.operations.compensation-proposal-upsert.action",
                    "ACTION", false, AggregateMode.REQUIRED, false),
            operation(CompensationPlanningService.SUBMIT_PROPOSAL,
                    "UPDATE", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                    "route.hcm.operations.compensation-proposal-submit.action",
                    "ACTION", false, AggregateMode.REQUIRED, false),
            operation(CompensationPlanningService.APPROVE,
                    "APPROVE", CompensationPlanningAuthorization.APPROVAL_RESOURCE,
                    CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                    "route.hcm.operations.compensation-plan-approve.action",
                    "ACTION", false, AggregateMode.REQUIRED, true),
            operation(CompensationPlanningService.PUBLISH,
                    "APPROVE", CompensationPlanningAuthorization.APPROVAL_RESOURCE,
                    CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                    "route.hcm.operations.compensation-snapshot-publish.action",
                    "ACTION", false, AggregateMode.REQUIRED, true),
            operation(CompensationPlanningService.CANCEL,
                    "UPDATE", CompensationPlanningAuthorization.DATA_RESOURCE,
                    CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                    "route.hcm.operations.compensation-cycle-cancel.action",
                    "ACTION", false, AggregateMode.REQUIRED, false));

    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;
    private final Supplier<PeopleRequestContext.Actor> actorSupplier;
    private final Function<Boolean, TargetPopulation> populationResolver;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    HcmCompensationPlanningAuthorityAdapter(HcmPopulationScopeService populations) {
        this(HcmPepContext::current, PeopleRequestContext::require,
                mutation -> resolvePopulation(populations, mutation), Clock.systemUTC());
    }

    HcmCompensationPlanningAuthorityAdapter(
            Supplier<HcmPepContext.Evidence> evidenceSupplier,
            Supplier<PeopleRequestContext.Actor> actorSupplier,
            Function<Boolean, TargetPopulation> populationResolver,
            Clock clock) {
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
        this.actorSupplier = Objects.requireNonNull(actorSupplier);
        this.populationResolver = Objects.requireNonNull(populationResolver);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuthorityEvidence authorize(AuthorityRequest request) {
        if (request == null) throw unavailable("Compensation authority request is unavailable.");
        OperationContract contract = OPERATIONS.get(request.operationId());
        if (contract == null
                || !CompensationPlanningAuthorization.APPLICATION.equals(
                request.applicationEntitlement())
                || !CompensationPlanningAuthorization.PURPOSE.equals(request.purposeCode())
                || !contract.resource().equals(request.resource())
                || !contract.action().equals(request.action())
                || !contract.capability().equals(request.capability())
                || !aggregateMatches(contract.aggregateMode(), request.aggregatePublicId())) {
            throw unavailable("Compensation authority contract is unavailable.");
        }

        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence evidence;
        try {
            actor = actorSupplier.get();
            evidence = evidenceSupplier.get();
        } catch (RuntimeException exception) {
            throw unavailable("Current compensation authority is unavailable.");
        }
        if (actor == null || evidence == null) {
            throw unavailable("Current compensation authority is unavailable.");
        }
        if (actor.tenantId() == null || actor.userId() == null
                || actor.personPublicId() == null
                || actor.tenantId() != request.tenantId()
                || actor.userId() != request.actorId()
                || !actor.personPublicId().equals(request.subjectPrincipalPublicId())) {
            throw forbidden("Compensation subject authority does not match the request.");
        }
        if (!actor.hasPermission(contract.resource(), contract.action())) {
            throw forbidden("The exact compensation permission is not granted.");
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
            throw unavailable("Current compensation authority is stale or incomplete.");
        }

        HcmV3PepRegistry.RouteAuthority route = evidence.authority();
        Set<String> expectedTargets = contract.aggregateMode() == AggregateMode.FORBIDDEN
                || contract.aggregateMode() == AggregateMode.OPTIONAL
                ? Set.of(TARGET_POPULATION) : Set.of(OBJECT, TARGET_POPULATION);
        if (route == null
                || !contract.routeContractKey().equals(route.routeContractKey())
                || !contract.routeKind().equals(route.routeKind())
                || contract.readOnly() != route.readOnly()
                || !contract.capability().equals(route.capabilityContractKey())
                || !Set.of(PREDICATE).equals(route.predicatePolicyKeys())
                || !expectedTargets.equals(route.targetBindingKinds())
                || contract.highRisk() != route.highRisk()
                || (contract.readOnly()
                && (blank(route.projectionPolicyKey()) || blank(route.responseSchemaKey())))) {
            throw forbidden("Exact compensation route authority is required.");
        }

        TargetPopulation population;
        try {
            population = populationResolver.apply(!contract.readOnly());
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Current compensation population is unavailable.");
        }
        if (population == null
                || (!population.tenantWide()
                && population.organizationPublicIds().isEmpty())) {
            throw unavailable("Current compensation population is incomplete.");
        }
        Map<String, FieldDecision> fields = fieldDecisions(contract.readOnly());
        String populationDigest = digest(
                "compensation-planning", request.tenantId(), request.actorId(),
                evidence.contextKey(), evidence.scopeKey(), route.routeContractKey(),
                request.aggregatePublicId(), population.tenantWide(),
                population.organizationPublicIds(), population.workerPublicIds(),
                population.assignmentPublicIds());
        long fieldRevision = positiveRevision(
                "field-policy", route.routeContractKey(), route.profileKey(),
                route.projectionPolicyKey(), route.responseSchemaKey(), fields);
        long authorizationRevision = positiveRevision(
                "authorization", evidence.decisionRevision(), evidence.contextKey(),
                evidence.scopeKey(), evidence.revalidateAt(), populationDigest);
        return new AuthorityEvidence(
                request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                request.applicationEntitlement(), request.resource(), request.action(),
                request.operationId(), request.capability(), request.purposeCode(),
                populationDigest, population, fields,
                fieldRevision, authorizationRevision,
                !contract.highRisk() || route.highRisk());
    }

    private static TargetPopulation resolvePopulation(
            HcmPopulationScopeService populations,
            boolean mutation) {
        HcmPopulationScopeService.ResolvedPopulation resolved = mutation
                ? populations.requireOperationsForMutation("READ")
                : populations.requireOperations("READ");
        populations.requireTrustedScope(
                resolved, "hcm.operations", "TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
        populations.requireField(resolved, "DIRECTORY");
        populations.requireField(resolved, "EMPLOYMENT");
        return new TargetPopulation(
                resolved.scope().tenantWide(), resolved.scope().organizationIds(),
                Set.of(), Set.of());
    }

    private Map<String, FieldDecision> fieldDecisions(boolean readOnly) {
        FieldDecision privileged = readOnly ? FieldDecision.MASK : FieldDecision.VIEW;
        Map<String, FieldDecision> fields = new LinkedHashMap<>();
        fields.put("GUIDELINES", FieldDecision.VIEW);
        fields.put("POPULATION", FieldDecision.VIEW);
        fields.put("ORGANIZATION", FieldDecision.VIEW);
        fields.put("WORKER", FieldDecision.VIEW);
        fields.put("ASSIGNMENT", FieldDecision.VIEW);
        fields.put("BUDGET_AMOUNT", privileged);
        fields.put("PROPOSAL_AMOUNT", privileged);
        fields.put("TYPED_CONTENT", privileged);
        return Map.copyOf(fields);
    }

    private boolean aggregateMatches(AggregateMode mode, UUID aggregate) {
        return switch (mode) {
            case REQUIRED -> aggregate != null;
            case FORBIDDEN -> aggregate == null;
            case OPTIONAL -> true;
        };
    }

    private static Map.Entry<String, OperationContract> operation(
            String operation,
            String action,
            String resource,
            String capability,
            String route,
            String routeKind,
            boolean readOnly,
            AggregateMode aggregateMode,
            boolean highRisk) {
        return Map.entry(operation, new OperationContract(
                action, resource, capability, route, routeKind,
                readOnly, aggregateMode, highRisk));
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

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private enum AggregateMode { REQUIRED, FORBIDDEN, OPTIONAL }

    private record OperationContract(
            String action,
            String resource,
            String capability,
            String routeContractKey,
            String routeKind,
            boolean readOnly,
            AggregateMode aggregateMode,
            boolean highRisk) {
    }
}
