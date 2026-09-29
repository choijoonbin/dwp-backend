package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
final class PerformanceParticipantPreviewService {

    private static final int PAGE_LIMIT = WorkforceSnapshotQuery.MAX_LIMIT;
    private static final int MAX_PAGES = 10_000;

    private final Supplier<WorkforceSnapshotQueryPort> portSupplier;
    private final Supplier<PerformancePopulationRuleEvaluator> ruleSupplier;

    @org.springframework.beans.factory.annotation.Autowired
    PerformanceParticipantPreviewService(
            ObjectProvider<WorkforceSnapshotQueryPort> provider,
            ObjectProvider<PerformancePopulationRuleEvaluator> ruleProvider) {
        this(provider::getIfAvailable, ruleProvider::getIfAvailable);
    }

    PerformanceParticipantPreviewService(
            Supplier<WorkforceSnapshotQueryPort> portSupplier,
            Supplier<PerformancePopulationRuleEvaluator> ruleSupplier) {
        this.portSupplier = portSupplier;
        this.ruleSupplier = ruleSupplier;
    }

    PreparedPreview prepare(long tenantId, Instant asOf, UUID populationRuleVersionId) {
        WorkforceSnapshotQueryPort port = portSupplier.get();
        PerformancePopulationRuleEvaluator ruleEvaluator = ruleSupplier.get();
        if (port == null || ruleEvaluator == null || populationRuleVersionId == null) {
            throw unavailable(null);
        }
        List<WorkforceSnapshotV1> snapshots = new ArrayList<>();
        List<String> ownerPageDigests = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        Long ownerRevision = null;
        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                WorkforceSnapshotPage page = port.query(new WorkforceSnapshotQuery(
                        WorkforceSnapshotQuery.PURPOSE_CODE,
                        asOf,
                        WorkforceSnapshotProjection.PERFORMANCE_V1,
                        PAGE_LIMIT,
                        cursor));
                if (page.tenantId() != tenantId || !asOf.equals(page.asOf())) {
                    throw new IllegalStateException(
                            "The workforce owner returned a page outside the trusted tenant snapshot.");
                }
                if (ownerRevision == null) {
                    ownerRevision = page.ownerRevision();
                } else if (ownerRevision.longValue() != page.ownerRevision()) {
                    throw new IllegalStateException(
                            "The workforce owner revision changed during pagination.");
                }
                for (WorkforceSnapshotV1 snapshot : page.snapshots()) {
                    snapshots.add(snapshot);
                }
                ownerPageDigests.add(page.payloadDigest());
                cursor = page.nextCursor();
                if (cursor == null) break;
                if (!cursors.add(cursor)) {
                    throw new IllegalStateException("The workforce owner repeated a cursor.");
                }
                if (pageNumber == MAX_PAGES - 1) {
                    throw new IllegalStateException("The workforce snapshot exceeded the page limit.");
                }
            }
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (ownerRevision == null) {
            throw unavailable(null);
        }

        PerformancePopulationRuleEvaluator.PopulationRuleEvaluation evaluation;
        try {
            evaluation = ruleEvaluator.evaluate(
                    new PerformancePopulationRuleEvaluator.PopulationRuleRequest(
                            tenantId, populationRuleVersionId, asOf, snapshots));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        validateEvaluation(populationRuleVersionId, asOf, snapshots, evaluation);
        Map<String, WorkforceSnapshotV1> byAssignment = new HashMap<>();
        for (WorkforceSnapshotV1 snapshot : snapshots) {
            if (byAssignment.put(key(snapshot.workerPublicId(), snapshot.assignmentPublicId()),
                    snapshot) != null) {
                throw unavailable(null);
            }
        }
        List<PerformanceCycleDtos.PreviewMember> members = evaluation.decisions().stream()
                .map(decision -> member(byAssignment.get(key(
                        decision.workerPublicId(), decision.assignmentPublicId())),
                        decision.eligibilityCode()))
                .sorted(Comparator
                        .comparing((PerformanceCycleDtos.PreviewMember value) ->
                                value.primaryAssignmentRef().toString())
                        .thenComparing(value -> value.participantRef().toString()))
                .toList();
        String canonical = members.stream()
                .map(value -> value.participantRef() + "|" + value.primaryAssignmentRef()
                        + "|" + value.workforceStatus()
                        + "|" + value.organizationRef()
                        + "|" + nullable(value.reviewerAssignmentRef())
                        + "|" + nullable(value.jobProfileRef())
                        + "|" + nullable(value.gradeRef())
                        + "|" + value.eligibilityCode())
                .reduce("", (left, right) -> left + right + "\n");
        String contentHash = PerformanceCycleCanonicalizer.sha256(canonical);
        String ownerDigest = PerformanceCycleCanonicalizer.sha256(
                String.join("|", ownerPageDigests));
        UUID snapshotId = UUID.nameUUIDFromBytes((
                "HRIS-PER|" + tenantId + "|" + asOf + "|" + ownerRevision
                        + "|" + ownerDigest)
                .getBytes(StandardCharsets.UTF_8));
        return new PreparedPreview(
                snapshotId, ownerRevision, populationRuleVersionId, contentHash, members);
    }

