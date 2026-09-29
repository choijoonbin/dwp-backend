package com.dwp.services.time.workregime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.DiffKind;
import com.dwp.services.time.workregime.WorkRegimeModels.DstOverlapPolicy;
import com.dwp.services.time.workregime.WorkRegimeModels.DstResolution;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyResolution;
import com.dwp.services.time.workregime.WorkRegimeModels.ResolvedSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleDiff;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleTemplate;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationResult;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationState;

/** Deterministic, server-side local-time schedule simulation. */
public final class ScheduleSimulationEngine {

    private static final Comparator<ResolvedSegment> SEGMENT_ORDER = Comparator
            .comparing(ResolvedSegment::localWorkDate)
            .thenComparing(segment -> segment.assignmentId().toString())
            .thenComparing(ResolvedSegment::segmentKey)
            .thenComparing(ResolvedSegment::startAt)
            .thenComparing(ResolvedSegment::endAt);

    public enum RejectionCode {
        UNRESOLVED_POLICY,
        INVALID_RANGE,
        POLICY_OUT_OF_RANGE,
        PACK_OUT_OF_RANGE,
        TENANT_MISMATCH,
        ASSIGNMENT_OUT_OF_RANGE,
        DUPLICATE_SEGMENT_KEY,
        INVALID_INSTANT_RANGE
    }

    /** A fail-closed input rejection; it is never normalized to a successful simulation. */
    public static final class SimulationRejectedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final RejectionCode code;

        SimulationRejectedException(RejectionCode code, String message) {
            super(message);
            this.code = code;
        }

