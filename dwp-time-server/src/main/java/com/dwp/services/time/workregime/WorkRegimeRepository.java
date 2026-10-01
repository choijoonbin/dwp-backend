package com.dwp.services.time.workregime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.ScopeType;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationResult;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;

/** Tenant-bound owner persistence used by the default-off Wave 1 application boundary. */
public interface WorkRegimeRepository {

    List<WorkPlanRecord> findEffective(long tenantId, LocalDate effectiveOn);

    List<WorkPlanRecord> findEffective(
            TargetAuthorizationGuard guard, LocalDate effectiveOn);

    Optional<WorkPlanRecord> findByPublicId(long tenantId, UUID publicId);

    Optional<WorkPlanRecord> findByPublicId(
            TargetAuthorizationGuard guard, UUID publicId);

    List<RulePack> findRulePacks(
            long tenantId, String jurisdiction, long policyRevision);

    List<PolicyCandidate> findPolicyCandidates(
            long tenantId, String jurisdiction, long policyRevision);

    /**
     * Returns only global policies and population policies whose persisted target binding is still
     * valid for the supplied actor entitlement. Implementations must revalidate and lock that
     * entitlement in the same transaction as the candidate read.
     */
    List<PolicyCandidate> findPolicyCandidates(
            TargetAuthorizationGuard guard, String jurisdiction, long policyRevision);

    List<AssignmentPlan> findSimulationAssignments(
            long tenantId, UUID workRegimePublicId, EffectivePeriod period);

    List<AssignmentPlan> findSimulationAssignments(
            TargetAuthorizationGuard guard,
            UUID workRegimePublicId,
            EffectivePeriod period);

    WorkPlanRecord createDraft(DraftWrite draft);

    WorkRegimeRevision transition(TransitionWrite transition);

    void saveSimulation(SimulationWrite simulation);

    /**
     * Persists a successful simulation and its resulting lifecycle transition as one unit.
     *
     * <p>The JDBC owner adapter overrides this default to guarantee one tenant-bound database
     * transaction. The default keeps lightweight test adapters source-compatible while preserving
     * the required call order.
     */
    default WorkRegimeRevision saveSimulationAndTransition(
            SimulationWrite simulation, TransitionWrite transition) {
        saveSimulation(simulation);
        return transition(transition);
    }

    Optional<StoredSimulation> findSimulationByReceipt(long tenantId, UUID receiptId);

    Optional<StoredSimulation> findSimulationByReceipt(
            TargetAuthorizationGuard guard, UUID receiptId, UUID aggregateId);

    /**
     * Finds immutable mutation evidence for receipt reconciliation. Missing evidence is deliberately
     * fail-closed; lightweight adapters need not claim that a command committed.
     */
    default Optional<CommandOutcomeEvidence> findCommandOutcome(
            long tenantId,
            UUID receiptId,
            UUID aggregateId,
            LifecycleAction operation) {
        return Optional.empty();
    }

    Optional<CommandOutcomeEvidence> findCommandOutcome(
            TargetAuthorizationGuard guard,
            UUID receiptId,
            UUID aggregateId,
            LifecycleAction operation);

    record WorkPlanRecord(
            WorkRegimeRevision revision,
            String displayName,
            ArrangementKind arrangementKind,
            String extensionCode,
            ScopeType scopeType,
            UUID rulePackPublicId,
            String jurisdiction,
            long policyRevision,
            String resolutionDigest,
            String zoneId,
            UUID assignmentPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod assignmentPeriod,
            TargetBindingEvidence targetBindingEvidence,
            List<LocalSegment> segments) {
        public WorkPlanRecord {
            if (revision == null || displayName == null || displayName.isBlank()
                    || arrangementKind == null || scopeType == null
                    || rulePackPublicId == null || jurisdiction == null || jurisdiction.isBlank()
                    || policyRevision < 1 || resolutionDigest == null
                    || zoneId == null || zoneId.isBlank()
                    || assignmentPublicId == null || workerPublicId == null
                    || peopleAssignmentPublicId == null
                    || peopleAssignmentRevision < 0 || assignmentPeriod == null
                    || targetBindingEvidence == null) {
                throw new IllegalArgumentException("work-plan record is incomplete");
            }
            if (!targetBindingEvidence.peopleAssignmentPublicId().equals(
                            peopleAssignmentPublicId)
                    || !targetBindingEvidence.workerPublicId().equals(workerPublicId)
                    || targetBindingEvidence.peopleAssignmentRevision()
                            != peopleAssignmentRevision) {
                throw new IllegalArgumentException("work-plan target evidence is mismatched");
            }
            if (scopeType != ScopeType.POPULATION
                    || !revision.scopeRef().equals(
                            WorkRegimeTargetPopulationResolver.stableScopeRef(
                                    targetBindingEvidence.populationPublicId()))
                    || revision.authorActorId() != targetBindingEvidence.authorActorId()) {
                throw new IllegalArgumentException(
                        "work-plan scope and author evidence must match the target population");
            }
            segments = List.copyOf(segments);
            WorkRegimeModels.requireDigest(resolutionDigest, "resolutionDigest");
        }
    }