    private static PerformanceCycleDtos.PreviewMember member(
            WorkforceSnapshotV1 snapshot,
            String eligibility) {
        return new PerformanceCycleDtos.PreviewMember(
                snapshot.workerPublicId(),
                snapshot.assignmentPublicId(),
                snapshot.status(),
                snapshot.organizationPublicId(),
                snapshot.managerAssignmentPublicId(),
                snapshot.jobProfilePublicId(),
                snapshot.gradePublicId(),
                eligibility);
    }

    private void validateEvaluation(
            UUID requestedRuleVersion,
            Instant asOf,
            List<WorkforceSnapshotV1> snapshots,
            PerformancePopulationRuleEvaluator.PopulationRuleEvaluation evaluation) {
        if (evaluation == null
                || !requestedRuleVersion.equals(evaluation.populationRuleVersionId())
                || evaluation.effectiveFrom() == null
                || asOf.isBefore(evaluation.effectiveFrom())
                || (evaluation.effectiveTo() != null && !asOf.isBefore(evaluation.effectiveTo()))) {
            throw unavailable(null);
        }
        Set<String> ownerKeys = new HashSet<>();
        for (WorkforceSnapshotV1 snapshot : snapshots) {
            if (!ownerKeys.add(key(
                    snapshot.workerPublicId(), snapshot.assignmentPublicId()))) {
                throw unavailable(null);
            }
        }
        Set<String> decidedKeys = new HashSet<>();
        for (PerformancePopulationRuleEvaluator.PopulationDecision decision : evaluation.decisions()) {
            if (decision == null || decision.workerPublicId() == null
                    || decision.assignmentPublicId() == null
                    || !Set.of("INCLUDED", "EXCLUDED", "REVIEW_REQUIRED")
                    .contains(decision.eligibilityCode())) {
                throw unavailable(null);
            }
            String decisionKey = key(
                    decision.workerPublicId(), decision.assignmentPublicId());
            if (!ownerKeys.contains(decisionKey) || !decidedKeys.add(decisionKey)) {
                throw unavailable(null);
            }
        }
        if (!decidedKeys.equals(ownerKeys)) {
            throw unavailable(null);
        }
    }

    private static String key(UUID workerId, UUID assignmentId) {
        return workerId + ":" + assignmentId;
    }

    private BaseException unavailable(Throwable cause) {
        String message = "The authoritative workforce snapshot is unavailable; preview is denied.";
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message)
                : new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }

    private static String nullable(Object value) {
        return value == null ? "" : value.toString();
    }

    record PreparedPreview(
            UUID workforceSnapshotId,
            long workforceSnapshotRevision,
            UUID populationRuleVersionId,
            String contentHash,
            List<PerformanceCycleDtos.PreviewMember> members) {

        PreparedPreview {
            members = List.copyOf(new ArrayList<>(members));
        }

        int participantCount() {
            return (int) members.stream()
                    .filter(member -> !"EXCLUDED".equals(member.eligibilityCode()))
                    .count();
        }

        int reviewerAssignmentCount() {
            return (int) members.stream()
                    .filter(member -> !"EXCLUDED".equals(member.eligibilityCode()))
                    .map(PerformanceCycleDtos.PreviewMember::reviewerAssignmentRef)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
        }
    }
}
