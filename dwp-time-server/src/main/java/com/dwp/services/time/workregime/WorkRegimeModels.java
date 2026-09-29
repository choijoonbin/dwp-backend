package com.dwp.services.time.workregime;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed, tenant-safe value model for the BASE-TFR-TIM-016 authoring slice. */
public final class WorkRegimeModels {

    private WorkRegimeModels() {
    }

    public enum ArrangementKind {
        FIXED,
        FLEX,
        AVERAGED,
        SELECTIVE,
        COMPRESSED,
        PART_TIME,
        REDUCED,
        SPLIT_SHIFT,
        SHIFT,
        ON_CALL,
        DEEMED,
        DISCRETIONARY,
        TENANT_EXTENSION
    }

    public enum PolicyExtensionKind {
        FLEXIBLE,
        ELASTIC,
        AVERAGED,
        SELECTIVE,
        DISCRETIONARY,
        DEEMED,
        REDUCED,
        SHIFT,
        SPLIT_SHIFT,
        ON_CALL,
        OVERTIME,
        BREAK,
        WEEKLY_LIMIT
    }

    public enum ScopeType {
        GLOBAL(100),
        COUNTRY(200),
        SUBDIVISION(300),
        TENANT(400),
        LEGAL_ENTITY(500),
        BUSINESS_UNIT(600),
        WORKPLACE(700),
        POPULATION(800),
        PERSON(900),
        ASSIGNMENT(1_000);

        private final int specificity;

        ScopeType(int specificity) {
            this.specificity = specificity;
        }

        public int specificity() {
            return specificity;
        }
    }

    public enum PolicyState {
        DRAFT,
        VALIDATED,
        SIMULATED,
        IN_REVIEW,
        APPROVED,
        PUBLISHED,
        SUPERSEDED,
        RETIRED,
        REJECTED
    }

    public enum RulePackState {
        STAGED,
        VALIDATED,
        PUBLISHED,
        SUPERSEDED,
        RETIRED,
        REVOKED
    }

    public enum ResolutionCode {
        RESOLVED,
        NO_APPLICABLE_POLICY,
        POLICY_OVERLAP,
        PACK_MISSING,
        PACK_EXPIRED,
        PACK_REVOKED,
        PACK_INVALID,
        PACK_CONFLICT,
        STALE_POLICY
    }

    public enum SegmentKind {
        WORK,
        BREAK,
        ON_CALL,
        TRAINING
    }

    public enum DstOverlapPolicy {
        REJECT,
        EARLIER,
        LATER
    }

    public enum DstResolution {
        EXACT,
        GAP_REJECTED,
        FOLD_EARLIER,
        FOLD_LATER
    }

    public enum DiffKind {
        ADDED,
        CHANGED,
        REMOVED,
        UNCHANGED
    }

    public enum SimulationState {
        SUCCEEDED,
        BLOCKED
    }

    public enum LifecycleAction {
        CREATE_DRAFT,
        REVISE_DRAFT,
        VALIDATE,
        SIMULATE,
        ASSIGN,
        SUBMIT_REVIEW,
        APPLY_APPROVAL,
        PUBLISH
    }

    public enum Duty {
        TIME_CONFIG_AUTHOR,
        TIME_CONFIG_APPROVER,
        TIME_OPERATOR,
        TIME_AUDITOR
    }

    public enum ReceiptState {
        ACCEPTED,
        RUNNING,
        SUCCEEDED,
        REJECTED,
        FAILED,
        RESULT_UNKNOWN,
        RECONCILING
    }

    public record EffectivePeriod(LocalDate from, LocalDate to) {
        public EffectivePeriod {
            Objects.requireNonNull(from, "from must not be null");
            if (to != null && !to.isAfter(from)) {
                throw new IllegalArgumentException("effective period must be non-empty and half-open");
            }
        }

        public boolean contains(LocalDate date) {
            Objects.requireNonNull(date, "date must not be null");
            return !date.isBefore(from) && (to == null || date.isBefore(to));
        }

        public boolean contains(EffectivePeriod period) {
            Objects.requireNonNull(period, "period must not be null");
            if (!contains(period.from)) return false;
            if (period.to == null) return to == null;
            return to == null || !period.to.isAfter(to);
        }
    }

    public record RulePack(
            long tenantId,
            UUID publicId,
            String jurisdiction,
            long policyRevision,
            RulePackState state,
            EffectivePeriod period,
            boolean signatureVerified,
            String mandatoryBoundDigest) {
        public RulePack {
            requireTenant(tenantId);
            Objects.requireNonNull(publicId, "publicId must not be null");
            jurisdiction = requireText(jurisdiction, "jurisdiction");
            requireRevision(policyRevision, "policyRevision");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(period, "period must not be null");
            mandatoryBoundDigest = requireDigest(mandatoryBoundDigest, "mandatoryBoundDigest");
        }
    }