    record PolicyTermWrite(
            WorkRegimeModels.PolicyExtensionKind extensionKind,
            String parameterName,
            String valueType,
            String stringValue,
            Long integerValue,
            java.math.BigDecimal decimalValue,
            Boolean booleanValue,
            LocalDate dateValue) {
    }

    record TenantExtensionWrite(
            String schemaRef,
            int schemaVersion,
            String payloadJson,
            String payloadDigest) {
    }

    record DraftWrite(
            long tenantId,
            UUID publicId,
            UUID assignmentPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            String regimeKey,
            long revision,
            String displayName,
            ArrangementKind arrangementKind,
            TenantExtensionWrite tenantExtension,
            ScopeType scopeType,
            String scopeRef,
            int priority,
            EffectivePeriod period,
            String zoneId,
            UUID rulePackPublicId,
            String jurisdiction,
            String jurisdictionSubdivision,
            long policyRevision,
            String resolutionDigest,
            int templateSchemaVersion,
            String templateDigest,
            String sourceContextDigest,
            long authorActorId,
            UUID correlationId,
            List<PolicyTermWrite> terms,
            List<LocalSegment> segments,
            TargetBindingEvidence targetBindingEvidence,
            CommandEvidence commandEvidence) {
        public DraftWrite {
            terms = List.copyOf(terms);
            segments = List.copyOf(segments);
            if (commandEvidence == null) {
                throw new IllegalArgumentException("commandEvidence must not be null");
            }
            if (targetBindingEvidence == null
                    || !targetBindingEvidence.peopleAssignmentPublicId().equals(
                            peopleAssignmentPublicId)
                    || !targetBindingEvidence.workerPublicId().equals(workerPublicId)
                    || targetBindingEvidence.peopleAssignmentRevision()
                            != peopleAssignmentRevision) {
                throw new IllegalArgumentException("targetBindingEvidence is mismatched");
            }
            if (targetBindingEvidence.authorActorId() != authorActorId
                    || commandEvidence.actorId() != authorActorId
                    || commandEvidence.targetAuthorization().tenantId() != tenantId
                    || commandEvidence.targetAuthorization().actorId() != authorActorId
                    || !commandEvidence.targetAuthorization().populationPublicId().equals(
                            targetBindingEvidence.populationPublicId())) {
                throw new IllegalArgumentException(
                        "draft command, author and target authority evidence are mismatched");
            }
        }
    }

    record TargetAuthorizationGuard(
            long tenantId,
            long actorId,
            String gatewayScopeKey,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String populationDigest,
            String grantDigest,
            Instant checkedAt) {
        public TargetAuthorizationGuard {
            if (tenantId <= 0 || actorId <= 0) {
                throw new IllegalArgumentException(
                        "target authorization tenant and actor must be positive");
            }
            gatewayScopeKey = WorkRegimeModels.requireBoundedText(
                    gatewayScopeKey, "gatewayScopeKey", 38);
            if (!gatewayScopeKey.matches("scope-[0-9a-f]{32}")) {
                throw new IllegalArgumentException("gatewayScopeKey is not canonical");
            }
            java.util.Objects.requireNonNull(
                    populationPublicId, "populationPublicId must not be null");
            if (populationRevision <= 0 || grantRevision <= 0) {
                throw new IllegalArgumentException(
                        "target authorization revisions must be positive");
            }
            populationDigest = WorkRegimeModels.requireDigest(
                    populationDigest, "populationDigest");
            grantDigest = WorkRegimeModels.requireDigest(grantDigest, "grantDigest");
            java.util.Objects.requireNonNull(checkedAt, "checkedAt must not be null");
        }

        String scopePublicRef() {
            return WorkRegimeTargetPopulationResolver.stableScopeRef(populationPublicId);
        }
    }

    record TargetBindingEvidence(
            UUID populationPublicId,
            long populationRevision,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            long membershipRevision,
            long authorActorId,
            String authorGatewayScopeKey,
            long authorGrantRevision,
            String populationDigest,
            String membershipDigest,
            String grantDigest,
            Instant verifiedAt) {
        public TargetBindingEvidence {
            java.util.Objects.requireNonNull(
                    populationPublicId, "populationPublicId must not be null");
            if (populationRevision <= 0) {
                throw new IllegalArgumentException("populationRevision must be positive");
            }
            java.util.Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
            java.util.Objects.requireNonNull(
                    peopleAssignmentPublicId, "peopleAssignmentPublicId must not be null");
            if (peopleAssignmentRevision < 0) {
                throw new IllegalArgumentException(
                        "peopleAssignmentRevision must not be negative");
            }
            if (membershipRevision <= 0) {
                throw new IllegalArgumentException("membershipRevision must be positive");
            }
            if (authorActorId <= 0) {
                throw new IllegalArgumentException("authorActorId must be positive");
            }
            authorGatewayScopeKey = WorkRegimeModels.requireBoundedText(
                    authorGatewayScopeKey, "authorGatewayScopeKey", 38);
            if (!authorGatewayScopeKey.matches("scope-[0-9a-f]{32}")) {
                throw new IllegalArgumentException("authorGatewayScopeKey is not canonical");
            }
            if (authorGrantRevision <= 0) {
                throw new IllegalArgumentException("authorGrantRevision must be positive");
            }
            populationDigest = WorkRegimeModels.requireDigest(
                    populationDigest, "populationDigest");
            membershipDigest = WorkRegimeModels.requireDigest(
                    membershipDigest, "membershipDigest");
            grantDigest = WorkRegimeModels.requireDigest(grantDigest, "grantDigest");
            java.util.Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
        }
    }

