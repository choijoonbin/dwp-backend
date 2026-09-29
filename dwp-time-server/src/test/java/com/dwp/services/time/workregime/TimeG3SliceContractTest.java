package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dwp.services.time.workregime.ScheduleSimulationEngine.RejectionCode;
import com.dwp.services.time.workregime.ScheduleSimulationEngine.SimulationRejectedException;
import com.dwp.services.time.workregime.WorkRegimeApiModels.TenantExtensionFieldInput;
import com.dwp.services.time.workregime.WorkRegimeApiModels.TenantExtensionInput;
import com.dwp.services.time.workregime.WorkRegimeApiModels.TenantExtensionValueType;
import com.dwp.services.time.workregime.WorkRegimeLifecycleGuard.DenialCode;
import com.dwp.services.time.workregime.WorkRegimeLifecycleGuard.LifecycleDeniedException;
import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.DiffKind;
import com.dwp.services.time.workregime.WorkRegimeModels.DstOverlapPolicy;
import com.dwp.services.time.workregime.WorkRegimeModels.DstResolution;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyResolution;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePackState;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleTemplate;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationResult;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationState;
import com.dwp.services.time.workregime.WorkRegimeModels.ScopeType;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;
import org.junit.jupiter.api.Test;

class TimeG3SliceContractTest {

