package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractAuthorization;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Exact owner-side DATA route, population, and field-decision adapter. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm011QueryAuthority {
    public static final String ROUTE =
            "route.hcm.operations.contracts-compensation-basis.data";
    public static final String PROFILE = "people-operations-reader";
    public static final String BINDING = ROUTE + ".binding.01";
    public static final String PROJECTION = ROUTE + ".people-operations-reader.projection.v1";
    public static final String RESPONSE = "Hrm011ContractsCompensationBasisProjectionV2";
    public static final String PURPOSE = "HR_OPERATIONS";
    public static final String FIELD_POLICY = "HRM-AUTH-007";
    public static final String PERMISSION = "DATA.HRIS_WORKFORCE:VIEW";
    public static final String BROWSER_PATH =
            "/api/people/v1/hris/workbench/operations/contracts/compensation/basis";

    private final Supplier<PeopleRequestContext.Actor> actors;
    private final Supplier<HcmPepContext.Evidence> evidence;
    private final HcmPopulationScopeService populations;
    private final Clock clock;

    @Autowired
    public Hrm011QueryAuthority(HcmPopulationScopeService populations) {
        this(PeopleRequestContext::require, HcmPepContext::require,
                populations, Clock.systemUTC());
    }

    Hrm011QueryAuthority(
            Supplier<PeopleRequestContext.Actor> actors,
            Supplier<HcmPepContext.Evidence> evidence,
            HcmPopulationScopeService populations,
            Clock clock) {
        this.actors = Objects.requireNonNull(actors);
        this.evidence = Objects.requireNonNull(evidence);
        this.populations = Objects.requireNonNull(populations);
        this.clock = Objects.requireNonNull(clock);
    }

    public Decision authorize() {
        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence pep;
        try {
            actor = actors.get();
            pep = evidence.get();
        } catch (RuntimeException exception) {
            throw EmploymentContractException.denied(
                    "Trusted IAQ-008 authority is unavailable.");
        }
        if (actor == null || actor.userId() == null || actor.userId() < 1
                || actor.tenantId() == null || actor.tenantId() < 1
                || actor.personPublicId() == null || actor.permissions() == null
                || !actor.permissions().contains(PERMISSION)) {
            throw EmploymentContractException.denied(
                    "The IAQ-008 reader responsibility is unavailable.");
        }
        if (pep == null || !PURPOSE.equals(pep.purpose())
                || !FIELD_POLICY.equals(pep.fieldPolicyKey())
                || pep.revalidateAt() == null
                || !pep.revalidateAt().toInstant().isAfter(clock.instant())
                || pep.decisionRevision() == null || pep.decisionRevision().isBlank()
                || pep.scopeKey() == null || pep.scopeKey().isBlank()) {
            throw EmploymentContractException.denied(
                    "Current IAQ-008 evidence is unavailable.");
        }
        HcmV3PepRegistry.RouteAuthority route = pep.authority();
        if (route == null || !ROUTE.equals(route.routeContractKey())
                || !"DATA".equals(route.routeKind()) || !PROFILE.equals(route.profileKey())
                || !route.readOnly() || !"GET".equals(route.method())
                || !BROWSER_PATH.equals(route.publicPath())
                || !"hcm.operations.people.view".equals(route.capabilityContractKey())
                || !BINDING.equals(route.bindingKey())
                || !PROJECTION.equals(route.projectionPolicyKey())
                || !RESPONSE.equals(route.responseSchemaKey())
                || !Set.of("predicate.hcm-workforce-visible-person.v1")
                .equals(route.predicatePolicyKeys())
                || !Set.of("TARGET_POPULATION").equals(route.targetBindingKinds())) {
            throw EmploymentContractException.denied(
                    "The exact IAQ-008 DATA route is required.");
        }
        HcmPopulationScopeService.ResolvedPopulation population;
        try {
            population = populations.requireOperations("READ");
            populations.requireTrustedScope(
                    population, "hcm.operations", "TARGET_POPULATION",
                    "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
        } catch (RuntimeException exception) {
            throw EmploymentContractException.denied(
                    "The IAQ-008 target population is unavailable.");
        }
        Map<String, FieldDecision> decisions = new LinkedHashMap<>();
        // HRM-AUTH-007 is a route-specific field policy. The V34 workforce
        // policy contributes only the target population and its field-group
        // namespace must not be used to weaken this sealed projection.
        decisions.put("CONTRACT_METADATA", FieldDecision.VIEW);
        decisions.put("COMPENSATION_BASIS", FieldDecision.VIEW);
        return new Decision(
                actor, population, pep.scopeKey(), pep.decisionRevision(),
                Map.copyOf(decisions), false);
    }

    public enum FieldDecision { VIEW, MASK, OMIT }

    public record Decision(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            String scopeRevision,
            String authorizationRevision,
            Map<String, FieldDecision> fields,
            boolean preliminaryIssueAllowed) {
    }
}