    record TransitionWrite(
            long tenantId,
            UUID publicId,
            long expectedVersion,
            PolicyState nextState,
            Long approvalActorId,
            UUID approvalReceiptId,
            Long publisherActorId,
            UUID publicationReceiptId,
            long updatedBy,
            CommandEvidence commandEvidence) {
        public TransitionWrite {
            if (commandEvidence == null
                    || commandEvidence.actorId() != updatedBy
                    || commandEvidence.targetAuthorization().tenantId() != tenantId
                    || commandEvidence.targetAuthorization().actorId() != updatedBy) {
                throw new IllegalArgumentException(
                        "transition command and target authority evidence are mismatched");
            }
            if (nextState == PolicyState.APPROVED
                    && (!Objects.equals(approvalActorId, updatedBy)
                            || !Objects.equals(
                                    approvalReceiptId, commandEvidence.receiptId())
                            || publisherActorId != null
                            || publicationReceiptId != null)) {
                throw new IllegalArgumentException(
                        "approval transition evidence must match the command actor and receipt");
            }
            if (nextState == PolicyState.PUBLISHED
                    && (!Objects.equals(publisherActorId, updatedBy)
                            || !Objects.equals(
                                    publicationReceiptId, commandEvidence.receiptId()))) {
                throw new IllegalArgumentException(
                        "publication transition evidence must match the command actor and receipt");
            }
        }
    }

    record SimulationWrite(
            long tenantId,
            UUID publicId,
            UUID receiptId,
            UUID idempotencyKey,
            UUID workRegimePublicId,
            EffectivePeriod period,
            String requestDigest,
            String resultDigest,
            String zoneId,
            String tzdbVersion,
            UUID rulePackPublicId,
            long policyRevision,
            String resolutionDigest,
            SimulationResult result,
            Instant calculatedAt,
            long actorId,
            CommandEvidence commandEvidence) {
        public SimulationWrite {
            if (commandEvidence == null
                    || commandEvidence.actorId() != actorId
                    || commandEvidence.targetAuthorization().tenantId() != tenantId
                    || commandEvidence.targetAuthorization().actorId() != actorId) {
                throw new IllegalArgumentException(
                        "simulation command and target authority evidence are mismatched");
            }
            if (!receiptId.equals(commandEvidence.receiptId())
                    || !idempotencyKey.equals(commandEvidence.idempotencyKey())
                    || commandEvidence.operation() != LifecycleAction.SIMULATE
                    || !workRegimePublicId.equals(commandEvidence.aggregateId())
                    || !requestDigest.equals(commandEvidence.requestDigest())) {
                throw new IllegalArgumentException(
                        "simulation write is not exactly bound to its command receipt");
            }
        }
    }

    record CommandEvidence(
            UUID receiptId,
            UUID correlationId,
            long actorId,
            String purpose,
            String decisionId,
            UUID idempotencyKey,
            LifecycleAction operation,
            UUID aggregateId,
            Long expectedVersion,
            String requestDigest,
            TargetAuthorizationGuard targetAuthorization) {
        public CommandEvidence {
            if (receiptId == null || correlationId == null || actorId <= 0
                    || idempotencyKey == null || operation == null || aggregateId == null) {
                throw new IllegalArgumentException("command evidence identity is invalid");
            }
            WorkRegimeModels.requireText(purpose, "command purpose");
            WorkRegimeModels.requireText(decisionId, "command decisionId");
            WorkRegimeModels.requireDigest(requestDigest, "command requestDigest");
            if ((operation == LifecycleAction.CREATE_DRAFT) != (expectedVersion == null)
                    || expectedVersion != null && expectedVersion <= 0) {
                throw new IllegalArgumentException("command expectedVersion is invalid");
            }
            java.util.Objects.requireNonNull(
                    targetAuthorization, "targetAuthorization must not be null");
            if (targetAuthorization.actorId() != actorId) {
                throw new IllegalArgumentException(
                        "command actor and target authorization actor are mismatched");
            }
        }
    }

    record CommandOutcomeEvidence(String resultCode, String resultDigest) {
        public CommandOutcomeEvidence {
            WorkRegimeModels.requireText(resultCode, "resultCode");
            WorkRegimeModels.requireDigest(resultDigest, "resultDigest");
        }
    }

    record StoredSimulation(
            UUID receiptId,
            UUID workRegimePublicId,
            long baseVersion,
            SimulationResult result,
            String resultDigest) {
        public StoredSimulation {
            if (baseVersion < 1) {
                throw new IllegalArgumentException("baseVersion must be positive");
            }
        }
    }
}
