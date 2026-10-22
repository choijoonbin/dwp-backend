package com.dwp.services.people.hris.workbench.hrm012;

import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Exact seven-layer owner authorization adapter for the IAQ-012 DATA route. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm012QueryAuthority {
    public static final String ROUTE =
            "route.hcm.operations.people-data-quality.data";
    public static final String PROFILE = "people-data-quality-reader";
    public static final String BINDING = ROUTE + ".binding.01";
    public static final String PROJECTION =
            ROUTE + ".people-data-quality-reader.projection.v1";
    public static final String RESPONSE = "Hrm012DataQualityProjectionV1";
    public static final String CAPABILITY = "hcm.operations.people.view";
    public static final String PERMISSION = "DATA.HRIS_WORKFORCE:VIEW";
    public static final String PURPOSE = "HR_OPERATIONS";
    public static final String FIELD_POLICY = "HRM-AUTH-007";
    public static final String BROWSER_PATH =
            "/api/people/v1/hris/workbench/operations/data/quality";
    private static final Set<String> FIELD_GROUPS = Set.of(
            "INGESTION_STATUS", "QUALITY_FINDING", "COMMAND_RECEIPT");
    private static final String ORIGINATING_ROUTE =
            "route.hcm.operations.workforce-ingestion.action";
    private static final String ORIGINATING_ACTION = "INITIATE_WORKFORCE_INGESTION";
    private static final String ORIGINATING_CAPABILITY =
            "hcm.integration.workforce-ingestion.execute";

    private final Supplier<PeopleRequestContext.Actor> actors;
    private final Supplier<HcmPepContext.Evidence> evidence;
    private final Supplier<Hrm012FieldDecisionPort> fieldDecisions;
    private final HcmPopulationScopeService populations;
    private final Clock clock;

    @Autowired
    public Hrm012QueryAuthority(
            HcmPopulationScopeService populations,
            ObjectProvider<Hrm012FieldDecisionPort> fieldDecisions) {
        this(PeopleRequestContext::require, HcmPepContext::require,
                fieldDecisions::getIfUnique, populations, Clock.systemUTC());
    }

    Hrm012QueryAuthority(
            Supplier<PeopleRequestContext.Actor> actors,
            Supplier<HcmPepContext.Evidence> evidence,
            Supplier<Hrm012FieldDecisionPort> fieldDecisions,
            HcmPopulationScopeService populations,
            Clock clock) {
        this.actors = Objects.requireNonNull(actors);
        this.evidence = Objects.requireNonNull(evidence);
        this.fieldDecisions = Objects.requireNonNull(fieldDecisions);
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
            throw Hrm012Problem.denied("Trusted IAQ-012 authority is unavailable.");
        }
        if (actor == null || actor.tenantId() == null || actor.tenantId() < 1
                || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null || actor.permissions() == null
                || !actor.permissions().contains(PERMISSION)
                || actor.roles() == null
                || !actor.roles().contains("HRIS_HR_BUSINESS_PARTNER")
                || !actor.roles().contains("HRIS_DUTY_CORE_VIEWER")) {
            throw Hrm012Problem.denied(
                    "The IAQ-012 reader profile and duty are unavailable.");
        }
        if (pep == null || !PURPOSE.equals(pep.purpose())
                || !FIELD_POLICY.equals(pep.fieldPolicyKey())
                || pep.revalidateAt() == null
                || !pep.revalidateAt().toInstant().isAfter(clock.instant())
                || !revision(pep.decisionRevision()) || !revision(pep.scopeKey())) {
            throw Hrm012Problem.denied("Current IAQ-012 evidence is unavailable.");
        }
        HcmV3PepRegistry.RouteAuthority route = pep.authority();
        if (route == null || !ROUTE.equals(route.routeContractKey())
                || !"DATA".equals(route.routeKind())
                || !PROFILE.equals(route.profileKey()) || !route.readOnly()
                || !"GET".equals(route.method())
                || !BROWSER_PATH.equals(route.publicPath())
                || !CAPABILITY.equals(route.capabilityContractKey())
                || !BINDING.equals(route.bindingKey())
                || !PROJECTION.equals(route.projectionPolicyKey())
                || !RESPONSE.equals(route.responseSchemaKey())
                || !Set.of("predicate.hcm-workforce-ingestion-scope.v1")
                .equals(route.predicatePolicyKeys())
                || !Set.of("TARGET_POPULATION").equals(route.targetBindingKinds())) {
            throw Hrm012Problem.denied("The exact IAQ-012 DATA route is required.");
        }
        HcmPopulationScopeService.ResolvedPopulation population;
        try {
            population = populations.requireOperations("READ");
            populations.requireTrustedScope(
                    population, "hcm.operations", "TARGET_POPULATION",
                    "LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
        } catch (RuntimeException exception) {
            throw Hrm012Problem.denied(
                    "The IAQ-012 target population is unavailable.");
        }
        // Non-tenant populations are resolved to fully-covered legal entities by
        // the SQL owner. An empty boundary cannot produce such a closure.
        if (!population.scope().tenantWide()
                && population.scope().organizationIds().isEmpty()
                && blank(population.scope().managerAssignmentKey())) {
            throw Hrm012Problem.denied(
                    "IAQ-012 target population has no resolvable boundary.");
        }
        if (!revision(population.targetPopulationRevision())) {
            throw Hrm012Problem.denied(
                    "IAQ-012 target population revision is invalid.");
        }
        Hrm012FieldDecisionPort.DecisionBinding fieldBinding =
                new Hrm012FieldDecisionPort.DecisionBinding(
                        actor.tenantId(), actor.userId(), actor.personPublicId(),
                        ROUTE, CAPABILITY, PURPOSE, pep.scopeKey(),
                        population.targetPopulationRevision(), pep.decisionRevision(),
                        pep.fieldPolicyKey(), ORIGINATING_ROUTE, ORIGINATING_ACTION,
                        ORIGINATING_CAPABILITY, FIELD_GROUPS);
        Hrm012FieldDecisionPort.DecisionResult resolved;
        try {
            Hrm012FieldDecisionPort port = fieldDecisions.get();
            if (port == null) throw new IllegalStateException("missing field decision port");
            resolved = port.resolve(fieldBinding);
        } catch (RuntimeException exception) {
            throw Hrm012Problem.denied(
                    "Trusted IAQ-012 field decisions are unavailable.");
        }
        if (resolved == null || !fieldBinding.equals(resolved.binding())
                || resolved.currentDecisions() == null
                || !resolved.currentDecisions().keySet().equals(FIELD_GROUPS)
                || resolved.currentDecisions().values().stream().anyMatch(Objects::isNull)
                || resolved.originatingCommandReceiptDecision() == null
                || !revision(resolved.fieldDecisionRevision())
                || resolved.evaluatedAt() == null || resolved.revalidateAt() == null
                || resolved.evaluatedAt().isAfter(clock.instant())
                || !resolved.revalidateAt().isAfter(clock.instant())) {
            throw Hrm012Problem.denied(
                    "Trusted IAQ-012 field decisions are incomplete or stale.");
        }
        Map<String, FieldDecision> fields = new LinkedHashMap<>();
        fields.put("INGESTION_STATUS", map(
                resolved.currentDecisions().get("INGESTION_STATUS")));
        fields.put("QUALITY_FINDING", map(
                resolved.currentDecisions().get("QUALITY_FINDING")));
        fields.put("COMMAND_RECEIPT", restrictive(
                map(resolved.currentDecisions().get("COMMAND_RECEIPT")),
                map(resolved.originatingCommandReceiptDecision())));
        if (fields.values().stream().allMatch(value -> value == FieldDecision.OMIT)) {
            throw Hrm012Problem.denied(
                    "IAQ-012 has no authorized serializable field group.");
        }
        return new Decision(actor, population, pep.scopeKey(), pep.decisionRevision(),
                resolved.fieldDecisionRevision(), Map.copyOf(fields));
    }

    private FieldDecision map(Hrm012FieldDecisionPort.FieldDecision value) {
        return switch (value) {
            case VIEW -> FieldDecision.VIEW;
            case MASK -> FieldDecision.MASK;
            case OMIT -> FieldDecision.OMIT;
        };
    }

    private FieldDecision restrictive(FieldDecision current, FieldDecision originating) {
        return current.ordinal() >= originating.ordinal() ? current : originating;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 512;
    }

    private static boolean revision(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
    }

    public enum FieldDecision { VIEW, MASK, OMIT }

    public record Decision(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            String scopeRevision,
            String authorizationRevision,
            String fieldPolicyRevision,
            Map<String, FieldDecision> fields) {
        public Decision {
            if (!revision(scopeRevision) || !revision(authorizationRevision)
                    || !revision(fieldPolicyRevision)
                    || fields == null || !fields.keySet().equals(FIELD_GROUPS)
                    || fields.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "IAQ-012 requires three closed field decisions.");
            }
            fields = Map.copyOf(fields);
        }
    }
}
