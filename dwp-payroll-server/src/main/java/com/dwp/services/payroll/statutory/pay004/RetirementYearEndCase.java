package com.dwp.services.payroll.statutory.pay004;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class RetirementYearEndCase {

    enum State {
        DRAFT, VALIDATED, BLOCKED_PACK_REQUIRED, CALCULATED, APPROVED,
        FINALIZED, CORRECTED, CANCELLED
    }

    enum CountryPackState { NOT_REQUIRED, NOT_INSTALLED, DISABLED, ACTIVE }

    private static final Set<State> TERMINAL = Set.of(
            State.FINALIZED, State.CORRECTED, State.CANCELLED);

    private final long tenantId;
    private final UUID caseId;
    private final long revision;
    private final UUID workerId;
    private final OwnerReference employmentRef;
    private final UUID legalEntityId;
    private final UUID payrollGroupId;
    private final LocalDate effectiveOn;
    private final State state;
    private final CountryPackState countryPackState;
    private final UUID makerSubjectPublicId;
    private final UUID transitionSubjectPublicId;
    private final String predecessorDigest;
    private final String revisionDigest;
    private final Instant recordedAt;

    private RetirementYearEndCase(
            long tenantId,
            UUID caseId,
            long revision,
            UUID workerId,
            OwnerReference employmentRef,
            UUID legalEntityId,
            UUID payrollGroupId,
            LocalDate effectiveOn,
            State state,
            CountryPackState countryPackState,
            UUID makerSubjectPublicId,
            UUID transitionSubjectPublicId,
            String predecessorDigest,
            Instant recordedAt) {
        if (tenantId < 1 || revision < 1) {
            throw new IllegalArgumentException("case identity is invalid");
        }
        this.tenantId = tenantId;
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.revision = revision;
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.employmentRef = Objects.requireNonNull(employmentRef, "employmentRef");
        this.legalEntityId = Objects.requireNonNull(legalEntityId, "legalEntityId");
        this.payrollGroupId = Objects.requireNonNull(payrollGroupId, "payrollGroupId");
        this.effectiveOn = Objects.requireNonNull(effectiveOn, "effectiveOn");
        this.state = Objects.requireNonNull(state, "state");
        this.countryPackState = Objects.requireNonNull(
                countryPackState, "countryPackState");
        this.makerSubjectPublicId = makerSubjectPublicId;
        this.transitionSubjectPublicId = Objects.requireNonNull(
                transitionSubjectPublicId, "transitionSubjectPublicId");
        if (revision == 1 && predecessorDigest != null
                || revision > 1 && predecessorDigest == null) {
            throw new IllegalArgumentException("predecessor digest is invalid");
        }
        if (predecessorDigest != null) {
            Pay004Models.digest(predecessorDigest, "predecessorDigest");
        }
        this.predecessorDigest = predecessorDigest;
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        this.revisionDigest = Pay004CanonicalDigest.sha256(
                "Pay004RetirementCaseRevision.v1",
                tenantId, caseId, revision, workerId,
                employmentRef.owner(), employmentRef.schemaId(),
                employmentRef.publicId(), employmentRef.revision(),
                employmentRef.digest(), legalEntityId, payrollGroupId,
                effectiveOn, state, countryPackState, makerSubjectPublicId,
                transitionSubjectPublicId, predecessorDigest, recordedAt);
    }

    static RetirementYearEndCase create(
            long tenantId,
            UUID caseId,
            UUID workerId,
            OwnerReference employmentRef,
            UUID legalEntityId,
            UUID payrollGroupId,
            LocalDate effectiveOn,
            CountryPackState packState,
            UUID creator,
            Instant recordedAt) {
        return new RetirementYearEndCase(
                tenantId, caseId, 1, workerId, employmentRef,
                legalEntityId, payrollGroupId, effectiveOn, State.DRAFT,
                packState, null, creator, null, recordedAt);
    }

    RetirementYearEndCase validate(
            CountryPackState resolvedPackState,
            UUID validator,
            Instant now) {
        requireState(State.DRAFT);
        State next = switch (resolvedPackState) {
            case ACTIVE, NOT_REQUIRED -> State.VALIDATED;
            case NOT_INSTALLED, DISABLED -> State.BLOCKED_PACK_REQUIRED;
        };
        return next(next, resolvedPackState, makerSubjectPublicId,
                Objects.requireNonNull(validator, "validator"),
                now);
    }

    RetirementYearEndCase calculate(
            ResultLineage lineage,
            UUID maker,
            Instant now) {
        requireState(State.VALIDATED);
        lineage.requireFor(this);
        if (!lineage.calculatedAt().equals(now)) {
            throw new IllegalArgumentException(
                    "result calculation time differs from the case transition");
        }
        return next(State.CALCULATED, countryPackState,
                Objects.requireNonNull(maker, "maker"), maker,
                now);
    }

    RetirementYearEndCase approve(UUID checker, Instant now) {
        requireState(State.CALCULATED);
        requireChecker(checker);
        return next(State.APPROVED, countryPackState,
                makerSubjectPublicId, checker, now);
    }

    RetirementYearEndCase finalizeCase(UUID checker, Instant now) {
        requireState(State.APPROVED);
        requireChecker(checker);
        return next(State.FINALIZED, countryPackState,
                makerSubjectPublicId, checker, now);
    }

    RetirementYearEndCase correct(UUID correctionMaker, Instant now) {
        requireState(State.FINALIZED);
        requireChecker(correctionMaker);
        return next(State.CORRECTED, countryPackState,
                makerSubjectPublicId, correctionMaker, now);
    }

    RetirementYearEndCase cancel(UUID canceller, Instant now) {
        if (!Set.of(State.DRAFT, State.VALIDATED,
                State.BLOCKED_PACK_REQUIRED).contains(state)) {
            throw new IllegalStateException("case cannot be cancelled");
        }
        return next(State.CANCELLED, countryPackState,
                makerSubjectPublicId,
                Objects.requireNonNull(canceller, "canceller"),
                now);
    }

    boolean terminal() {
        return TERMINAL.contains(state);
    }

    private RetirementYearEndCase next(
            State nextState,
            CountryPackState nextPackState,
            UUID maker,
            UUID transitionSubject,
            Instant now) {
        if (terminal()
                && !(state == State.FINALIZED
                && nextState == State.CORRECTED)) {
            throw new IllegalStateException("terminal case is immutable");
        }
        Objects.requireNonNull(now, "now");
        if (!now.isAfter(recordedAt)) {
            throw new IllegalArgumentException(
                    "case revision time must increase monotonically");
        }
        return new RetirementYearEndCase(
                tenantId, caseId, revision + 1, workerId, employmentRef,
                legalEntityId, payrollGroupId, effectiveOn, nextState,
                nextPackState, maker, transitionSubject,
                revisionDigest, now);
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException(
                    "expected " + expected + " but was " + state);
        }
    }

    private void requireChecker(UUID checker) {
        Objects.requireNonNull(checker, "checker");
        if (checker.equals(makerSubjectPublicId)) {
            throw new IllegalArgumentException(
                    "maker and checker must be different subjects");
        }
    }

    long tenantId() { return tenantId; }
    UUID caseId() { return caseId; }
    long revision() { return revision; }
    UUID workerId() { return workerId; }
    OwnerReference employmentRef() { return employmentRef; }
    UUID legalEntityId() { return legalEntityId; }
    UUID payrollGroupId() { return payrollGroupId; }
    LocalDate effectiveOn() { return effectiveOn; }
    State state() { return state; }
    CountryPackState countryPackState() { return countryPackState; }
    UUID makerSubjectPublicId() { return makerSubjectPublicId; }
    UUID transitionSubjectPublicId() { return transitionSubjectPublicId; }
    String predecessorDigest() { return predecessorDigest; }
    String revisionDigest() { return revisionDigest; }
    Instant recordedAt() { return recordedAt; }

    record OwnerReference(
            String owner,
            String schemaId,
            UUID publicId,
            long revision,
            String digest) {
        OwnerReference {
            Pay004Models.token(owner, 80, "owner");
            Pay004Models.token(schemaId, 128, "schemaId");
            Objects.requireNonNull(publicId, "publicId");
            if (revision < 1) throw new IllegalArgumentException(
                    "owner reference revision is invalid");
            Pay004Models.digest(digest, "owner reference digest");
        }
    }

    record ResultLineage(
            long tenantId,
            UUID caseId,
            long caseRevision,
            UUID caseWorkerId,
            UUID runId,
            long runRevision,
            int attemptNo,
            UUID workerResultId,
            UUID workerResultWorkerId,
            CountryPackState countryPackState,
            UUID packId,
            long packSnapshotRevision,
            long activationRevision,
            UUID nonPackPolicyPublicId,
            long nonPackPolicyRevision,
            String nonPackPolicyDigest,
            String policyKey,
            long policyRevision,
            LocalDate policyEffectiveFrom,
            LocalDate policyEffectiveTo,
            String policyDigest,
            Pay004Models.PostedMoney result,
            String roundingStage,
            List<BasisFact> basisFacts,
            List<EvidenceFact> evidenceFacts,
            String workerResultDigest,
            String calculationResultDigest,
            Instant calculatedAt) {
        ResultLineage {
            if (tenantId < 1 || caseId == null || caseRevision < 1
                    || caseWorkerId == null
                    || runId == null || runRevision < 1 || attemptNo < 1
                    || workerResultId == null
                    || workerResultWorkerId == null || countryPackState == null
                    || policyKey == null || policyRevision < 1
                    || policyEffectiveFrom == null || policyDigest == null
                    || result == null || roundingStage == null
                    || calculatedAt == null) {
                throw new IllegalArgumentException("result lineage is incomplete");
            }
            if (!policyKey.matches("[a-z][a-z0-9._-]{1,127}")
                    || policyEffectiveTo != null
                    && policyEffectiveTo.isBefore(policyEffectiveFrom)) {
                throw new IllegalArgumentException("result policy is invalid");
            }
            Pay004Models.digest(policyDigest, "policyDigest");
            if (!"RETIREMENT_FINAL".equals(roundingStage)) {
                throw new IllegalArgumentException("roundingStage is invalid");
            }
            basisFacts = List.copyOf(Objects.requireNonNull(
                    basisFacts, "basisFacts"));
            evidenceFacts = List.copyOf(Objects.requireNonNull(
                    evidenceFacts, "evidenceFacts"));
            requireMicrosecond(calculatedAt, "calculatedAt");
            Pay004Models.digest(workerResultDigest, "workerResultDigest");
            Pay004Models.digest(
                    calculationResultDigest, "calculationResultDigest");
            if (!caseWorkerId.equals(workerResultWorkerId)) {
                throw new IllegalArgumentException("worker lineage differs");
            }
            if (countryPackState == CountryPackState.ACTIVE) {
                if (packId == null || packSnapshotRevision < 0
                        || activationRevision < 1 || nonPackPolicyPublicId != null
                        || nonPackPolicyRevision != 0
                        || nonPackPolicyDigest != null
                        || policyRevision != activationRevision) {
                    throw new IllegalArgumentException(
                            "ACTIVE result requires exact pack lineage");
                }
            } else if (countryPackState == CountryPackState.NOT_REQUIRED) {
                if (packId != null || packSnapshotRevision != 0
                        || activationRevision != 0
                        || nonPackPolicyPublicId == null
                        || nonPackPolicyRevision < 1
                        || nonPackPolicyDigest == null) {
                    throw new IllegalArgumentException(
                            "NOT_REQUIRED result requires non-pack policy lineage");
                }
                Pay004Models.digest(nonPackPolicyDigest, "nonPackPolicyDigest");
                if (policyRevision != nonPackPolicyRevision
                        || !policyDigest.equals(nonPackPolicyDigest)) {
                    throw new IllegalArgumentException(
                            "NOT_REQUIRED policy digest differs");
                }
            } else {
                throw new IllegalArgumentException(
                        "blocked country-pack state cannot produce a result");
            }
        }

        void requireFor(RetirementYearEndCase aggregate) {
            if (tenantId != aggregate.tenantId || !caseId.equals(aggregate.caseId)
                    || caseRevision != aggregate.revision + 1
                    || !caseWorkerId.equals(aggregate.workerId)
                    || countryPackState != aggregate.countryPackState
                    || !calculatedAt.isAfter(aggregate.recordedAt)) {
                throw new IllegalArgumentException(
                        "result does not belong to the exact case revision");
            }
        }

        String resultDigest() {
            return Pay004CanonicalDigest.sha256(
                    "Pay004RetirementResult.v1",
                    tenantId, caseId, caseRevision, caseWorkerId,
                    runId, runRevision, attemptNo, workerResultId,
                    workerResultWorkerId, countryPackState, packId,
                    packId == null ? null : packSnapshotRevision,
                    packId == null ? null : activationRevision,
                    nonPackPolicyPublicId,
                    nonPackPolicyPublicId == null
                            ? null : nonPackPolicyRevision,
                    nonPackPolicyDigest, policyKey, policyRevision,
                    policyEffectiveFrom, policyEffectiveTo, policyDigest,
                    result.amount().toPlainString(), result.currency(),
                    result.roundingMode(), roundingStage, caseInputsDigest(),
                    workerResultDigest, calculationResultDigest,
                    calculatedAt);
        }

        String caseInputsDigest() {
            List<String> basisDigests = basisFacts.stream()
                    .sorted(BasisFact.ORDER)
                    .map(BasisFact::factDigest)
                    .toList();
            List<String> evidenceDigests = evidenceFacts.stream()
                    .sorted(EvidenceFact.ORDER)
                    .map(EvidenceFact::factDigest)
                    .toList();
            java.util.ArrayList<Object> facts = new java.util.ArrayList<>();
            facts.add(basisDigests.size());
            facts.addAll(basisDigests);
            facts.add(evidenceDigests.size());
            facts.addAll(evidenceDigests);
            return Pay004CanonicalDigest.sha256(
                    "Pay004CaseInputs.v1", facts.toArray());
        }
    }

    record BasisFact(
            UUID basisRefId,
            String basisRole,
            String ownerServiceKey,
            String schemaId,
            UUID resourcePublicId,
            long resourceRevision,
            String resourceDigest,
            Instant recordedAt) {

        private static final Comparator<BasisFact> ORDER = Comparator
                .comparing(BasisFact::basisRole)
                .thenComparing(BasisFact::ownerServiceKey)
                .thenComparing(BasisFact::schemaId)
                .thenComparing(fact -> fact.resourcePublicId().toString())
                .thenComparingLong(BasisFact::resourceRevision)
                .thenComparing(fact -> fact.basisRefId().toString());

        BasisFact {
            Objects.requireNonNull(basisRefId, "basisRefId");
            Pay004Models.token(basisRole, 48, "basisRole");
            Pay004Models.token(ownerServiceKey, 80, "ownerServiceKey");
            Pay004Models.token(schemaId, 128, "schemaId");
            Objects.requireNonNull(resourcePublicId, "resourcePublicId");
            if (resourceRevision < 1) {
                throw new IllegalArgumentException(
                        "resourceRevision is invalid");
            }
            Pay004Models.digest(resourceDigest, "resourceDigest");
            requireMicrosecond(recordedAt, "basis recordedAt");
        }

        String factDigest() {
            return Pay004CanonicalDigest.sha256(
                    "Pay004BasisFact.v1", basisRefId, basisRole,
                    ownerServiceKey, schemaId, resourcePublicId,
                    resourceRevision, resourceDigest, recordedAt);
        }
    }

    record EvidenceFact(
            UUID evidenceId,
            String evidenceType,
            String evidenceOwner,
            String evidenceSchemaId,
            UUID evidencePublicId,
            long evidenceRevision,
            String evidenceDigest,
            Instant recordedAt) {

        private static final Comparator<EvidenceFact> ORDER = Comparator
                .comparing(EvidenceFact::evidenceType)
                .thenComparing(EvidenceFact::evidenceOwner)
                .thenComparing(EvidenceFact::evidenceSchemaId)
                .thenComparing(fact -> fact.evidencePublicId().toString())
                .thenComparingLong(EvidenceFact::evidenceRevision)
                .thenComparing(fact -> fact.evidenceId().toString());

        EvidenceFact {
            Objects.requireNonNull(evidenceId, "evidenceId");
            Pay004Models.token(evidenceType, 64, "evidenceType");
            Pay004Models.token(evidenceOwner, 80, "evidenceOwner");
            Pay004Models.token(evidenceSchemaId, 128, "evidenceSchemaId");
            Objects.requireNonNull(evidencePublicId, "evidencePublicId");
            if (evidenceRevision < 1) {
                throw new IllegalArgumentException(
                        "evidenceRevision is invalid");
            }
            Pay004Models.digest(evidenceDigest, "evidenceDigest");
            requireMicrosecond(recordedAt, "evidence recordedAt");
        }

        String factDigest() {
            return Pay004CanonicalDigest.sha256(
                    "Pay004EvidenceFact.v1", evidenceId, evidenceType,
                    evidenceOwner, evidenceSchemaId, evidencePublicId,
                    evidenceRevision, evidenceDigest, recordedAt);
        }
    }

    private static void requireMicrosecond(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    name + " must use PostgreSQL microsecond precision");
        }
    }
}
