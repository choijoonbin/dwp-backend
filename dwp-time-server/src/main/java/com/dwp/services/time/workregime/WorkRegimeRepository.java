package com.dwp.services.time.workregime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    Optional<WorkPlanRecord> findByPublicId(long tenantId, UUID publicId);

    List<RulePack> findRulePacks(
            long tenantId, String jurisdiction, long policyRevision);

    List<PolicyCandidate> findPolicyCandidates(
            long tenantId, String jurisdiction, long policyRevision);

    List<AssignmentPlan> findSimulationAssignments(
            long tenantId, UUID workRegimePublicId, EffectivePeriod period);

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
            long peopleAssignmentRevision,
            EffectivePeriod assignmentPeriod,
            List<LocalSegment> segments) {
        public WorkPlanRecord {
            if (revision == null || displayName == null || displayName.isBlank()
                    || arrangementKind == null || scopeType == null
                    || rulePackPublicId == null || jurisdiction == null || jurisdiction.isBlank()
                    || policyRevision < 1 || resolutionDigest == null
                    || zoneId == null || zoneId.isBlank()
                    || assignmentPublicId == null || workerPublicId == null
                    || peopleAssignmentRevision < 0 || assignmentPeriod == null) {
                throw new IllegalArgumentException("work-plan record is incomplete");
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
            CommandEvidence commandEvidence) {
        public DraftWrite {
            terms = List.copyOf(terms);
            segments = List.copyOf(segments);
            if (commandEvidence == null) {
                throw new IllegalArgumentException("commandEvidence must not be null");
            }
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
    }

    record CommandEvidence(
            UUID receiptId,
            UUID correlationId,
            long actorId,
            String purpose,
            String decisionId) {
        public CommandEvidence {
            if (receiptId == null || correlationId == null || actorId <= 0) {
                throw new IllegalArgumentException("command evidence identity is invalid");
            }
            WorkRegimeModels.requireText(purpose, "command purpose");
            WorkRegimeModels.requireText(decisionId, "command decisionId");
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
