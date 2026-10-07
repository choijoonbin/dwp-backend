package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeRepository.DraftWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.SimulationWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.TransitionWrite;
import java.util.Objects;

/** Validates repository write envelopes before any tenant transaction is opened. */
final class WorkRegimeWriteValidator {

    private WorkRegimeWriteValidator() { }

    static void validateDraft(DraftWrite draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        requireTenant(draft.tenantId());
        Objects.requireNonNull(draft.publicId(), "publicId must not be null");
        Objects.requireNonNull(draft.assignmentPublicId(), "assignmentPublicId must not be null");
        Objects.requireNonNull(draft.workerPublicId(), "workerPublicId must not be null");
        Objects.requireNonNull(
                draft.peopleAssignmentPublicId(), "peopleAssignmentPublicId must not be null");
        Objects.requireNonNull(draft.rulePackPublicId(), "rulePackPublicId must not be null");
        Objects.requireNonNull(draft.arrangementKind(), "arrangementKind must not be null");
        Objects.requireNonNull(draft.scopeType(), "scopeType must not be null");
        Objects.requireNonNull(
                draft.targetBindingEvidence(), "targetBindingEvidence must not be null");
        Objects.requireNonNull(draft.correlationId(), "correlationId must not be null");
        Objects.requireNonNull(draft.period(), "period must not be null");
        if (draft.revision() < 1 || draft.policyRevision() < 1
                || draft.templateSchemaVersion() < 1 || draft.authorActorId() <= 0
                || draft.peopleAssignmentRevision() < 0) {
            throw new IllegalArgumentException("draft revisions and actor evidence are invalid");
        }
        if (draft.scopeType() != WorkRegimeModels.ScopeType.POPULATION
                || !draft.scopeRef().equals(WorkRegimeTargetPopulationResolver.stableScopeRef(
                        draft.targetBindingEvidence().populationPublicId()))) {
            throw new IllegalArgumentException(
                    "draft scope must be the resolved stable target population");
        }
        requireCountry(draft.jurisdiction());
        if (draft.jurisdictionSubdivision() == null
                || draft.jurisdictionSubdivision().length() > 12
                || !draft.jurisdictionSubdivision().equals(
                        draft.jurisdictionSubdivision().trim())) {
            throw new IllegalArgumentException("jurisdiction subdivision must be canonical");
        }
        if ((draft.arrangementKind() == ArrangementKind.TENANT_EXTENSION)
                != (draft.tenantExtension() != null)) {
            throw new IllegalArgumentException(
                    "tenant extension evidence must match the arrangement kind");
        }
        Objects.requireNonNull(draft.terms(), "terms must not be null");
        Objects.requireNonNull(draft.segments(), "segments must not be null");
    }

    static void validateTransition(TransitionWrite transition) {
        Objects.requireNonNull(transition, "transition must not be null");
        requireTenant(transition.tenantId());
        Objects.requireNonNull(transition.publicId(), "publicId must not be null");
        Objects.requireNonNull(transition.nextState(), "nextState must not be null");
        Objects.requireNonNull(transition.commandEvidence(), "commandEvidence must not be null");
        if (transition.expectedVersion() < 1 || transition.updatedBy() <= 0) {
            throw new IllegalArgumentException("transition version and actor must be positive");
        }
        if (transition.commandEvidence().operation() != operationFor(transition.nextState())) {
            throw new IllegalArgumentException(
                    "transition state does not match its command operation");
        }
    }

    static void validateSuccessfulSimulationTransition(
            SimulationWrite simulation, TransitionWrite transition) {
        JdbcWorkRegimeSimulationPersistence.validate(simulation);
        validateTransition(transition);
        if (simulation.result().state() != WorkRegimeModels.SimulationState.SUCCEEDED
                || transition.nextState() != PolicyState.SIMULATED) {
            throw new IllegalArgumentException(
                    "Atomic simulation transition requires a successful SIMULATED result");
        }
        if (simulation.tenantId() != transition.tenantId()
                || !simulation.workRegimePublicId().equals(transition.publicId())
                || simulation.actorId() != transition.updatedBy()
                || !simulation.commandEvidence().equals(transition.commandEvidence())) {
            throw new IllegalArgumentException(
                    "Simulation and transition must carry one aggregate command identity");
        }
    }

    static void requireResolutionKey(long tenantId, String jurisdiction, long policyRevision) {
        requireTenant(tenantId);
        requireCountry(jurisdiction);
        if (policyRevision < 1) {
            throw new IllegalArgumentException("policyRevision must be at least one");
        }
    }

    private static void requireCountry(String jurisdiction) {
        if (jurisdiction == null || !jurisdiction.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("jurisdiction must be an ISO alpha-2 country");
        }
    }

    static void requireClosedPeriod(EffectivePeriod period) {
        Objects.requireNonNull(period, "period must not be null");
        if (period.to() == null) {
            throw new IllegalArgumentException("operation requires a closed half-open period");
        }
    }

    static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private static LifecycleAction operationFor(PolicyState state) {
        return switch (state) {
            case VALIDATED -> LifecycleAction.VALIDATE;
            case SIMULATED -> LifecycleAction.SIMULATE;
            case IN_REVIEW -> LifecycleAction.SUBMIT_REVIEW;
            case APPROVED -> LifecycleAction.APPLY_APPROVAL;
            case PUBLISHED -> LifecycleAction.PUBLISH;
            default -> throw new IllegalArgumentException(
                    "No command operation exists for transition to " + state);
        };
    }
}
