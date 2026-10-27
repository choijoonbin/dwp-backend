package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.PerArtifactType;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningSupport.*;

/** Builds compensation child results and reconstructs immutable command replay results. */
final class CompensationPlanningResults {

    private final CompensationPlanningRepository repository;
    private final PerTypedArtifactQueryPort artifactQuery;
    private final CompensationPlanningCanonicalizer canonicalizer;

    CompensationPlanningResults(CompensationPlanningRepository repository,
            PerTypedArtifactQueryPort artifactQuery, CompensationPlanningCanonicalizer canonicalizer) {
        this.repository = repository;
        this.artifactQuery = artifactQuery;
        this.canonicalizer = canonicalizer;
    }

    CompensationPlanningDtos.TypedChildResult artifactChild(
            PerTypedArtifactService.AppendedArtifact artifact,
            String entityType,
            int ordinal,
            String ownerType,
            UUID ownerId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        Map<String, Object> lineage = new HashMap<>();
        lineage.put("artifactType", artifact.artifactType().name());
        lineage.put("schemaVersion", artifact.schemaVersion());
        lineage.put("ownerAggregateType", ownerType);
        lineage.put("ownerAggregatePublicId", ownerId);
        lineage.put("canonicalContentDigest", artifact.sha256());
        lineage.put("effectiveFrom", effectiveFrom);
        lineage.put("effectiveTo", effectiveTo);
        return artifactVersionChild(entityType, artifact.artifactPublicId(),
                artifact.artifactRevision(), ordinal, lineage);
    }

    CompensationPlanningDtos.TypedChildResult artifactChild(
            PerTypedArtifactQueryPort.TypedArtifact artifact,
            String entityType,
            int ordinal,
            String ownerType,
            UUID ownerId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        Map<String, Object> lineage = new HashMap<>();
        lineage.put("artifactType", artifact.artifactType().name());
        lineage.put("schemaVersion", artifact.schemaVersion());
        lineage.put("ownerAggregateType", ownerType);
        lineage.put("ownerAggregatePublicId", ownerId);
        lineage.put("canonicalContentDigest", artifact.sha256());
        lineage.put("effectiveFrom", effectiveFrom);
        lineage.put("effectiveTo", effectiveTo);
        return artifactVersionChild(entityType, artifact.artifactPublicId(),
                artifact.artifactRevision(), ordinal, lineage);
    }

    CompensationPlanningDtos.TypedChildResult artifactVersionChild(
            String entityType,
            UUID publicId,
            long artifactRevision,
            int ordinal,
            Map<String, ?> lineage) {
        Map<String, Object> digestInput = new HashMap<>();
        digestInput.put("entityType", entityType);
        digestInput.put("publicId", publicId);
        digestInput.put("artifactRevision", artifactRevision);
        digestInput.put("ordinal", ordinal);
        digestInput.putAll(lineage);
        return new CompensationPlanningDtos.TypedChildResult(
                entityType, publicId, artifactRevision, ordinal,
                canonicalizer.resultDigest(digestInput));
    }

    CompensationPlanningDtos.TypedChildResult revisionChild(
            UUID proposalId,
            UUID revisionId,
            long revision,
            UUID artifactId,
            String contentDigest,
            int ordinal) {
        Map<String, Object> lineage = new HashMap<>();
        lineage.put("proposalPublicId", proposalId);
        lineage.put("revisionNo", revision);
        lineage.put("typedContentArtifactPublicId", artifactId);
        lineage.put("contentDigest", contentDigest);
        lineage.put("exceptionApprovalReceiptPublicId", null);
        return child("COMPENSATION_PROPOSAL_REVISION", revisionId,
                1, ordinal, lineage);
    }

    CompensationPlanningDtos.TypedChildResult child(
            String entityType,
            UUID publicId,
            long aggregateVersion,
            int ordinal,
            Map<String, ?> lineage) {
        Map<String, Object> digestInput = new HashMap<>();
        digestInput.put("entityType", entityType);
        digestInput.put("publicId", publicId);
        digestInput.put("aggregateVersion", aggregateVersion);
        digestInput.put("ordinal", ordinal);
        digestInput.putAll(lineage);
        return new CompensationPlanningDtos.TypedChildResult(
                entityType, publicId, aggregateVersion, ordinal,
                canonicalizer.resultDigest(digestInput));
    }

    CompensationPlanningDtos.RichCommandResult rich(
            UUID receiptId,
            UUID aggregateId,
            long aggregateVersion,
            UUID correlationId,
            Instant completedAt,
            List<CompensationPlanningDtos.TypedChildResult> children) {
        List<CompensationPlanningDtos.TypedChildResult> immutable = List.copyOf(children);
        Map<String, Object> result = new HashMap<>();
        result.put("commandReceiptId", receiptId);
        result.put("status", "COMPLETED");
        result.put("correlationId", correlationId);
        result.put("completedAt", completedAt);
        result.put("aggregatePublicId", aggregateId);
        result.put("aggregateVersion", aggregateVersion);
        result.put("childResults", immutable);
        return new CompensationPlanningDtos.RichCommandResult(
                receiptId, "COMPLETED", correlationId, completedAt,
                canonicalizer.resultDigest(result), aggregateId,
                aggregateVersion, immutable);
    }

