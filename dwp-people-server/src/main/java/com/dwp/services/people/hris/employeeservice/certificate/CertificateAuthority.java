package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Exact owner recheck for both entry queries and the one shared action route. */
@Component
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class CertificateAuthority {

    static final String ACTION_ROUTE =
            "route.hcm.operations.certificate-issue.action";
    static final String ACTION_BROWSER_PATH = "/api/people/v1/hris/certificates";
    static final String SELF_PROFILE = "certificate-self-requester";
    static final String OPERATIONS_PROFILE = "certificate-operations-issuer";
    static final String SELF_CAPABILITY = "hcm.personal.certificate.request";
    static final String OPERATIONS_CAPABILITY =
            "hcm.operations.certificate.issue";
    static final String SELF_PURPOSE = "EMPLOYEE_SELF_SERVICE";
    static final String OPERATIONS_PURPOSE = "HR_OPERATIONS";
    static final String DEFERRED_TEAM_PURPOSE = "TEAM_MANAGEMENT";
    static final String TARGET_TYPE = "PERSON";

    private static final String SELF_ROLE = "HRIS_EMPLOYEE";
    private static final String SELF_DUTY =
            "HRIS_DUTY_SELF_CERTIFICATE_REQUESTER";
    private static final String OPERATIONS_ROLE = "HRIS_HR_BUSINESS_PARTNER";
    private static final String OPERATIONS_DUTY =
            "HRIS_DUTY_CERTIFICATE_ISSUER";
    private static final String SELF_PERMISSION =
            "ACTION.HRIS_CERTIFICATE:CREATE";
    private static final String OPERATIONS_PERMISSION =
            "ACTION.HRIS_CERTIFICATE:ISSUE";
    private static final Set<String> SEALED_COMMAND_FIELDS = Set.of(
            "DIRECTORY", "DOCUMENT_CONTENT", "EMPLOYMENT");

    private final HcmPopulationScopeService populations;
    private final Clock clock;
    private final Supplier<PeopleRequestContext.Actor> actorSupplier;
    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;

    CertificateAuthority(HcmPopulationScopeService populations) {
        this(populations, Clock.systemUTC(), PeopleRequestContext::require,
                HcmPepContext::current);
    }

    CertificateAuthority(HcmPopulationScopeService populations, Clock clock) {
        this(populations, clock, PeopleRequestContext::require, HcmPepContext::current);
    }

    CertificateAuthority(
            HcmPopulationScopeService populations,
            Clock clock,
            Supplier<PeopleRequestContext.Actor> actorSupplier,
            Supplier<HcmPepContext.Evidence> evidenceSupplier) {
        this.populations = Objects.requireNonNull(populations);
        this.clock = Objects.requireNonNull(clock);
        this.actorSupplier = Objects.requireNonNull(actorSupplier);
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
    }

    QueryDecision authorizeQuery(CertificateQueryDefinition definition) {
        PeopleRequestContext.Actor actor = actor();
        HcmPepContext.Evidence evidence = evidence();
        HcmV3PepRegistry.RouteAuthority route = evidence.authority();
        if (definition == null || route == null
                || !"DATA".equals(route.routeKind())
                || !definition.routeContractKey().equals(route.routeContractKey())
                || !definition.profileKey().equals(route.profileKey())
                || !route.readOnly()
                || !"GET".equals(route.method())
                || !definition.browserPath().equals(route.publicPath())
                || !definition.capability().equals(route.capabilityContractKey())
                || !definition.predicatePolicyKeys().equals(route.predicatePolicyKeys())
                || !definition.targetBindingKinds().equals(route.targetBindingKinds())
                || !definition.purpose().equals(evidence.purpose())
                || route.stepUpBinding() != null) {
            throw denied("Exact HRM-009 read authority is required.");
        }

        Set<String> fields;
        String populationKind;
        String scopeRevision;
        HcmPopulationScopeService.ResolvedPopulation resolved = null;
        if (definition == CertificateQueryDefinition.SELF_REQUESTS) {
            requireRoles(actor, SELF_ROLE, "HRIS_DUTY_SELF_REQUEST_READER");
            populations.requireSelfScope("hcm.personal");
            fields = Set.copyOf(definition.fieldGroups());
            populationKind = "SELF";
            scopeRevision = "self:" + evidence.scopeKey();
        } else {
            requireRoles(actor, OPERATIONS_ROLE, "HRIS_DUTY_CORE_VIEWER");
            resolved = populations.requireOperations("READ");
            populations.requireTrustedScope(
                    resolved, "hcm.operations", "TARGET_POPULATION",
                    "LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
            fields = resolved.scope().fieldGroups();
            populationKind = resolved.scope().tenantWide()
                    ? "LEGAL_ENTITY" : "ORG_UNIT";
            scopeRevision = resolved.targetPopulationRevision();
        }
        return new QueryDecision(
                actor, evidence.decisionRevision(), populationKind,
                scopeRevision, resolved, fields,
                allowedActions(definition, actor, fields));
    }

    CommandDecision authorizeCommand(
            UUID subjectPersonPublicId,
            String purposeCode,
            String headerPurpose) {
        PeopleRequestContext.Actor actor = actor();
        HcmPepContext.Evidence evidence = evidence();
        if (DEFERRED_TEAM_PURPOSE.equals(purposeCode)
                || DEFERRED_TEAM_PURPOSE.equals(headerPurpose)
                || DEFERRED_TEAM_PURPOSE.equals(evidence.purpose())) {
            throw denied("TEAM_MANAGEMENT is deferred and has no Stage-A profile.");
        }
        boolean self = actor.personPublicId().equals(subjectPersonPublicId);
        String profile = self ? SELF_PROFILE : OPERATIONS_PROFILE;
        String capability = self ? SELF_CAPABILITY : OPERATIONS_CAPABILITY;
        String purpose = self ? SELF_PURPOSE : OPERATIONS_PURPOSE;
        String permission = self ? SELF_PERMISSION : OPERATIONS_PERMISSION;
        String role = self ? SELF_ROLE : OPERATIONS_ROLE;
        String duty = self ? SELF_DUTY : OPERATIONS_DUTY;
        Set<String> predicates = self
                ? Set.of("predicate.self-person.v1", "predicate.people.object-version.v1")
                : Set.of("predicate.hcm-workforce-visible-person.v1",
                        "predicate.people.object-version.v1");
        Set<String> bindings = self
                ? Set.of("SELF", "OBJECT")
                : Set.of("OBJECT", "TARGET_POPULATION");

        requireRoles(actor, role, duty);
        if (!permissions(actor).contains(permission)
                || !purpose.equals(purposeCode)
                || headerPurpose != null && !purpose.equals(headerPurpose)
                || !purpose.equals(evidence.purpose())) {
            throw denied("The certificate purpose or permission is not authorized.");
        }
        HcmV3PepRegistry.RouteAuthority route = evidence.authority();
        HcmV3PepRegistry.StepUpBinding stepUp = route == null
                ? null : route.stepUpBinding();
        if (route == null
                || !ACTION_ROUTE.equals(route.routeContractKey())
                || !"ACTION".equals(route.routeKind())
                || !profile.equals(route.profileKey())
                || route.readOnly()
                || !predicates.equals(route.predicatePolicyKeys())
                || !bindings.equals(route.targetBindingKinds())
                || !capability.equals(route.capabilityContractKey())
                || !"STEPUP-MGMT-HIGH-V1".equals(route.activationPolicy())
                || !"POST".equals(route.method())
                || !ACTION_BROWSER_PATH.equals(route.publicPath())
                || stepUp == null
                || !TARGET_TYPE.equals(stepUp.targetType())
                || stepUp.targetIdPathParameter() != null
                || !List.of("personPublicId").equals(stepUp.targetIdBodyFields())
                || !"people".equals(stepUp.ownerServiceKey())
                || !"dwp-people-server".equals(stepUp.audience())) {
            throw denied("Exact high-risk HRM-009 action authority is required.");
        }

        // A durable idempotent replay still requires the current exact action
        // route/profile/HIGH binding above, but must not depend on a mutable
        // subject or population snapshot. Population resolution is therefore a
        // separate fresh-command step invoked only after replay disposition.
        String scopeRevision = self ? "self:" + evidence.scopeKey() : null;
        Set<String> fields = self ? SEALED_COMMAND_FIELDS : Set.of();
        return new CommandDecision(
                actor, self, profile, capability, purpose, null,
                scopeRevision, fields, evidence.decisionRevision());
    }

    CommandDecision requireMutationScope(CommandDecision authorized) {
        if (authorized == null || authorized.actor() == null
                || authorized.purpose() == null
                || authorized.authorizationRevision() == null) {
            throw denied("Fresh certificate command authority is incomplete.");
        }
        if (authorized.self()) {
            populations.requireSelfScope("hcm.personal");
            return new CommandDecision(
                    authorized.actor(), true, authorized.profile(),
                    authorized.capability(), authorized.purpose(), null,
                    authorized.scopeRevision(), SEALED_COMMAND_FIELDS,
                    authorized.authorizationRevision());
        }

        HcmPopulationScopeService.ResolvedPopulation resolved =
                populations.requireOperationsForMutation("READ");
        populations.requireTrustedScope(
                resolved, "hcm.operations", "TARGET_POPULATION",
                "LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION",
                "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
        Set<String> fields = resolved.scope().fieldGroups();
        if (!fields.equals(SEALED_COMMAND_FIELDS)) {
            throw denied("The certificate field policy is incomplete.");
        }
        return new CommandDecision(
                authorized.actor(), false, authorized.profile(),
                authorized.capability(), authorized.purpose(), resolved,
                resolved.targetPopulationRevision(),
                Set.copyOf(SEALED_COMMAND_FIELDS),
                authorized.authorizationRevision());
    }

    private PeopleRequestContext.Actor actor() {
        PeopleRequestContext.Actor actor;
        try {
            actor = actorSupplier.get();
        } catch (RuntimeException exception) {
            throw denied("Trusted People identity is unavailable.");
        }
        if (actor.userId() == null || actor.userId() < 1
                || actor.tenantId() == null || actor.tenantId() < 1
                || actor.personPublicId() == null
                || actor.roles() == null || actor.permissions() == null) {
            throw denied("Trusted People identity is incomplete.");
        }
        return actor;
    }

    private HcmPepContext.Evidence evidence() {
        HcmPepContext.Evidence evidence = evidenceSupplier.get();
        if (evidence == null || evidence.revalidateAt() == null
                || !evidence.revalidateAt().toInstant().isAfter(clock.instant())
                || evidence.rolloutState() == null
                || !evidence.rolloutState().matches("[01]1[01]")
                || evidence.contextKey() == null
                || !evidence.contextKey().matches("psc-[0-9a-f]{64}")
                || evidence.decisionRevision() == null
                || !evidence.decisionRevision().matches("psr-[0-9a-f]{64}")
                || !HcmEligibilityScopeKey.isCanonical(evidence.scopeKey())) {
            throw denied("Trusted HRM-009 authority is stale or incomplete.");
        }
        return evidence;
    }

    private void requireRoles(
            PeopleRequestContext.Actor actor,
            String role,
            String duty) {
        if (!actor.roles().contains(role) || !actor.roles().contains(duty)) {
            throw denied("The required certificate profile and duty are unavailable.");
        }
    }

    private Set<String> allowedActions(
            CertificateQueryDefinition definition,
            PeopleRequestContext.Actor actor,
            Set<String> fieldGroups) {
        Set<String> permissions = permissions(actor);
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (definition == CertificateQueryDefinition.SELF_REQUESTS
                && permissions.contains("ACTION.HRIS_PEOPLE_CHANGE_REQUEST:CREATE")) {
            actions.add("hris.action.hrm.create-employee-change-request");
            actions.add("hris.action.hrm.submit-employee-change-request");
        }
        String certificatePermission = definition == CertificateQueryDefinition.SELF_REQUESTS
                ? SELF_PERMISSION : OPERATIONS_PERMISSION;
        if (permissions.contains(certificatePermission)
                && (definition == CertificateQueryDefinition.SELF_REQUESTS
                || fieldGroups.equals(SEALED_COMMAND_FIELDS))) {
            actions.add(CertificateDtos.REQUEST_CERTIFICATE_ACTION);
        }
        actions.retainAll(definition.actionKeys());
        return Set.copyOf(actions);
    }

    private Set<String> permissions(PeopleRequestContext.Actor actor) {
        return actor.permissions().stream()
                .filter(Objects::nonNull)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private CertificateException denied(String diagnostic) {
        return CertificateException.denied(diagnostic);
    }

    record QueryDecision(
            PeopleRequestContext.Actor actor,
            String authorizationRevision,
            String populationKind,
            String scopeRevision,
            HcmPopulationScopeService.ResolvedPopulation population,
            Set<String> fieldGroups,
            Set<String> allowedActions) {
        QueryDecision {
            fieldGroups = Set.copyOf(fieldGroups);
            allowedActions = Set.copyOf(allowedActions);
        }
    }

    record CommandDecision(
            PeopleRequestContext.Actor actor,
            boolean self,
            String profile,
            String capability,
            String purpose,
            HcmPopulationScopeService.ResolvedPopulation population,
            String scopeRevision,
            Set<String> fieldGroups,
            String authorizationRevision) {
        CommandDecision {
            fieldGroups = Set.copyOf(fieldGroups);
        }
    }
}