    public record PolicyCandidate(
            long tenantId,
            UUID publicId,
            long revision,
            ArrangementKind arrangementKind,
            ScopeType scopeType,
            String scopeRef,
            int priority,
            EffectivePeriod period,
            UUID rulePackPublicId,
            String jurisdiction,
            long rulePackRevision,
            PolicyState state,
            long authorActorId,
            String artifactDigest) {
        public PolicyCandidate {
            requireTenant(tenantId);
            Objects.requireNonNull(publicId, "publicId must not be null");
            requireRevision(revision, "revision");
            Objects.requireNonNull(arrangementKind, "arrangementKind must not be null");
            Objects.requireNonNull(scopeType, "scopeType must not be null");
            scopeRef = requireText(scopeRef, "scopeRef");
            Objects.requireNonNull(period, "period must not be null");
            Objects.requireNonNull(rulePackPublicId, "rulePackPublicId must not be null");
            jurisdiction = requireText(jurisdiction, "jurisdiction");
            requireRevision(rulePackRevision, "rulePackRevision");
            Objects.requireNonNull(state, "state must not be null");
            if (authorActorId <= 0) throw new IllegalArgumentException("authorActorId must be positive");
            artifactDigest = requireDigest(artifactDigest, "artifactDigest");
        }
    }

    public record PolicyResolution(
            ResolutionCode code,
            PolicyCandidate selected,
            RulePack rulePack,
            List<UUID> consideredPolicyIds) {
        public PolicyResolution {
            Objects.requireNonNull(code, "code must not be null");
            consideredPolicyIds = List.copyOf(consideredPolicyIds);
            if ((code == ResolutionCode.RESOLVED) != (selected != null && rulePack != null)) {
                throw new IllegalArgumentException("resolved policy evidence is inconsistent");
            }
        }

        public boolean resolved() {
            return code == ResolutionCode.RESOLVED;
        }
    }