    CompensationPlanningDtos.RichCommandResult replayRichRevision(
            long tenantId,
            UUID cycleId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningRepository.CycleArtifactEvidence evidence = repository
                .cycleArtifactsForReceipt(tenantId, replay.receiptId(), cycleId)
                .orElseThrow(CompensationPlanningSupport::unavailable);
        PerTypedArtifactQueryPort.TypedArtifact guidelines = artifactQuery.query(
                tenantId, evidence.guidelineArtifactId(),
                PerArtifactType.COMPENSATION_GUIDELINES, "COMPENSATION_CYCLE",
                cycleId, PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        PerTypedArtifactQueryPort.TypedArtifact population = artifactQuery.query(
                tenantId, evidence.populationArtifactId(),
                PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE",
                cycleId, PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        return rich(replay.receiptId(), cycleId, replay.aggregateVersion(),
                replay.correlationId(), replay.occurredAt(), List.of(
                        artifactChild(guidelines,
                                "COMPENSATION_GUIDELINES_ARTIFACT_VERSION", 1,
                                "COMPENSATION_CYCLE", cycleId,
                                guidelines.effectiveFrom(), guidelines.effectiveTo()),
                        artifactChild(population,
                                "COMPENSATION_POPULATION_ARTIFACT_VERSION", 2,
                                "COMPENSATION_CYCLE", cycleId,
                                population.effectiveFrom(), population.effectiveTo())));
    }

    CompensationPlanningDtos.RichCommandResult replayRichProposal(
            long tenantId,
            UUID cycleId,
            UUID proposalId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningRepository.ProposalRevisionEvidence evidence = repository
                .proposalRevisionForReceipt(
                        tenantId, replay.receiptId(), cycleId, proposalId)
                .orElseThrow(CompensationPlanningSupport::unavailable);
        PerTypedArtifactQueryPort.TypedArtifact artifact = artifactQuery.query(
                tenantId, evidence.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                "COMPENSATION_PROPOSAL_REVISION", evidence.revisionId(),
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        if (!artifact.sha256().equals(evidence.contentDigest())) {
            throw unavailable();
        }
        return rich(replay.receiptId(), proposalId, replay.aggregateVersion(),
                replay.correlationId(), replay.occurredAt(), List.of(
                        artifactChild(artifact,
                                "COMPENSATION_PROPOSAL_ARTIFACT_VERSION", 1,
                                "COMPENSATION_PROPOSAL_REVISION",
                                evidence.revisionId(), artifact.effectiveFrom(),
                                artifact.effectiveTo()),
                        revisionChild(proposalId, evidence.revisionId(),
                                evidence.revision(), evidence.artifactId(),
                                evidence.contentDigest(), 2)));
    }

    CompensationPlanningDtos.RichCommandResult replayRichCancel(
            long tenantId,
            UUID cycleId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(tenantId, cycleId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<CompensationPlanningRepository.ProposalRow> rows = repository
                .proposals(tenantId, cycle.internalId(), false).stream()
                .filter(value -> "CANCELLED".equals(value.status()))
                .filter(value -> replay.occurredAt().equals(value.updatedAt()))
                .toList();
        List<CompensationPlanningDtos.TypedChildResult> children = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            CompensationPlanningRepository.ProposalRow value = rows.get(index);
            children.add(child("COMPENSATION_PROPOSAL", value.publicId(),
                    value.aggregateVersion(), index + 1,
                    Map.of("postStatus", "CANCELLED")));
        }
        return rich(replay.receiptId(), cycleId, replay.aggregateVersion(),
                replay.correlationId(), replay.occurredAt(), children);
    }

    CompensationPlanningDtos.SnapshotPublished replayPublished(
            long tenantId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningDtos.ApprovedSnapshot snapshot = repository
                .snapshot(tenantId, replay.aggregateId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        canonicalizer.verifySnapshot(snapshot);
        return new CompensationPlanningDtos.SnapshotPublished(
                snapshot.approvalReceiptId(), snapshot.cycleId(), snapshot.snapshotId(),
                snapshot.snapshotRevision(), snapshot.sourceVersion(),
                snapshot.aggregateVersion(), snapshot.lineCount(), snapshot.payloadDigest(),
                snapshot.correlationId(), snapshot.occurredAt());
    }

    void requireCompleted(CompensationPlanningDtos.CommandReceipt replay) {
        if (!"COMPLETED".equals(replay.status())
                || replay.aggregateId() == null
                || replay.aggregateVersion() == null
                || replay.correlationId() == null
                || replay.occurredAt() == null) {
            throw conflict("The idempotent command result is not yet durable.", null);
        }
    }

    CompensationPlanningDtos.CommandReceipt completed(
            UUID receiptId,
            UUID aggregateId,
            Long aggregateVersion,
            UUID correlationId,
            Instant occurredAt,
            String payloadDigest) {
        return new CompensationPlanningDtos.CommandReceipt(
                receiptId, "COMPLETED", aggregateId, aggregateVersion,
                correlationId, occurredAt, payloadDigest);
    }
}
