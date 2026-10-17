package com.dwp.services.people.hris.workbench.hrm007;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmEligibilityScopeKeys;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Adapts trusted gateway evidence and live owner-side populations to the three
 * closed HRM-007 query definitions. The adapter verifies the exact generated DATA route,
 * route profile, binding, projection/schema, field policy, purpose and target binding.
 */
@Component
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class Hrm007Authority {

    private static final String PEOPLE_CHANGE_CREATE_PERMISSION =
            "ACTION.HRIS_PEOPLE_CHANGE_REQUEST:CREATE";
    private static final String ASSIGNMENT_EVENT_MANAGE_PERMISSION =
            "ACTION.HRIS_ASSIGNMENT_EVENT:MANAGE";
    private static final String WORKFORCE_CHANGE_APPROVE_PERMISSION =
            "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE:APPROVE";

    private final Supplier<PeopleRequestContext.Actor> actorSupplier;
    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;
    private final Function<Hrm007QueryDefinition, TargetPopulation> populationResolver;
    private final Clock clock;

    @Autowired
    Hrm007Authority(HcmPopulationScopeService populations) {
        this(PeopleRequestContext::require, HcmPepContext::current,
                definition -> resolve(populations, definition), Clock.systemUTC());
    }

    Hrm007Authority(
            Supplier<PeopleRequestContext.Actor> actorSupplier,
            Supplier<HcmPepContext.Evidence> evidenceSupplier,
            Function<Hrm007QueryDefinition, TargetPopulation> populationResolver,
            Clock clock) {
        this.actorSupplier = Objects.requireNonNull(actorSupplier);
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
        this.populationResolver = Objects.requireNonNull(populationResolver);
        this.clock = Objects.requireNonNull(clock);
    }

    Decision authorize(Hrm007QueryDefinition definition) {
        if (definition == null) throw denied("Unknown HRM-007 query definition.");
        PeopleRequestContext.Actor actor;
        HcmPepContext.Evidence evidence;
        try {
            actor = actorSupplier.get();
            evidence = evidenceSupplier.get();
        } catch (RuntimeException exception) {
            throw denied("Trusted authority evidence is unavailable.");
        }
        requireSubject(actor);
        if (!actor.roles().contains(definition.businessRole())
                || !actor.roles().contains(definition.duty())) {
            throw denied("The required HRIS profile and duty are unavailable.");
        }
        requireEvidence(evidence);
        HcmV3PepRegistry.RouteAuthority route = evidence.authority();
        if (route == null
                || !"DATA".equals(route.routeKind())
                || !definition.routeContractKey().equals(route.routeContractKey())
                || !definition.routeProfileKey().equals(route.profileKey())
                || !route.readOnly()
                || !"GET".equals(route.method())
                || !definition.browserPath().equals(route.publicPath())
                || !definition.capability().equals(route.capabilityContractKey())
                || !definition.bindingKey().equals(route.bindingKey())
                || !definition.projectionPolicyKey().equals(route.projectionPolicyKey())
                || !definition.responseSchemaKey().equals(route.responseSchemaKey())
                || !definition.purpose().equals(evidence.purpose())
                || !definition.fieldPolicyKey().equals(evidence.fieldPolicyKey())
                || !definition.predicatePolicyKeys().equals(
                route.predicatePolicyKeys())
                || !definition.targetBindingKinds().equals(route.targetBindingKinds())) {
            throw denied("Exact read-only HRM-007 route authority is required.");
        }

        TargetPopulation population;
        try {
            population = populationResolver.apply(definition);
        } catch (Hrm007Exception exception) {
            throw exception;
        } catch (BaseException exception) {
            throw denied("The HRM-007 population is unavailable.");
        } catch (RuntimeException exception) {
            throw denied("The HRM-007 population is unavailable.");
        }
        requirePopulation(definition, population);

        Map<String, FieldDecision> fieldDecisions = decisions(definition, population);
        if (fieldDecisions.values().stream().allMatch(value -> value == FieldDecision.OMIT)) {
            throw denied("Every HRM-007 field group was omitted.");
        }
        String populationDigest = digest(
                "population", definition.queryId(), definition.purpose(), actor.tenantId(),
                population.populationKind(), population.tenantWide(),
                population.actorWorkerId(), population.managerAssignmentKey(),
                population.organizationIds(), population.revision(), evidence.scopeKey());
        String scopeRevision = "scope:" + digest(
                "scope", definition.queryId(), evidence.scopeKey(),
                population.revision(), populationDigest).substring(0, 32);
        return new Decision(
                actor.tenantId(), actor.userId(), actor.personPublicId(),
                population.populationKind() + ':' + populationDigest,
                scopeRevision, evidence.decisionRevision(), population,
                actionTruth(actor),
                Map.copyOf(new LinkedHashMap<>(fieldDecisions)));
    }

    static ActionTruth actionTruth(PeopleRequestContext.Actor actor) {
        Set<String> normalized = actor == null || actor.permissions() == null
                ? Set.of()
                : actor.permissions().stream()
                .filter(Objects::nonNull)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ActionTruth(
                normalized.contains(PEOPLE_CHANGE_CREATE_PERMISSION),
                normalized.contains(ASSIGNMENT_EVENT_MANAGE_PERMISSION),
                normalized.contains(WORKFORCE_CHANGE_APPROVE_PERMISSION));
    }

    private static TargetPopulation resolve(
            HcmPopulationScopeService populations,
            Hrm007QueryDefinition definition) {
        return switch (definition) {
            case PEOPLE_CHANGES -> {
                HcmPopulationScopeService.ResolvedPopulation resolved =
                        populations.requireTeam();
                String resolverSource = requireTrustedResolverSource(
                        resolved, PeopleRequestContext.require(), HcmPepContext.current(),
                        "hcm.team", "TARGET_POPULATION",
                        "DIRECT_REPORTS", "APPROVED_DELEGATION",
                        "TEAM/ORG_UNIT",
                        "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
                String kind = teamPopulationKind(resolverSource, resolved.scope());
                yield from(kind, resolved);
            }
            case APPOINTMENTS -> {
                HcmPopulationScopeService.ResolvedPopulation resolved =
                        populations.requireOperations("READ");
                String resolverSource = requireTrustedResolverSource(
                        resolved, PeopleRequestContext.require(), HcmPepContext.current(),
                        "hcm.operations", "TARGET_POPULATION",
                        "LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION",
                        "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
                String kind = operationsPopulationKind(resolverSource, resolved.scope());
                yield from(kind, resolved);
            }
            case PEOPLE_EMPLOYMENT -> {
                populations.requireConfigurationScope();
                yield new TargetPopulation(
                        "CONFIG_SCOPE", true, 0L, null, Set.of(),
                        Set.of("POLICY_METADATA", "EMPLOYMENT_RULE", "IMPACT_SUMMARY"),
                        "config-scope");
            }
        };
    }

    private static TargetPopulation from(
            String kind,
            HcmPopulationScopeService.ResolvedPopulation resolved) {
        HcmPopulationRepository.PopulationScope scope = resolved.scope();
        return new TargetPopulation(
                kind, scope.tenantWide(), scope.actorWorkerId(),
                scope.managerAssignmentKey(), scope.organizationIds(),
                scope.fieldGroups(), resolved.targetPopulationRevision());
    }

    /**
     * Recomputes only declared/known scope-source candidates against the opaque
     * trusted key. It neither accepts a caller population label nor invents a
     * future V51 route, binding, projection, or schema key.
     */
    static String requireTrustedResolverSource(
            HcmPopulationScopeService.ResolvedPopulation population,
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence,
            String surfaceKey,
            String kind,
            String... resolverSources) {
        if (actor == null || evidence == null || population == null) {
            throw Hrm007Exception.denied(
                    "Trusted HRM-007 population evidence is unavailable.");
        }
        for (String source : resolverSources) {
            String sourceKey = ProductSurfaceScopeKey.key(
                    actor.tenantId(), actor.userId(), "hcm", surfaceKey, source, kind);
            String candidate = HcmEligibilityScopeKeys.derived(
                    actor.tenantId(), actor.userId(), surfaceKey, sourceKey,
                    population.relationshipRevision(),
                    population.targetPopulationRevision());
            if (candidate.equals(evidence.scopeKey())) return source;
        }
        throw Hrm007Exception.denied(
                "The HRM-007 population changed or is outside this boundary.");
    }

    static String teamPopulationKind(
            String resolverSource,
            HcmPopulationRepository.PopulationScope scope) {
        if ("DIRECT_REPORTS".equals(resolverSource)
                || "APPROVED_DELEGATION".equals(resolverSource)) {
            return resolverSource;
        }
        return !scope.tenantWide()
                && scope.organizationIds().isEmpty()
                && "DIRECT_REPORT".equals(scope.policyFingerprint())
                ? "DIRECT_REPORTS" : "APPROVED_DELEGATION";
    }

    static String operationsPopulationKind(
            String resolverSource,
            HcmPopulationRepository.PopulationScope scope) {
        if (Set.of("LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION")
                .contains(resolverSource)) {
            return resolverSource;
        }
        return scope.tenantWide() ? "LEGAL_ENTITY" : "ORG_UNIT";
    }

    private Map<String, FieldDecision> decisions(
            Hrm007QueryDefinition definition,
            TargetPopulation population) {
        boolean directory = population.fieldGroups().contains("DIRECTORY");
        boolean employment = population.fieldGroups().contains("EMPLOYMENT");
        return switch (definition) {
            case PEOPLE_CHANGES -> ordered(
                    "CHANGE_REQUEST_SUMMARY", directory ? FieldDecision.VIEW : FieldDecision.OMIT,
                    "LIMITED_EMPLOYMENT", employment ? FieldDecision.VIEW : FieldDecision.MASK,
                    "APPROVAL_STATUS", employment ? FieldDecision.VIEW : FieldDecision.OMIT);
            case APPOINTMENTS -> ordered(
                    "APPOINTMENT", directory ? FieldDecision.VIEW : FieldDecision.MASK,
                    "ASSIGNMENT", directory ? FieldDecision.VIEW : FieldDecision.OMIT,
                    "EMPLOYMENT", employment ? FieldDecision.VIEW : FieldDecision.MASK);
            case PEOPLE_EMPLOYMENT -> ordered(
                    "POLICY_METADATA", FieldDecision.VIEW,
                    "EMPLOYMENT_RULE", FieldDecision.VIEW,
                    "IMPACT_SUMMARY", FieldDecision.VIEW);
        };
    }

    private Map<String, FieldDecision> ordered(
            String firstKey, FieldDecision first,
            String secondKey, FieldDecision second,
            String thirdKey, FieldDecision third) {
        Map<String, FieldDecision> values = new LinkedHashMap<>();
        values.put(firstKey, first);
        values.put(secondKey, second);
        values.put(thirdKey, third);
        return values;
    }

    private void requireSubject(PeopleRequestContext.Actor actor) {
        if (actor == null || actor.tenantId() == null || actor.tenantId() < 1
                || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null) {
            throw denied("Trusted HRM-007 subject is unavailable.");
        }
    }

    private void requireEvidence(HcmPepContext.Evidence evidence) {
        if (evidence == null || evidence.revalidateAt() == null
                || !evidence.revalidateAt().toInstant().isAfter(clock.instant())
                || evidence.rolloutState() == null
                || !evidence.rolloutState().matches("[01]1[01]")
                || evidence.contextKey() == null
                || !evidence.contextKey().matches("psc-[0-9a-f]{64}")
                || evidence.decisionRevision() == null
                || !evidence.decisionRevision().matches("psr-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(evidence.scopeKey())) {
            throw denied("Trusted HRM-007 authority is stale or incomplete.");
        }
    }

    private void requirePopulation(
            Hrm007QueryDefinition definition,
            TargetPopulation population) {
        boolean invalidConfigurationScope = definition == Hrm007QueryDefinition.PEOPLE_EMPLOYMENT
                && (population == null
                || !"CONFIG_SCOPE".equals(population.populationKind())
                || !population.tenantWide()
                || population.managerAssignmentKey() != null
                || !population.organizationIds().isEmpty());
        if (population == null
                || !definition.populations().contains(population.populationKind())
                || population.revision() == null || population.revision().isBlank()
                || population.revision().length() > 512
                || invalidConfigurationScope
                || (!population.tenantWide()
                && population.managerAssignmentKey() == null
                && population.organizationIds().isEmpty()
                && definition != Hrm007QueryDefinition.PEOPLE_EMPLOYMENT)) {
            throw denied("The HRM-007 target population is incomplete.");
        }
    }

    private String digest(String domain, Object... values) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(domain.getBytes(StandardCharsets.UTF_8));
            for (Object value : values) {
                hash.update((byte) 0);
                if (value instanceof Set<?> set) {
                    set.stream().map(String::valueOf).sorted().forEach(item ->
                            update(hash, item));
                } else {
                    update(hash, String.valueOf(value));
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void update(MessageDigest hash, String value) {
        hash.update(value.getBytes(StandardCharsets.UTF_8));
        hash.update((byte) '\n');
    }

    private Hrm007Exception denied(String diagnostic) {
        return Hrm007Exception.denied(diagnostic);
    }

    enum FieldDecision {
        VIEW,
        MASK,
        OMIT
    }

    record TargetPopulation(
            String populationKind,
            boolean tenantWide,
            long actorWorkerId,
            String managerAssignmentKey,
            Set<UUID> organizationIds,
            Set<String> fieldGroups,
            String revision) {

        TargetPopulation {
            organizationIds = organizationIds == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(organizationIds));
            fieldGroups = fieldGroups == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(fieldGroups));
        }
    }

    record Decision(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String targetPopulationBinding,
            String scopeDecisionRevision,
            String authorizationDecisionRevision,
            TargetPopulation population,
            ActionTruth actionTruth,
            Map<String, FieldDecision> fieldDecisions) {

        Decision {
            actionTruth = actionTruth == null ? ActionTruth.none() : actionTruth;
        }
    }

    record ActionTruth(
            boolean createOrSubmitPeopleChange,
            boolean manageAssignmentEvent,
            boolean approveWorkforceChange) {

        static ActionTruth none() {
            return new ActionTruth(false, false, false);
        }
    }
}