        public RejectionCode code() {
            return code;
        }
    }

    public SimulationResult simulate(
            PolicyResolution resolution,
            List<AssignmentPlan> assignments,
            EffectivePeriod requestedPeriod,
            Instant calculatedAt,
            String tzdbVersion) {
        Objects.requireNonNull(requestedPeriod, "requestedPeriod must not be null");
        if (requestedPeriod.to() == null) {
            throw rejected(RejectionCode.INVALID_RANGE, "simulation range must have an end date");
        }
        return simulate(
                resolution,
                assignments,
                requestedPeriod.from(),
                requestedPeriod.to(),
                calculatedAt,
                tzdbVersion);
    }

    public SimulationResult simulate(
            PolicyResolution resolution,
            List<AssignmentPlan> assignments,
            LocalDate from,
            LocalDate to,
            Instant calculatedAt,
            String tzdbVersion) {
        requireInputs(resolution, assignments, from, to, calculatedAt, tzdbVersion);
        EffectivePeriod range = new EffectivePeriod(from, to);
        if (!resolution.selected().period().contains(range)) {
            throw rejected(RejectionCode.POLICY_OUT_OF_RANGE,
                    "selected policy does not cover the simulation range");
        }
        if (!resolution.rulePack().period().contains(range)) {
            throw rejected(RejectionCode.PACK_OUT_OF_RANGE,
                    "selected rule pack does not cover the simulation range");
        }

        List<AssignmentPlan> orderedAssignments = assignments.stream()
                .sorted(Comparator.comparing(plan -> plan.assignmentId().toString()))
                .toList();
        for (AssignmentPlan assignment : orderedAssignments) {
            if (assignment.tenantId() != resolution.selected().tenantId()) {
                throw rejected(RejectionCode.TENANT_MISMATCH,
                        "assignment tenant does not match the resolved policy tenant");
            }
            if (intersection(assignment.period(), range) == null) {
                throw rejected(RejectionCode.ASSIGNMENT_OUT_OF_RANGE,
                        "assignment does not intersect the simulation range");
            }
        }

        List<String> temporalFindings = new ArrayList<>();
        List<OwnedSegment> current = new ArrayList<>();
        List<OwnedSegment> draft = new ArrayList<>();
        for (AssignmentPlan assignment : orderedAssignments) {
            current.addAll(resolveTemplate(
                    assignment, assignment.currentTemplate(), range, temporalFindings));
            draft.addAll(resolveTemplate(
                    assignment, assignment.draftTemplate(), range, temporalFindings));
        }
        if (!temporalFindings.isEmpty()) {
            return result(
                    SimulationState.BLOCKED,
                    calculatedAt,
                    tzdbVersion,
                    resolution,
                    List.of(),
                    List.of(),
                    temporalFindings);
        }

        List<ResolvedSegment> draftSegments = draft.stream()
                .map(OwnedSegment::segment)
                .sorted(SEGMENT_ORDER)
                .toList();
        List<ScheduleDiff> differences = differences(current, draft);
        List<String> overlapFindings = detectOverlaps(draft);
        return result(
                overlapFindings.isEmpty() ? SimulationState.SUCCEEDED : SimulationState.BLOCKED,
                calculatedAt,
                tzdbVersion,
                resolution,
                draftSegments,
                differences,
                overlapFindings);
    }

    private static List<OwnedSegment> resolveTemplate(
            AssignmentPlan assignment,
            ScheduleTemplate template,
            EffectivePeriod range,
            List<String> findings) {
        List<OwnedSegment> resolved = new ArrayList<>();
        Map<SegmentIdentity, Boolean> identities = new HashMap<>();
        EffectivePeriod activeRange = Objects.requireNonNull(
                intersection(assignment.period(), range),
                "validated assignment must intersect the simulation range");
        for (LocalDate date = activeRange.from();
                date.isBefore(activeRange.to());
                date = date.plusDays(1)) {
            for (LocalSegment local : template.segments()) {
                if (local.dayOfWeek() != date.getDayOfWeek()) continue;
                SegmentIdentity identity = new SegmentIdentity(
                        assignment.assignmentId(), date, local.key());
                if (identities.put(identity, Boolean.TRUE) != null) {
                    throw rejected(RejectionCode.DUPLICATE_SEGMENT_KEY,
                            "segment keys must be unique for an assignment and work date");
                }
                ResolvedSegment segment = resolveSegment(assignment, date, local, findings);
                if (segment != null) {
                    resolved.add(new OwnedSegment(assignment.workerId(), segment));
                }
            }
        }
        return resolved;
    }

    private static EffectivePeriod intersection(
            EffectivePeriod assignment, EffectivePeriod requested) {
        LocalDate from = assignment.from().isAfter(requested.from())
                ? assignment.from() : requested.from();
        LocalDate to = assignment.to() == null || requested.to().isBefore(assignment.to())
                ? requested.to() : assignment.to();
        return to.isAfter(from) ? new EffectivePeriod(from, to) : null;
    }

    private static ResolvedSegment resolveSegment(
            AssignmentPlan assignment,
            LocalDate workDate,
            LocalSegment local,
            List<String> findings) {
        ZoneId zone = ZoneId.of(assignment.zoneId());
        LocalDateTime localStart = workDate.atTime(local.start());
        LocalDateTime localEnd = workDate.plusDays(local.endDayOffset()).atTime(local.end());
        OffsetChoice start = chooseOffset(
                zone.getRules(), localStart, local.overlapPolicy(), assignment, local, "START", findings);
        OffsetChoice end = chooseOffset(
                zone.getRules(), localEnd, local.overlapPolicy(), assignment, local, "END", findings);
        if (start == null || end == null) return null;

        Instant startAt = localStart.toInstant(start.offset());
        Instant endAt = localEnd.toInstant(end.offset());
        Duration duration = Duration.between(startAt, endAt);
        long minutes = duration.toMinutes();
        if (minutes <= 0 || !duration.minusMinutes(minutes).isZero()) {
            throw rejected(RejectionCode.INVALID_INSTANT_RANGE,
                    "resolved segment must have a positive whole-minute instant duration");
        }
        return new ResolvedSegment(
                assignment.assignmentId(),
                local.key(),
                local.kind(),
                workDate,
                startAt,
                endAt,
                assignment.zoneId(),
                start.offset(),
                end.offset(),
                local.endDayOffset() == 1,
                combine(start.resolution(), end.resolution()),
                minutes);
    }

    private static OffsetChoice chooseOffset(
            ZoneRules rules,
            LocalDateTime localDateTime,
            DstOverlapPolicy overlapPolicy,
            AssignmentPlan assignment,
            LocalSegment segment,
            String boundary,
            List<String> findings) {
        List<ZoneOffset> offsets = rules.getValidOffsets(localDateTime);
        if (offsets.isEmpty()) {
            findings.add(finding(
                    "DST_GAP", assignment, segment, localDateTime.toLocalDate(), boundary));
            return null;
        }
        if (offsets.size() == 1) {
            return new OffsetChoice(offsets.getFirst(), DstResolution.EXACT);
        }
        if (overlapPolicy == DstOverlapPolicy.REJECT) {
            findings.add(finding(
                    "DST_FOLD_REQUIRES_POLICY",
                    assignment,
                    segment,
                    localDateTime.toLocalDate(),
                    boundary));
            return null;
        }
        if (overlapPolicy == DstOverlapPolicy.EARLIER) {
            return new OffsetChoice(offsets.getFirst(), DstResolution.FOLD_EARLIER);
        }
        return new OffsetChoice(offsets.getLast(), DstResolution.FOLD_LATER);
    }

    private static String finding(
            String code,
            AssignmentPlan assignment,
            LocalSegment segment,
            LocalDate date,
            String boundary) {
        return code + ":" + assignment.assignmentId() + ":" + date + ":"
                + segment.key() + ":" + boundary;
    }

    private static DstResolution combine(DstResolution start, DstResolution end) {
        if (start == DstResolution.FOLD_LATER || end == DstResolution.FOLD_LATER) {
            return DstResolution.FOLD_LATER;
        }
        if (start == DstResolution.FOLD_EARLIER || end == DstResolution.FOLD_EARLIER) {
            return DstResolution.FOLD_EARLIER;
        }
        return DstResolution.EXACT;
    }

    private static List<ScheduleDiff> differences(
            List<OwnedSegment> current, List<OwnedSegment> draft) {
        Map<SegmentIdentity, ResolvedSegment> currentByKey = index(current);
        Map<SegmentIdentity, ResolvedSegment> draftByKey = index(draft);
        Set<SegmentIdentity> identities = new TreeSet<>();
        identities.addAll(currentByKey.keySet());
        identities.addAll(draftByKey.keySet());

        List<ScheduleDiff> differences = new ArrayList<>();
        for (SegmentIdentity identity : identities) {
            ResolvedSegment before = currentByKey.get(identity);
            ResolvedSegment after = draftByKey.get(identity);
            DiffKind kind = before == null
                    ? DiffKind.ADDED
                    : after == null
                            ? DiffKind.REMOVED
                            : equivalent(before, after) ? DiffKind.UNCHANGED : DiffKind.CHANGED;
            differences.add(new ScheduleDiff(
                    identity.assignmentId(),
                    identity.workDate(),
                    identity.segmentKey(),
                    kind,
                    before == null ? null : before.minutes(),
                    after == null ? null : after.minutes()));
        }
        return List.copyOf(differences);
    }

    private static Map<SegmentIdentity, ResolvedSegment> index(List<OwnedSegment> segments) {
        Map<SegmentIdentity, ResolvedSegment> indexed = new LinkedHashMap<>();
        for (OwnedSegment owned : segments) {
            ResolvedSegment segment = owned.segment();
            SegmentIdentity key = new SegmentIdentity(
                    segment.assignmentId(), segment.localWorkDate(), segment.segmentKey());
            if (indexed.put(key, segment) != null) {
                throw rejected(RejectionCode.DUPLICATE_SEGMENT_KEY,
                        "resolved segment identity must be unique");
            }
        }
        return indexed;
    }

    private static boolean equivalent(ResolvedSegment left, ResolvedSegment right) {
        return left.kind() == right.kind()
                && left.startAt().equals(right.startAt())
                && left.endAt().equals(right.endAt())
                && left.zoneId().equals(right.zoneId())
                && left.startOffset().equals(right.startOffset())
                && left.endOffset().equals(right.endOffset())
                && left.overnight() == right.overnight()
                && left.dstResolution() == right.dstResolution()
                && left.minutes() == right.minutes();
    }

    private static List<String> detectOverlaps(List<OwnedSegment> draft) {
        List<OwnedSegment> ordered = draft.stream()
                .filter(owned -> owned.segment().kind() != SegmentKind.BREAK)
                .sorted(Comparator
                        .comparing((OwnedSegment owned) -> owned.workerId().toString())
                        .thenComparing(owned -> owned.segment().startAt())
                        .thenComparing(owned -> owned.segment().endAt())
                        .thenComparing(owned -> owned.segment().assignmentId().toString())
                        .thenComparing(owned -> owned.segment().segmentKey()))
                .toList();
        Set<String> findings = new TreeSet<>();
        for (int leftIndex = 0; leftIndex < ordered.size(); leftIndex++) {
            OwnedSegment left = ordered.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < ordered.size(); rightIndex++) {
                OwnedSegment right = ordered.get(rightIndex);
                if (!left.workerId().equals(right.workerId())) break;
                if (!right.segment().startAt().isBefore(left.segment().endAt())) break;
                if (left.segment().startAt().isBefore(right.segment().endAt())) {
                    String code = left.segment().assignmentId().equals(
                            right.segment().assignmentId())
                            ? "SCHEDULE_SEGMENT_OVERLAP" : "MULTIPLE_EMPLOYMENT_OVERLAP";
                    findings.add(code + ":"
                            + left.workerId() + ":"
                            + left.segment().localWorkDate() + ":"
                            + left.segment().assignmentId() + ":" + left.segment().segmentKey() + ":"
                            + right.segment().localWorkDate() + ":"
                            + right.segment().assignmentId() + ":" + right.segment().segmentKey());
                }
            }
        }
        return List.copyOf(findings);
    }

    private static SimulationResult result(
            SimulationState state,
            Instant calculatedAt,
            String tzdbVersion,
            PolicyResolution resolution,
            List<ResolvedSegment> segments,
            List<ScheduleDiff> differences,
            List<String> findings) {
        return new SimulationResult(
                state,
                calculatedAt,
                tzdbVersion,
                resolution.selected().revision(),
                segments,
                differences,
                findings.stream().distinct().sorted().toList());
    }

    private static void requireInputs(
            PolicyResolution resolution,
            List<AssignmentPlan> assignments,
            LocalDate from,
            LocalDate to,
            Instant calculatedAt,
            String tzdbVersion) {
        if (resolution == null || !resolution.resolved()) {
            throw rejected(RejectionCode.UNRESOLVED_POLICY,
                    "simulation requires a resolved policy and rule pack");
        }
        Objects.requireNonNull(assignments, "assignments must not be null");
        if (assignments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("assignments must not contain nulls");
        }
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (!to.isAfter(from)) {
            throw rejected(RejectionCode.INVALID_RANGE,
                    "simulation range must be non-empty and half-open");
        }
        Objects.requireNonNull(calculatedAt, "calculatedAt must not be null");
        WorkRegimeModels.requireText(tzdbVersion, "tzdbVersion");
    }

    private static SimulationRejectedException rejected(RejectionCode code, String message) {
        return new SimulationRejectedException(code, message);
    }

    private record OffsetChoice(ZoneOffset offset, DstResolution resolution) {
    }

    private record OwnedSegment(UUID workerId, ResolvedSegment segment) {
    }

    private record SegmentIdentity(UUID assignmentId, LocalDate workDate, String segmentKey)
            implements Comparable<SegmentIdentity> {
        @Override
        public int compareTo(SegmentIdentity other) {
            int assignment = assignmentId.toString().compareTo(other.assignmentId.toString());
            if (assignment != 0) return assignment;
            int date = workDate.compareTo(other.workDate);
            if (date != 0) return date;
            return segmentKey.compareTo(other.segmentKey);
        }
    }
}
