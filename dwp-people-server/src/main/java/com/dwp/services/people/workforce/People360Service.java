package com.dwp.services.people.workforce;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.directory.PeopleDirectoryService;
import com.dwp.services.people.directory.PeopleDtos;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded owner API for a single, unambiguous People 360 as-of projection. */
@Service
public class People360Service {

    private final People360Repository repository;
    private final PeopleDirectoryService directory;
    private final HcmPopulationScopeService populationScopes;
    private final HcmPopulationRepository populations;
    private final People360ProjectionPolicyProvider projectionPolicies;
    private final boolean runtimeEnabled;

    public People360Service(
            People360Repository repository,
            PeopleDirectoryService directory,
            HcmPopulationScopeService populationScopes,
            HcmPopulationRepository populations,
            People360ProjectionPolicyProvider projectionPolicies,
            @Value("${dwp.people.people360-runtime-enabled:false}") boolean runtimeEnabled) {
        this.repository = repository;
        this.directory = directory;
        this.populationScopes = populationScopes;
        this.populations = populations;
        this.projectionPolicies = projectionPolicies;
        this.runtimeEnabled = runtimeEnabled;
    }

    @Transactional(readOnly = true)
    public People360Dtos.Page search(
            String query,
            String status,
            String cursor,
            int size,
            LocalDate requestedAsOf) {
        requireRuntimeEnabled();
        LocalDate asOf = requiredAsOf(requestedAsOf);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireOperations("READ");
        requireOperationsEvidence(population);
        Authorization authorization = operationsAuthorization(actor, population);
        PeopleDtos.CursorPage<PeopleDtos.PersonSummary> selected =
                directory.searchWorkforce(query, status, cursor, size, asOf, population);
        List<People360Dtos.Snapshot> items = selected.items().stream()
                .map(person -> searchSnapshot(person.personId(), asOf, actor, authorization))
                .flatMap(Optional::stream)
                .toList();
        // The opaque directory cursor must remain advanceable past filtered candidates,
        // while size describes only items the caller is allowed to observe.
        return new People360Dtos.Page(
                items, selected.nextCursor(), items.size(), selected.hasMore(), asOf);
    }

    @Transactional(readOnly = true)
    public People360Dtos.Snapshot get(UUID personId, LocalDate requestedAsOf) {
        requireRuntimeEnabled();
        LocalDate asOf = requiredAsOf(requestedAsOf);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        Authorization authorization = authorizeOperations(actor);
        People360Repository.PersonRow person = person(actor, personId);
        List<People360Repository.CurrentEmploymentRow> employments =
                repository.findCurrentEmployments(
                        actor.tenantId(), person.internalPersonId(), asOf);
        requirePopulationMembership(actor, authorization.population(), employments);
        return project(
                actor, person, uniquePrimary(employments), asOf, authorization,
                People360ProjectionPolicyProvider.ProjectionPurpose.DETAIL);
    }

    @Transactional(readOnly = true)
    public People360Dtos.Snapshot getTeam(UUID personId, LocalDate requestedAsOf) {
        requireRuntimeEnabled();
        LocalDate asOf = requiredAsOf(requestedAsOf);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        Authorization authorization = authorizeTeam();
        People360Repository.PersonRow person = person(actor, personId);
        List<People360Repository.CurrentEmploymentRow> employments =
                repository.findCurrentEmployments(
                        actor.tenantId(), person.internalPersonId(), asOf);
        requirePopulationMembership(actor, authorization.population(), employments);
        return project(
                actor, person, uniquePrimary(employments), asOf, authorization,
                People360ProjectionPolicyProvider.ProjectionPurpose.DETAIL);
    }