    private static final long TENANT = 41L;
    private static final long OTHER_TENANT = 42L;
    private static final long AUTHOR = 101L;
    private static final long APPROVER = 202L;
    private static final long PUBLISHER = 303L;
    private static final String JURISDICTION = "KR";
    private static final String OTHER_JURISDICTION = "US";
    private static final String SCOPE = "TENANT:41";
    private static final String DIGEST = "a".repeat(64);
    private static final String TZDB_VERSION = "tzdb-test-fixture";
    private static final Instant CALCULATED_AT = Instant.parse("2026-09-17T08:00:00Z");
    private static final LocalDate MONDAY = LocalDate.parse("2026-09-14");
    private static final EffectivePeriod YEAR = new EffectivePeriod(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"));
    private static final UUID PACK_ID = uuid("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PACK_ID = uuid("10000000-0000-0000-0000-000000000002");
    private static final UUID POLICY_ID = uuid("20000000-0000-0000-0000-000000000001");
    private static final UUID WORKER_ID = uuid("30000000-0000-0000-0000-000000000001");

    private final WorkPolicyResolver resolver = new WorkPolicyResolver();
    private final ScheduleSimulationEngine simulation = new ScheduleSimulationEngine();
    private final WorkRegimeLifecycleGuard lifecycle = new WorkRegimeLifecycleGuard();

    @Test
    void tenantExtensionsAcceptOnlyUniqueExactlyTypedFields() {
        var threshold = new TenantExtensionFieldInput(
                "eligibility.threshold", TenantExtensionValueType.INTEGER,
                null, 3L, null, null, null);
        var extension = new TenantExtensionInput(
                "tenant.example/work-pattern", 1, List.of(threshold));

        assertThat(extension.fields()).containsExactly(threshold);
        assertThrows(IllegalArgumentException.class, () -> new TenantExtensionFieldInput(
                "eligibility.threshold", TenantExtensionValueType.BOOLEAN,
                null, 3L, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TenantExtensionFieldInput(
                "eligibility.note", TenantExtensionValueType.STRING,
                "x".repeat(1_001), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TenantExtensionInput(
                "tenant.example/work-pattern", 1, List.of(threshold, threshold)));
    }

    @Test
    void arrangementCatalogIsTheExactClosedThirteenValueSet() {
        assertThat(ArrangementKind.values()).extracting(Enum::name).containsExactly(
                "FIXED",
                "FLEX",
                "AVERAGED",
                "SELECTIVE",
                "COMPRESSED",
                "PART_TIME",
                "REDUCED",
                "SPLIT_SHIFT",
                "SHIFT",
                "ON_CALL",
                "DEEMED",
                "DISCRETIONARY",
                "TENANT_EXTENSION");
    }

    @Test
    void resolverUsesEffectivePrecedenceAndExcludesOtherTenantPackAndJurisdiction() {
        RulePack selectedPack = pack(PACK_ID, TENANT, JURISDICTION, RulePackState.PUBLISHED, YEAR, true);
        PolicyCandidate global = candidate(
                uuid("20000000-0000-0000-0000-000000000010"), TENANT, 3L,
                ScopeType.GLOBAL, "GLOBAL", 10, YEAR, PACK_ID, JURISDICTION, PolicyState.PUBLISHED);
        PolicyCandidate selected = candidate(
                POLICY_ID, TENANT, 7L, ScopeType.ASSIGNMENT, "ASSIGNMENT:A", 20,
                YEAR, PACK_ID, JURISDICTION, PolicyState.PUBLISHED);
        PolicyCandidate lowerPriority = candidate(
                uuid("20000000-0000-0000-0000-000000000005"), TENANT, 99L,
                ScopeType.ASSIGNMENT, "ASSIGNMENT:A", 10,
                YEAR, PACK_ID, JURISDICTION, PolicyState.PUBLISHED);
        PolicyCandidate otherTenant = candidate(
                uuid("20000000-0000-0000-0000-000000000020"), OTHER_TENANT, 99L,
                ScopeType.ASSIGNMENT, "ASSIGNMENT:A", 99,
                YEAR, PACK_ID, JURISDICTION, PolicyState.PUBLISHED);
        PolicyCandidate wrongJurisdiction = candidate(
                uuid("20000000-0000-0000-0000-000000000030"), TENANT, 99L,
                ScopeType.ASSIGNMENT, "ASSIGNMENT:A", 99,
                YEAR, PACK_ID, OTHER_JURISDICTION, PolicyState.PUBLISHED);
        PolicyCandidate wrongPack = candidate(
                uuid("20000000-0000-0000-0000-000000000040"), TENANT, 99L,
                ScopeType.ASSIGNMENT, "ASSIGNMENT:A", 99,
                YEAR, OTHER_PACK_ID, JURISDICTION, PolicyState.PUBLISHED);

        PolicyResolution result = resolver.resolve(
                TENANT,
                JURISDICTION,
                MONDAY,
                1L,
                7L,
                Set.of("ASSIGNMENT:A"),
                List.of(
                        selectedPack,
                        pack(OTHER_PACK_ID, OTHER_TENANT, JURISDICTION,
                                RulePackState.PUBLISHED, YEAR, true)),
                List.of(
                        global,
                        otherTenant,
                        wrongJurisdiction,
                        wrongPack,
                        lowerPriority,
                        selected));

        assertThat(result.code()).isEqualTo(ResolutionCode.RESOLVED);
        assertThat(result.selected()).isEqualTo(selected);
        assertThat(result.rulePack()).isEqualTo(selectedPack);
        assertThat(result.consideredPolicyIds()).containsExactly(
                selected.publicId(), lowerPriority.publicId(), global.publicId());
    }

    @Test
    void resolverTreatsEffectivePeriodsAsHalfOpen() {
        EffectivePeriod policyPeriod = new EffectivePeriod(MONDAY.minusDays(1), MONDAY);
        PolicyCandidate ended = candidate(
                POLICY_ID, TENANT, 1L, ScopeType.TENANT, SCOPE, 1,
                policyPeriod, PACK_ID, JURISDICTION, PolicyState.PUBLISHED);

        PolicyResolution result = resolve(
                MONDAY, 1L, List.of(pack()), List.of(ended), Set.of(SCOPE));

        assertThat(result.code()).isEqualTo(ResolutionCode.NO_APPLICABLE_POLICY);
    }

    @Test
    void resolverReturnsEveryFailClosedPackAndPolicyCode() {
        PolicyCandidate published = candidate(
                POLICY_ID, TENANT, 5L, ScopeType.TENANT, SCOPE, 1,
                YEAR, PACK_ID, JURISDICTION, PolicyState.PUBLISHED);

        assertThat(resolve(MONDAY, 5L, List.of(), List.of(published), Set.of(SCOPE)).code())
                .isEqualTo(ResolutionCode.PACK_MISSING);
        assertThat(resolve(
                MONDAY,
                5L,
                List.of(pack(PACK_ID, TENANT, JURISDICTION, RulePackState.PUBLISHED,
                        new EffectivePeriod(MONDAY.minusDays(2), MONDAY), true)),
                List.of(published),
                Set.of(SCOPE)).code()).isEqualTo(ResolutionCode.PACK_EXPIRED);
        assertThat(resolve(
                MONDAY,
                5L,
                List.of(pack(PACK_ID, TENANT, JURISDICTION, RulePackState.REVOKED, YEAR, true)),
                List.of(published),
                Set.of(SCOPE)).code()).isEqualTo(ResolutionCode.PACK_REVOKED);
        assertThat(resolve(
                MONDAY,
                5L,
                List.of(pack(PACK_ID, TENANT, JURISDICTION, RulePackState.PUBLISHED, YEAR, false)),
                List.of(published),
                Set.of(SCOPE)).code()).isEqualTo(ResolutionCode.PACK_INVALID);
        assertThat(resolve(
                MONDAY,
                5L,
                List.of(pack(), pack(OTHER_PACK_ID, TENANT, JURISDICTION,
                        RulePackState.PUBLISHED, YEAR, true)),
                List.of(published),
                Set.of(SCOPE)).code()).isEqualTo(ResolutionCode.PACK_CONFLICT);
        assertThat(resolve(MONDAY, 5L, List.of(pack()), List.of(), Set.of(SCOPE)).code())
                .isEqualTo(ResolutionCode.NO_APPLICABLE_POLICY);

        PolicyCandidate overlapping = candidate(
                uuid("20000000-0000-0000-0000-000000000099"), TENANT, 5L,
                ScopeType.TENANT, SCOPE, 1, YEAR, PACK_ID, JURISDICTION, PolicyState.PUBLISHED);
        assertThat(resolve(
                MONDAY, 5L, List.of(pack()), List.of(published, overlapping), Set.of(SCOPE)).code())
                .isEqualTo(ResolutionCode.POLICY_OVERLAP);
        assertThat(resolve(MONDAY, 4L, List.of(pack()), List.of(published), Set.of(SCOPE)).code())
                .isEqualTo(ResolutionCode.STALE_POLICY);
    }

    @Test
    void simulationComputesTimezoneOvernightAndDeterministicCurrentDraftDiff() {
        LocalSegment same = segment("same", DayOfWeek.MONDAY, 8, 0, 9, 0, 0,
                DstOverlapPolicy.REJECT);
        LocalSegment changedBefore = segment("changed", DayOfWeek.MONDAY, 9, 0, 10, 0, 0,
                DstOverlapPolicy.REJECT);
        LocalSegment changedAfter = segment("changed", DayOfWeek.MONDAY, 9, 0, 11, 0, 0,
                DstOverlapPolicy.REJECT);
        LocalSegment removed = segment("removed", DayOfWeek.MONDAY, 11, 0, 12, 0, 0,
                DstOverlapPolicy.REJECT);
        LocalSegment added = segment("added", DayOfWeek.MONDAY, 12, 0, 13, 0, 0,
                DstOverlapPolicy.REJECT);
        AssignmentPlan diffPlan = assignment(
                uuid("40000000-0000-0000-0000-000000000001"),
                WORKER_ID,
                "Asia/Seoul",
                template("current-diff", same, changedBefore, removed),
                template("draft-diff", same, changedAfter, added),
                YEAR);

        SimulationResult first = simulation.simulate(
                resolved(), List.of(diffPlan), MONDAY, MONDAY.plusDays(1),
                CALCULATED_AT, TZDB_VERSION);
        SimulationResult replay = simulation.simulate(
                resolved(), List.of(diffPlan), MONDAY, MONDAY.plusDays(1),
                CALCULATED_AT, TZDB_VERSION);

        assertThat(first).isEqualTo(replay);
        assertThat(first.state()).isEqualTo(SimulationState.SUCCEEDED);
        assertThat(first.segments()).allSatisfy(segment -> {
            assertThat(segment.zoneId()).isEqualTo("Asia/Seoul");
            assertThat(segment.startOffset()).isEqualTo(ZoneOffset.ofHours(9));
        });
        assertThat(first.differences())
                .extracting(diff -> diff.segmentKey() + ":" + diff.kind())
                .containsExactly(
                        "added:" + DiffKind.ADDED,
                        "changed:" + DiffKind.CHANGED,
                        "removed:" + DiffKind.REMOVED,
                        "same:" + DiffKind.UNCHANGED);

        LocalSegment overnight = segment("overnight", DayOfWeek.MONDAY, 22, 0, 6, 0, 1,
                DstOverlapPolicy.REJECT);
        AssignmentPlan overnightPlan = assignment(
                uuid("40000000-0000-0000-0000-000000000002"),
                WORKER_ID,
                "Asia/Seoul",
                template("overnight-current", overnight),
                template("overnight-draft", overnight),
                YEAR);
        SimulationResult overnightResult = simulation.simulate(
                resolved(), List.of(overnightPlan), MONDAY, MONDAY.plusDays(1),
                CALCULATED_AT, TZDB_VERSION);

        assertThat(overnightResult.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.overnight()).isTrue();
            assertThat(segment.minutes()).isEqualTo(8L * 60L);
            assertThat(segment.startAt()).isEqualTo(
                    LocalDateTime.of(MONDAY, LocalTime.of(22, 0))
                            .atZone(ZoneId.of("Asia/Seoul")).toInstant());
        });
    }

    @Test
    void simulationBlocksDstGapAndRequiresAnExplicitFoldChoice() {
        LocalDate gapDate = LocalDate.parse("2026-03-08");
        LocalSegment gap = segment("gap", DayOfWeek.SUNDAY, 2, 30, 3, 30, 0,
                DstOverlapPolicy.REJECT);
        SimulationResult gapResult = simulateSingle(gapDate, "America/New_York", gap);
        assertThat(gapResult.state()).isEqualTo(SimulationState.BLOCKED);
        assertThat(gapResult.findings()).anyMatch(finding -> finding.startsWith("DST_GAP:"));

        LocalDate foldDate = LocalDate.parse("2026-11-01");
        LocalSegment rejectedFold = segment("fold", DayOfWeek.SUNDAY, 1, 30, 2, 30, 0,
                DstOverlapPolicy.REJECT);
        SimulationResult rejected = simulateSingle(foldDate, "America/New_York", rejectedFold);
        assertThat(rejected.state()).isEqualTo(SimulationState.BLOCKED);
        assertThat(rejected.findings())
                .anyMatch(finding -> finding.startsWith("DST_FOLD_REQUIRES_POLICY:"));

        SimulationResult earlier = simulateSingle(
                foldDate,
                "America/New_York",
                segment("fold", DayOfWeek.SUNDAY, 1, 30, 2, 30, 0,
                        DstOverlapPolicy.EARLIER));
        SimulationResult later = simulateSingle(
                foldDate,
                "America/New_York",
                segment("fold", DayOfWeek.SUNDAY, 1, 30, 2, 30, 0,
                        DstOverlapPolicy.LATER));
        assertThat(earlier.segments()).singleElement()
                .extracting(segment -> segment.dstResolution())
                .isEqualTo(DstResolution.FOLD_EARLIER);
        assertThat(later.segments()).singleElement()
                .extracting(segment -> segment.dstResolution())
                .isEqualTo(DstResolution.FOLD_LATER);
        assertThat(earlier.segments().getFirst().startAt())
                .isBefore(later.segments().getFirst().startAt());
    }

    @Test
    void simulationBlocksMultipleEmploymentOverlapAndRejectsOutOfRangeInput() {
        LocalSegment firstShift = segment("first", DayOfWeek.MONDAY, 9, 0, 12, 0, 0,
                DstOverlapPolicy.REJECT);
        LocalSegment secondShift = segment("second", DayOfWeek.MONDAY, 11, 0, 14, 0, 0,
                DstOverlapPolicy.REJECT);
        AssignmentPlan first = assignment(
                uuid("40000000-0000-0000-0000-000000000010"), WORKER_ID, "UTC",
                template("first-current", firstShift), template("first-draft", firstShift), YEAR);
        AssignmentPlan second = assignment(
                uuid("40000000-0000-0000-0000-000000000020"), WORKER_ID, "UTC",
                template("second-current", secondShift), template("second-draft", secondShift), YEAR);

        SimulationResult overlap = simulation.simulate(
                resolved(), List.of(second, first), MONDAY, MONDAY.plusDays(1),
                CALCULATED_AT, TZDB_VERSION);
        assertThat(overlap.state()).isEqualTo(SimulationState.BLOCKED);
        assertThat(overlap.findings())
                .singleElement().asString().startsWith("MULTIPLE_EMPLOYMENT_OVERLAP:");

        AssignmentPlan startsAtRequestedEnd = assignment(
                uuid("40000000-0000-0000-0000-000000000030"), WORKER_ID, "UTC",
                template("short-current", firstShift), template("short-draft", firstShift),
                new EffectivePeriod(MONDAY.plusDays(2), MONDAY.plusDays(3)));
        SimulationRejectedException rejected = assertThrows(
                SimulationRejectedException.class,
                () -> simulation.simulate(
                        resolved(), List.of(startsAtRequestedEnd), MONDAY, MONDAY.plusDays(2),
                        CALCULATED_AT, TZDB_VERSION));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ASSIGNMENT_OUT_OF_RANGE);
    }

    @Test
    void simulationClipsPartiallyEffectiveAssignmentUsingHalfOpenDates() {
        UUID targetId = uuid("40000000-0000-0000-0000-000000000040");
        UUID contextId = uuid("40000000-0000-0000-0000-000000000050");
        LocalSegment targetTuesday = segment(
                "target-tuesday", DayOfWeek.TUESDAY, 9, 0, 12, 0, 0,
                DstOverlapPolicy.REJECT);
        AssignmentPlan target = assignment(
                targetId, WORKER_ID, "UTC", template("target-current"),
                template("target-draft", targetTuesday), YEAR);
        AssignmentPlan partialContext = assignment(
                contextId,
                WORKER_ID,
                "UTC",
                template(
                        "context-current",
                        segment("outside", DayOfWeek.MONDAY, 11, 0, 14, 0, 0,
                                DstOverlapPolicy.REJECT),
                        segment("inside", DayOfWeek.TUESDAY, 11, 0, 14, 0, 0,
                                DstOverlapPolicy.REJECT)),
                template(
                        "context-draft",
                        segment("outside", DayOfWeek.MONDAY, 11, 0, 14, 0, 0,
                                DstOverlapPolicy.REJECT),
                        segment("inside", DayOfWeek.TUESDAY, 11, 0, 14, 0, 0,
                                DstOverlapPolicy.REJECT)),
                new EffectivePeriod(MONDAY.plusDays(1), MONDAY.plusDays(2)));

        SimulationResult result = simulation.simulate(
                resolved(), List.of(target, partialContext), MONDAY, MONDAY.plusDays(3),
                CALCULATED_AT, TZDB_VERSION);

        assertThat(result.state()).isEqualTo(SimulationState.BLOCKED);
        assertThat(result.segments().stream()
                .filter(segment -> segment.assignmentId().equals(contextId))
                .map(segment -> segment.localWorkDate() + ":" + segment.segmentKey()))
                .containsExactly(MONDAY.plusDays(1) + ":inside");
        assertThat(result.findings())
                .singleElement().asString().startsWith("MULTIPLE_EMPLOYMENT_OVERLAP:");
    }

    @Test
    void simulationComparesSlicedCurrentBaselineWithoutExtendingIt() {
        UUID assignmentId = uuid("40000000-0000-0000-0000-000000000060");
        ScheduleTemplate empty = template("empty");
        AssignmentPlan draft = assignment(
                assignmentId,
                WORKER_ID,
                "UTC",
                empty,
                template("draft", segment(
                        "tuesday", DayOfWeek.TUESDAY, 9, 0, 12, 0, 0,
                        DstOverlapPolicy.REJECT)),
                YEAR);
        AssignmentPlan currentSlice = assignment(
                assignmentId,
                WORKER_ID,
                "UTC",
                template("current", segment(
                        "monday", DayOfWeek.MONDAY, 9, 0, 12, 0, 0,
                        DstOverlapPolicy.REJECT)),
                empty,
                new EffectivePeriod(MONDAY, MONDAY.plusDays(1)));

        SimulationResult result = simulation.simulate(
                resolved(), List.of(draft, currentSlice), MONDAY, MONDAY.plusDays(3),
                CALCULATED_AT, TZDB_VERSION);

        assertThat(result.differences())
                .extracting(diff -> diff.localWorkDate() + ":" + diff.segmentKey()
                        + ":" + diff.kind())
                .containsExactly(
                        MONDAY + ":monday:" + DiffKind.REMOVED,
                        MONDAY.plusDays(1) + ":tuesday:" + DiffKind.ADDED);
    }

    @Test
    void lifecycleFollowsExactValidatedSimulatedReviewApprovedPublishedSequence() {
        WorkRegimeRevision revision = revision(PolicyState.DRAFT, 1L, null);
        revision = lifecycle.transition(revision, LifecycleAction.VALIDATE, author(), 1L);
        assertState(revision, PolicyState.VALIDATED, 2L);
        revision = lifecycle.transition(revision, LifecycleAction.SIMULATE, author(), 2L);
        assertState(revision, PolicyState.SIMULATED, 3L);
        revision = lifecycle.transition(revision, LifecycleAction.SUBMIT_REVIEW, author(), 3L);
        assertState(revision, PolicyState.IN_REVIEW, 4L);
        revision = lifecycle.transition(revision, LifecycleAction.APPLY_APPROVAL, approver(), 4L);
        assertState(revision, PolicyState.APPROVED, 5L);
        assertThat(revision.approvalActorId()).isEqualTo(APPROVER);
        revision = lifecycle.transition(revision, LifecycleAction.PUBLISH, publisher(true), 5L);
        assertState(revision, PolicyState.PUBLISHED, 6L);
    }

    @Test
    void lifecycleDeniesContextRoleStalenessStepUpAndSeparationFailures() {
        WorkRegimeRevision draft = revision(PolicyState.DRAFT, 1L, null);
        assertDenial(DenialCode.DUTY_MISSING,
                () -> lifecycle.transition(draft, LifecycleAction.VALIDATE,
                        authority(TENANT, PUBLISHER, Set.of(Duty.TIME_AUDITOR),
                                Set.of(SCOPE), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, true, false),
                        1L));
        assertDenial(DenialCode.PURPOSE_MISMATCH,
                () -> lifecycle.transition(draft, LifecycleAction.VALIDATE,
                        authority(TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_AUTHOR),
                                Set.of(SCOPE), "TIME_VIEW", true, false), 1L));
        assertDenial(DenialCode.OUT_OF_SCOPE,
                () -> lifecycle.transition(draft, LifecycleAction.VALIDATE,
                        authority(TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_AUTHOR),
                                Set.of("TENANT:OTHER"), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                                true, false), 1L));
        assertDenial(DenialCode.TENANT_MISMATCH,
                () -> lifecycle.transition(draft, LifecycleAction.VALIDATE,
                        authority(OTHER_TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_AUTHOR),
                                Set.of(SCOPE), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, true, false),
                        1L));
        assertDenial(DenialCode.AUTHORITY_REVOKED,
                () -> lifecycle.transition(draft, LifecycleAction.VALIDATE,
                        authority(TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_AUTHOR),
                                Set.of(SCOPE), WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, true, true),
                        1L));
        assertDenial(DenialCode.STALE_VERSION,
                () -> lifecycle.transition(draft, LifecycleAction.VALIDATE, author(), 2L));

        WorkRegimeRevision review = revision(PolicyState.IN_REVIEW, 4L, null);
        Authority selfApprover = authority(
                TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_APPROVER), Set.of(SCOPE),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, true, false);
        assertDenial(DenialCode.SELF_APPROVAL,
                () -> lifecycle.transition(
                        review, LifecycleAction.APPLY_APPROVAL, selfApprover, 4L));

        WorkRegimeRevision approved = revision(PolicyState.APPROVED, 5L, APPROVER);
        assertDenial(DenialCode.STEP_UP_REQUIRED,
                () -> lifecycle.transition(
                        approved, LifecycleAction.PUBLISH, publisher(false), 5L));
        assertDenial(DenialCode.SEPARATION_OF_DUTY,
                () -> lifecycle.transition(
                        approved, LifecycleAction.PUBLISH, approver(), 5L));
        Authority authorPublisher = authority(
                TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_APPROVER), Set.of(SCOPE),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, true, false);
        assertDenial(DenialCode.SEPARATION_OF_DUTY,
                () -> lifecycle.transition(
                        approved, LifecycleAction.PUBLISH, authorPublisher, 5L));
    }

    @Test
    void receiptOperationsCannotBeUsedAsLifecycleTransitions() {
        WorkRegimeRevision draft = revision(PolicyState.DRAFT, 1L, null);
        for (LifecycleAction action : List.of(
                LifecycleAction.CREATE_DRAFT,
                LifecycleAction.REVISE_DRAFT,
                LifecycleAction.ASSIGN)) {
            assertDenial(DenialCode.INVALID_TRANSITION,
                    () -> lifecycle.transition(draft, action, author(), 1L));
        }
    }

    private PolicyResolution resolve(
            LocalDate date,
            long expectedPolicyRevision,
            List<RulePack> packs,
            List<PolicyCandidate> candidates,
            Set<String> scopes) {
        return resolver.resolve(
                TENANT, JURISDICTION, date, 1L, expectedPolicyRevision,
                scopes, packs, candidates);
    }

    private SimulationResult simulateSingle(LocalDate date, String zone, LocalSegment segment) {
        AssignmentPlan plan = assignment(
                uuid("40000000-0000-0000-0000-000000000099"), WORKER_ID, zone,
                template("single-current", segment), template("single-draft", segment), YEAR);
        return simulation.simulate(
                resolved(), List.of(plan), date, date.plusDays(1), CALCULATED_AT, TZDB_VERSION);
    }

    private static PolicyResolution resolved() {
        return new PolicyResolution(
                ResolutionCode.RESOLVED,
                candidate(POLICY_ID, TENANT, 1L, ScopeType.TENANT, SCOPE, 1,
                        YEAR, PACK_ID, JURISDICTION, PolicyState.PUBLISHED),
                pack(),
                List.of(POLICY_ID));
    }

    private static RulePack pack() {
        return pack(PACK_ID, TENANT, JURISDICTION, RulePackState.PUBLISHED, YEAR, true);
    }

    private static RulePack pack(
            UUID publicId,
            long tenantId,
            String jurisdiction,
            RulePackState state,
            EffectivePeriod period,
            boolean signatureVerified) {
        return new RulePack(
                tenantId, publicId, jurisdiction, 1L, state, period, signatureVerified, DIGEST);
    }

    private static PolicyCandidate candidate(
            UUID publicId,
            long tenantId,
            long revision,
            ScopeType scopeType,
            String scopeRef,
            int priority,
            EffectivePeriod period,
            UUID packId,
            String jurisdiction,
            PolicyState state) {
        return new PolicyCandidate(
                tenantId,
                publicId,
                revision,
                ArrangementKind.FLEX,
                scopeType,
                scopeRef,
                priority,
                period,
                packId,
                jurisdiction,
                1L,
                state,
                AUTHOR,
                DIGEST);
    }

    private static LocalSegment segment(
            String key,
            DayOfWeek day,
            int startHour,
            int startMinute,
            int endHour,
            int endMinute,
            int endDayOffset,
            DstOverlapPolicy overlapPolicy) {
        return new LocalSegment(
                key,
                day,
                SegmentKind.WORK,
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute),
                endDayOffset,
                overlapPolicy);
    }

    private static ScheduleTemplate template(String id, LocalSegment... segments) {
        return new ScheduleTemplate(id, 1L, List.of(segments));
    }

    private static AssignmentPlan assignment(
            UUID assignmentId,
            UUID workerId,
            String zoneId,
            ScheduleTemplate current,
            ScheduleTemplate draft,
            EffectivePeriod period) {
        return new AssignmentPlan(
                TENANT, assignmentId, workerId, 1L, period, zoneId, current, draft);
    }

    private static WorkRegimeRevision revision(
            PolicyState state, long version, Long approvalActorId) {
        return new WorkRegimeRevision(
                TENANT,
                uuid("50000000-0000-0000-0000-000000000001"),
                1L,
                version,
                state,
                AUTHOR,
                approvalActorId,
                SCOPE,
                YEAR,
                DIGEST);
    }

    private static Authority author() {
        return authority(
                TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_AUTHOR), Set.of(SCOPE),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, false, false);
    }

    private static Authority approver() {
        return authority(
                TENANT, APPROVER, Set.of(Duty.TIME_CONFIG_APPROVER), Set.of(SCOPE),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, true, false);
    }

    private static Authority publisher(boolean stepUp) {
        return authority(
                TENANT, PUBLISHER, Set.of(Duty.TIME_CONFIG_APPROVER), Set.of(SCOPE),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE, stepUp, false);
    }

    private static Authority authority(
            long tenantId,
            long actorId,
            Set<Duty> duties,
            Set<String> scopes,
            String purpose,
            boolean stepUp,
            boolean revoked) {
        return new Authority(
                tenantId, actorId, duties, scopes, purpose,
                "decision-tim-016", stepUp, revoked);
    }

    private static void assertState(
            WorkRegimeRevision revision, PolicyState expectedState, long expectedVersion) {
        assertThat(revision.state()).isEqualTo(expectedState);
        assertThat(revision.version()).isEqualTo(expectedVersion);
    }

    private static void assertDenial(DenialCode expected, Runnable command) {
        LifecycleDeniedException denied = assertThrows(LifecycleDeniedException.class, command::run);
        assertThat(denied.code()).isEqualTo(expected);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