    public record LocalSegment(
            String key,
            DayOfWeek dayOfWeek,
            SegmentKind kind,
            LocalTime start,
            LocalTime end,
            int endDayOffset,
            DstOverlapPolicy overlapPolicy) {
        public LocalSegment {
            key = requireText(key, "segment key");
            Objects.requireNonNull(dayOfWeek, "dayOfWeek must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(start, "start must not be null");
            Objects.requireNonNull(end, "end must not be null");
            Objects.requireNonNull(overlapPolicy, "overlapPolicy must not be null");
            if (start.getSecond() != 0 || start.getNano() != 0
                    || end.getSecond() != 0 || end.getNano() != 0) {
                throw new IllegalArgumentException("segment boundaries must use exact minutes");
            }
            if (endDayOffset < 0 || endDayOffset > 1) {
                throw new IllegalArgumentException("endDayOffset must be zero or one");
            }
            if (endDayOffset == 0 && !end.isAfter(start)) {
                throw new IllegalArgumentException("same-day segment must end after it starts");
            }
        }
    }

    public record ScheduleTemplate(String templateId, long revision, List<LocalSegment> segments) {
        public ScheduleTemplate {
            templateId = requireText(templateId, "templateId");
            requireRevision(revision, "template revision");
            segments = List.copyOf(segments);
        }
    }

    public record AssignmentPlan(
            long tenantId,
            UUID assignmentId,
            UUID workerId,
            long peopleAssignmentRevision,
            EffectivePeriod period,
            String zoneId,
            ScheduleTemplate currentTemplate,
            ScheduleTemplate draftTemplate) {
        public AssignmentPlan {
            requireTenant(tenantId);
            Objects.requireNonNull(assignmentId, "assignmentId must not be null");
            Objects.requireNonNull(workerId, "workerId must not be null");
            if (peopleAssignmentRevision < 0) {
                throw new IllegalArgumentException("peopleAssignmentRevision must not be negative");
            }
            Objects.requireNonNull(period, "period must not be null");
            zoneId = requireText(zoneId, "zoneId");
            ZoneId parsedZone = ZoneId.of(zoneId);
            if (parsedZone instanceof ZoneOffset) {
                throw new IllegalArgumentException("zoneId must be an IANA region, not a fixed offset");
            }
            Objects.requireNonNull(currentTemplate, "currentTemplate must not be null");
            Objects.requireNonNull(draftTemplate, "draftTemplate must not be null");
        }
    }

    public record ResolvedSegment(
            UUID assignmentId,
            String segmentKey,
            SegmentKind kind,
            LocalDate localWorkDate,
            Instant startAt,
            Instant endAt,
            String zoneId,
            ZoneOffset startOffset,
            ZoneOffset endOffset,
            boolean overnight,
            DstResolution dstResolution,
            long minutes) {
    }

    public record ScheduleDiff(
            UUID assignmentId,
            LocalDate localWorkDate,
            String segmentKey,
            DiffKind kind,
            Long currentMinutes,
            Long draftMinutes) {
    }

    public record SimulationResult(
            SimulationState state,
            Instant calculatedAt,
            String tzdbVersion,
            long policyRevision,
            List<ResolvedSegment> segments,
            List<ScheduleDiff> differences,
            List<String> findings) {
        public SimulationResult {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(calculatedAt, "calculatedAt must not be null");
            tzdbVersion = requireText(tzdbVersion, "tzdbVersion");
            requireRevision(policyRevision, "policyRevision");
            segments = List.copyOf(segments);
            differences = List.copyOf(differences);
            findings = List.copyOf(findings);
        }
    }

    public record WorkRegimeRevision(
            long tenantId,
            UUID publicId,
            long revision,
            long version,
            PolicyState state,
            long authorActorId,
            Long approvalActorId,
            String scopeRef,
            EffectivePeriod period,
            String artifactDigest) {
        public WorkRegimeRevision {
            requireTenant(tenantId);
            Objects.requireNonNull(publicId, "publicId must not be null");
            requireRevision(revision, "revision");
            requireRevision(version, "version");
            Objects.requireNonNull(state, "state must not be null");
            if (authorActorId <= 0) throw new IllegalArgumentException("authorActorId must be positive");
            scopeRef = requireText(scopeRef, "scopeRef");
            Objects.requireNonNull(period, "period must not be null");
            artifactDigest = requireDigest(artifactDigest, "artifactDigest");
        }
    }

    public record Authority(
            long tenantId,
            long actorId,
            Set<Duty> duties,
            Set<String> scopeRefs,
            String purpose,
            String decisionId,
            boolean stepUpSatisfied,
            boolean revoked) {
        public Authority {
            requireTenant(tenantId);
            if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
            duties = Set.copyOf(duties);
            scopeRefs = Set.copyOf(scopeRefs);
            purpose = requireText(purpose, "purpose");
            decisionId = requireText(decisionId, "decisionId");
        }
    }

    public record CommandReceipt(
            long tenantId,
            UUID receiptId,
            UUID idempotencyKey,
            LifecycleAction operation,
            UUID aggregateId,
            String scopePublicRef,
            Long expectedVersion,
            String requestDigest,
            long actorId,
            String purpose,
            ReceiptState state,
            String resultCode,
            String resultDigest,
            Instant updatedAt) {
        public CommandReceipt {
            requireTenant(tenantId);
            Objects.requireNonNull(receiptId, "receiptId must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            scopePublicRef = requireBoundedText(scopePublicRef, "scopePublicRef", 128);
            if (expectedVersion != null && expectedVersion <= 0) {
                throw new IllegalArgumentException("expectedVersion must be positive when supplied");
            }
            if ((operation == LifecycleAction.CREATE_DRAFT) != (expectedVersion == null)) {
                throw new IllegalArgumentException(
                        "only CREATE_DRAFT may omit expectedVersion");
            }
            requestDigest = requireDigest(requestDigest, "requestDigest");
            if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
            purpose = requireText(purpose, "purpose");
            Objects.requireNonNull(state, "state must not be null");
            resultCode = resultCode == null ? null : requireText(resultCode, "resultCode");
            resultDigest = resultDigest == null ? null : requireDigest(resultDigest, "resultDigest");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }

        public CommandReceipt withState(
                ReceiptState next, String nextResultCode, String nextResultDigest, Instant at) {
            return new CommandReceipt(
                    tenantId, receiptId, idempotencyKey, operation, aggregateId,
                    scopePublicRef, expectedVersion, requestDigest, actorId, purpose,
                    next, nextResultCode, nextResultDigest, at);
        }
    }

    static String requireBoundedText(String value, String label, int maximumLength) {
        String candidate = requireText(value, label);
        if (candidate.length() > maximumLength
                || candidate.indexOf('\r') >= 0
                || candidate.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " must be at most "
                    + maximumLength + " characters without line breaks");
        }
        return candidate;
    }

    static String requireDigest(String value, String label) {
        String candidate = requireText(value, label);
        if (!candidate.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
        }
        return candidate;
    }

    static String requireText(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must be canonical text");
        }
        return value;
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private static void requireRevision(long revision, String label) {
        if (revision < 1) throw new IllegalArgumentException(label + " must be at least one");
    }
}