    @Transactional(readOnly = true)
    public People360Dtos.Snapshot getSelf(LocalDate requestedAsOf) {
        requireRuntimeEnabled();
        LocalDate asOf = requiredAsOf(requestedAsOf);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.personPublicId() == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A verified workforce identity is required for People 360 self access.");
        }
        populationScopes.requireSelfScope();
        People360Repository.PersonRow person = person(actor, actor.personPublicId());
        List<People360Repository.CurrentEmploymentRow> employments =
                repository.findCurrentEmployments(
                        actor.tenantId(), person.internalPersonId(), asOf);
        Authorization authorization = selfAuthorization(person);
        return project(
                actor, person, uniquePrimary(employments), asOf, authorization,
                People360ProjectionPolicyProvider.ProjectionPurpose.DETAIL);
    }

    private Optional<People360Dtos.Snapshot> searchSnapshot(
            UUID personId,
            LocalDate asOf,
            PeopleRequestContext.Actor actor,
            Authorization authorization) {
        People360Repository.PersonRow person = person(actor, personId);
        List<People360Repository.CurrentEmploymentRow> employments =
                repository.findCurrentEmployments(
                        actor.tenantId(), person.internalPersonId(), asOf);
        if (!inPopulation(actor, authorization.population(), employments)) {
            return Optional.empty();
        }
        return Optional.of(project(
                actor, person, uniquePrimary(employments), asOf, authorization,
                People360ProjectionPolicyProvider.ProjectionPurpose.LIST));
    }

    private People360Repository.PersonRow person(
            PeopleRequestContext.Actor actor,
            UUID personId) {
        return repository.findPerson(actor.tenantId(), personId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private Authorization authorizeOperations(PeopleRequestContext.Actor actor) {
        HcmPopulationScopeService.ResolvedPopulation operations =
                populationScopes.findOperations("READ").orElse(null);
        if (operations == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        requireOperationsEvidence(operations);
        return operationsAuthorization(actor, operations);
    }

    private Authorization authorizeTeam() {
        HcmPopulationScopeService.ResolvedPopulation team = populationScopes.requireTeam();
        populationScopes.requireTrustedScope(
                team, "hcm.team", "TARGET_POPULATION",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TEAM/ORG_UNIT");
        return new Authorization(
                People360Dtos.Archetype.MANAGER,
                "TEAM",
                revision("team", team.targetPopulationRevision()),
                team.scope().fieldGroups(),
                team);
    }

    private Authorization selfAuthorization(People360Repository.PersonRow person) {
        return new Authorization(
                People360Dtos.Archetype.SELF,
                "SELF",
                revision("self", person.personId(), person.version()),
                Set.of("DIRECTORY", "WORKER_IDENTIFIERS", "EMPLOYMENT", "JOB_GRADE"),
                null);
    }

    private Authorization operationsAuthorization(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population) {
        People360Dtos.Archetype archetype = projectionPolicies.operationsArchetype(
                new People360ProjectionPolicyProvider.Subject(
                        actor.roles(), actor.permissions()));
        return new Authorization(
                archetype,
                "WORKFORCE_POLICY",
                revision("policy", population.targetPopulationRevision()),
                population.scope().fieldGroups(),
                population);
    }

    private void requireOperationsEvidence(
            HcmPopulationScopeService.ResolvedPopulation population) {
        populationScopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "WORKFORCE_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY");
    }

    private void requirePopulationMembership(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            List<People360Repository.CurrentEmploymentRow> employments) {
        if (!inPopulation(actor, population, employments)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
    }

    private boolean inPopulation(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            List<People360Repository.CurrentEmploymentRow> employments) {
        if (employments.isEmpty()) return population.scope().tenantWide();
        return employments.stream()
                .map(People360Repository.CurrentEmploymentRow::internalWorkerId)
                .distinct()
                .allMatch(workerId -> populations.containsWorker(
                        actor.tenantId(), population.scope(), workerId));
    }

    private People360Repository.CurrentEmploymentRow uniquePrimary(
            List<People360Repository.CurrentEmploymentRow> employments) {
        List<People360Repository.CurrentEmploymentRow> primary = employments.stream()
                .filter(row -> row.assignmentId() != null)
                .toList();
        if (primary.size() != 1) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    primary.isEmpty()
                            ? "No primary assignment is effective for the requested as-of date."
                            : "Multiple primary assignments are effective for the requested as-of date.");
        }
        return primary.getFirst();
    }

    private People360Dtos.Snapshot project(
            PeopleRequestContext.Actor actor,
            People360Repository.PersonRow person,
            People360Repository.CurrentEmploymentRow employment,
            LocalDate asOf,
            Authorization authorization,
            People360ProjectionPolicyProvider.ProjectionPurpose purpose) {
        People360ProjectionPolicyProvider.ProjectionPolicy policy =
                projectionPolicies.resolve(
                        new People360ProjectionPolicyProvider.PolicyInput(
                                actor.tenantId(), asOf, purpose, authorization.archetype(),
                                authorization.authorityRevision(), authorization.fieldGroups()));
        Decisions decisions = new Decisions(policy);
        String effectivePolicyRevision = revision(
                "effective-policy", authorization.authorityRevision(),
                policy.providerRevision(), actor.tenantId(), asOf, purpose);
        People360Dtos.Person personProjection = new People360Dtos.Person(
                person.personId(),
                decisions.text("person.displayName", person.displayName()),
                decisions.text("person.preferredLocale", person.preferredLocale()),
                decisions.text("person.timeZone", person.timeZone()),
                decisions.text("person.lifecycleState", person.lifecycleState()));
        People360Dtos.Employment employmentProjection = decisions.section("employment")
                ? new People360Dtos.Employment(
                        decisions.text("employment.workerNumber", employment.workerNumber()),
                        decisions.text("employment.workerType", employment.workerType()),
                        decisions.text("employment.workerStatus", employment.workerStatus()),
                        decisions.date("employment.originalHireDate", employment.originalHireDate()),
                        decisions.text("employment.relationshipType", employment.relationshipType()),
                        decisions.date(
                                "employment.relationshipStartDate",
                                employment.relationshipStartDate()),
                        decisions.date(
                                "employment.relationshipEndDate",
                                employment.relationshipEndDate()),
                        decisions.text(
                                "employment.legalEmployerName",
                                employment.legalEmployerName()))
                : null;
        People360Dtos.PrimaryAssignment assignmentProjection =
                decisions.section("primaryAssignment")
                ? new People360Dtos.PrimaryAssignment(
                        decisions.text(
                                "primaryAssignment.assignmentKey",
                                employment.assignmentKey()),
                        decisions.text(
                                "primaryAssignment.assignmentStatus",
                                employment.assignmentStatus()),
                        decisions.text(
                                "primaryAssignment.businessTitle",
                                employment.businessTitle()),
                        decisions.text(
                                "primaryAssignment.organizationName",
                                employment.organizationName()),
                        decisions.text(
                                "primaryAssignment.jobProfileName",
                                employment.jobProfileName()),
                        decisions.text(
                                "primaryAssignment.jobGradeName",
                                employment.jobGradeName()),
                        decisions.text(
                                "primaryAssignment.locationName",
                                employment.locationName()),
                        decisions.text(
                                "primaryAssignment.managerDisplayName",
                                employment.managerDisplayName()),
                        decisions.date(
                                "primaryAssignment.effectiveStartDate",
                                employment.effectiveStartDate()),
                        decisions.date(
                                "primaryAssignment.effectiveEndDate",
                                employment.effectiveEndDate()))
                : null;
        People360Dtos.Access access = new People360Dtos.Access(
                authorization.archetype(), authorization.scope(),
                effectivePolicyRevision, decisions.contract());
        return new People360Dtos.Snapshot(
                People360Dtos.SCHEMA_VERSION,
                asOf,
                People360Dtos.ProjectionState.READY,
                revision(
                        "projection", asOf, person.version(), employment.workerVersion(),
                        employment.relationshipVersion(), employment.assignmentVersion(),
                        effectivePolicyRevision, decisions.fingerprint()),
                personProjection,
                employmentProjection,
                assignmentProjection,
                access);
    }

    private LocalDate requiredAsOf(LocalDate requested) {
        if (requested == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The People 360 asOf date is required.");
        }
        return requested;
    }

    private void requireRuntimeEnabled() {
        if (!runtimeEnabled) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The People 360 runtime contract is not enabled for this service.");
        }
    }

    private String revision(String kind, Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(kind.getBytes(StandardCharsets.UTF_8));
            for (Object value : values) {
                digest.update((byte) 0);
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            }
            return kind + '-' + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record Authorization(
            People360Dtos.Archetype archetype,
            String scope,
            String authorityRevision,
            Set<String> fieldGroups,
            HcmPopulationScopeService.ResolvedPopulation population) {
    }

    private static final class Decisions {
        private static final Set<String> DATE_FIELDS = Set.of(
                "employment.originalHireDate",
                "employment.relationshipStartDate",
                "employment.relationshipEndDate",
                "primaryAssignment.effectiveStartDate",
                "primaryAssignment.effectiveEndDate");

        private final Map<String, People360Dtos.FieldDecisionValue> values;

        private Decisions(People360ProjectionPolicyProvider.ProjectionPolicy policy) {
            if (policy == null || policy.providerRevision() == null
                    || policy.providerRevision().isBlank()
                    || !policy.fieldDecisions().keySet().equals(
                            Set.copyOf(People360ProjectionPolicyProvider.FIELD_REGISTRY))
                    || policy.fieldDecisions().values().stream().anyMatch(
                            java.util.Objects::isNull)
                    || policy.fieldDecisions().entrySet().stream().anyMatch(entry ->
                            entry.getValue() == People360Dtos.FieldDecisionValue.MASK
                                    && DATE_FIELDS.contains(entry.getKey()))) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The People 360 field projection policy is incomplete.");
            }
            this.values = Map.copyOf(policy.fieldDecisions());
        }

        private boolean view(String field) {
            return values.get(field) == People360Dtos.FieldDecisionValue.VIEW;
        }

        private String text(String field, String value) {
            return switch (values.get(field)) {
                case VIEW -> value;
                case MASK -> People360Dtos.MASK_LITERAL;
                case OMIT -> null;
            };
        }

        private LocalDate date(String field, LocalDate value) {
            return view(field) ? value : null;
        }

        private boolean section(String prefix) {
            return values.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().startsWith(prefix + '.')
                            && entry.getValue() != People360Dtos.FieldDecisionValue.OMIT);
        }

        private List<People360Dtos.FieldDecision> contract() {
            List<People360Dtos.FieldDecision> result = new ArrayList<>();
            People360ProjectionPolicyProvider.FIELD_REGISTRY.forEach(field -> result.add(
                    new People360Dtos.FieldDecision(field, values.get(field))));
            return List.copyOf(result);
        }

        private String fingerprint() {
            List<String> valuesInOrder = new ArrayList<>();
            People360ProjectionPolicyProvider.FIELD_REGISTRY.forEach(
                    field -> valuesInOrder.add(field + '=' + values.get(field)));
            return String.join("|", valuesInOrder);
        }
    }
}
