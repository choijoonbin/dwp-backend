package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Exact seven-layer owner authorization for PEP-HRM-011. */
@Component
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class EmploymentContractAuthorization {
    public static final String ROUTE =
            "route.hcm.operations.employment-contract-issue.action";
    public static final String BINDING = ROUTE + ".binding.01";
    public static final String PROFILE = "employment-contract-issuer";
    public static final String CAPABILITY = "hcm.operations.contract.issue";
    public static final String PERMISSION = "ACTION.HRIS_EMPLOYMENT_CONTRACT:ISSUE";
    public static final String PURPOSE = "HR_LIFECYCLE";
    public static final String INTERNAL_PURPOSE = "EMPLOYMENT_CONTRACT";
    public static final String FIELD_POLICY = "HRM-AUTH-015";
    public static final String BROWSER_PATH = "/api/people/v1/hris/employment-contracts";
    public static final String TARGET_TYPE = "WORK_RELATIONSHIP";
    public static final Set<String> FIELDS = Set.of(
            "EMPLOYMENT", "COMPENSATION", "DOCUMENT_CONTENT");
    private static final Set<String> PREDICATES = Set.of(
            "predicate.hcm-workforce-visible-person.v1",
            "predicate.people.object-version.v1");
    private static final Set<String> TARGETS = Set.of("OBJECT", "TARGET_POPULATION");

    private final Supplier<PeopleRequestContext.Actor> actors;
    private final Supplier<HcmPepContext.Evidence> evidence;
    private final HcmPopulationScopeService populations;
    private final HcmPopulationRepository populationRepository;
    private final Clock clock;

    @Autowired
    public EmploymentContractAuthorization(
            HcmPopulationScopeService populations,
            HcmPopulationRepository populationRepository) {
        this(PeopleRequestContext::require, HcmPepContext::require,
                populations, populationRepository, Clock.systemUTC());
    }

    EmploymentContractAuthorization(
            Supplier<PeopleRequestContext.Actor> actors,
            Supplier<HcmPepContext.Evidence> evidence,
            HcmPopulationScopeService populations,
            HcmPopulationRepository populationRepository,
            Clock clock) {
        this.actors = Objects.requireNonNull(actors);
        this.evidence = Objects.requireNonNull(evidence);
        this.populations = populations;
        this.populationRepository = populationRepository;
        this.clock = Objects.requireNonNull(clock);
    }

    public Decision authorize(String optionalPurposeHeader) {
        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence pep;
        try {
            actor = actors.get();
            pep = evidence.get();
        } catch (RuntimeException exception) {
            throw EmploymentContractException.denied(
                    "Trusted HRM-011 identity and PEP evidence are required.");
        }
        if (actor == null || actor.userId() == null || actor.userId() < 1
                || actor.tenantId() == null || actor.tenantId() < 1
                || actor.personPublicId() == null
                || actor.permissions() == null
                || !actor.permissions().contains(PERMISSION)) {
            throw EmploymentContractException.denied(
                    "The employment-contract issuer responsibility is unavailable.");
        }
        if (optionalPurposeHeader != null && !PURPOSE.equals(optionalPurposeHeader)) {
            throw EmploymentContractException.denied(
                    "X-Purpose-Code must be HR_LIFECYCLE when present.");
        }
        if (pep == null || !PURPOSE.equals(pep.purpose())
                || !FIELD_POLICY.equals(pep.fieldPolicyKey())
                || pep.decisionRevision() == null || pep.decisionRevision().isBlank()
                || pep.revalidateAt() == null
                || !pep.revalidateAt().toInstant().isAfter(clock.instant())
                || pep.contextKey() == null || !pep.contextKey().matches("psc-[0-9a-f]{64}")
                || pep.scopeKey() == null || pep.scopeKey().isBlank()
                || !Set.of("110", "111").contains(pep.rolloutState())) {
            throw EmploymentContractException.denied(
                    "Current HRM-011 policy evidence is unavailable.");
        }
        HcmV3PepRegistry.RouteAuthority route = pep.authority();
        HcmV3PepRegistry.StepUpBinding stepUp = route == null
                ? null : route.stepUpBinding();
        if (route == null || !ROUTE.equals(route.routeContractKey())
                || !"ACTION".equals(route.routeKind())
                || !PROFILE.equals(route.profileKey()) || route.readOnly()
                || !PREDICATES.equals(route.predicatePolicyKeys())
                || !TARGETS.equals(route.targetBindingKinds())
                || !BINDING.equals(route.bindingKey())
                || !CAPABILITY.equals(route.capabilityContractKey())
                || !"STEPUP-MGMT-HIGH-V1".equals(route.activationPolicy())
                || !"POST".equals(route.method())
                || !BROWSER_PATH.equals(route.publicPath())
                || stepUp == null || !TARGET_TYPE.equals(stepUp.targetType())
                || stepUp.targetIdPathParameter() != null
                || !java.util.List.of("relationshipPublicId")
                .equals(stepUp.targetIdBodyFields())
                || !"people".equals(stepUp.ownerServiceKey())
                || !"dwp-people-server".equals(stepUp.audience())) {
            throw EmploymentContractException.denied(
                    "The exact high-risk PEP-HRM-011 route is required.");
        }
        return new Decision(actor, pep.decisionRevision(), pep.scopeKey(), null);
    }

    public Decision requireMutationPopulation(
            Decision initial, long targetWorkerId, UUID targetPersonPublicId) {
        if (initial == null || initial.actor() == null || populations == null
                || populationRepository == null || targetWorkerId < 1
                || targetPersonPublicId == null) {
            throw EmploymentContractException.denied(
                    "The employment-contract target population is unavailable.");
        }
        if (initial.actor().personPublicId().equals(targetPersonPublicId)) {
            throw EmploymentContractException.denied(
                    "NO_APPROVE_OWN_CHANGE forbids issuing your own employment contract.");
        }
        HcmPopulationScopeService.ResolvedPopulation population;
        try {
            population = populations.requireOperationsForMutation("READ");
            populations.requireTrustedScope(
                    population, "hcm.operations", "TARGET_POPULATION",
                    "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
        } catch (RuntimeException exception) {
            throw EmploymentContractException.denied(
                    "The locked HRM-011 target population is unavailable.");
        }
        if (!populationRepository.lockWorkerInPopulation(
                initial.actor().tenantId(), population.scope(), targetWorkerId)) {
            throw EmploymentContractException.denied(
                    "The work relationship is outside the authorized population.");
        }
        return new Decision(
                initial.actor(), initial.authorizationRevision(),
                initial.scopeKey(), population);
    }

    public record Decision(
            PeopleRequestContext.Actor actor,
            String authorizationRevision,
            String scopeKey,
            HcmPopulationScopeService.ResolvedPopulation population) {
    }
}
