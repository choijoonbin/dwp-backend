package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long ACTOR_ID = 7L;
    private static final String SURFACE = "workspace.work";
    private static final String SURFACE_PERMISSIONS = "APP.WORK:VIEW";
    private static final UUID TEAM_REF = UUID.fromString(
            "58fa4516-dc70-4785-ac9f-3606992c3f6b");
    private static final String TEAM_HEADER = TEAM_REF.toString();

    @Mock
    private SavedViewRepository repository;
    @Mock
    private PlatformAuditService audit;
    @Mock
    private SavedViewSubjectDirectory subjects;
    @Mock
    private SavedViewLifecycleHistoryRepository lifecycleHistory;
    @Mock
    private SavedViewOwnershipConflictPolicy ownershipConflicts;

    private SavedViewService service;
    private SavedViewSurfaceAccessPolicy surfaceAccess;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        surfaceAccess = new SavedViewSurfaceAccessPolicy();
        SavedViewTargetEligibilityPolicy targetEligibility =
                new SavedViewTargetEligibilityPolicy(surfaceAccess);
        service = new SavedViewService(repository, audit, objectMapper, subjects,
                new SavedViewOrphanLifecycleService(
                        repository, subjects, audit, objectMapper, lifecycleHistory,
                        ownershipConflicts, targetEligibility), ownershipConflicts,
                surfaceAccess, targetEligibility);
        lenient().when(subjects.require(TENANT_ID, 21L))
                .thenReturn(subject(21L, "Departing Owner"));
        lenient().when(subjects.require(TENANT_ID, 22L))
                .thenReturn(subject(22L, "Successor Owner"));
        lenient().when(subjects.require(TENANT_ID, 23L))
                .thenReturn(subject(23L, "Alternate Owner"));
        lenient().when(ownershipConflicts.transferConflicts(anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(ownershipConflicts.orphanCandidateReasons(
                        anyLong(), any(), any()))
                .thenReturn(Map.of());
    }

    @Test
    void resolvesPersonalTeamAndTenantVisibilityFromVerifiedGroups() {
        SavedViewRepository.Row personal = row(UUID.randomUUID(), ACTOR_ID, "PERSONAL", null);
        SavedViewRepository.Row team = row(UUID.randomUUID(), 99L, "TEAM", TEAM_REF);
        SavedViewRepository.Row tenant = row(UUID.randomUUID(), 99L, "TENANT", null);
        when(repository.visible(TENANT_ID, ACTOR_ID, Set.of(TEAM_REF), SURFACE))
                .thenReturn(List.of(personal, team, tenant));

        List<SavedViewDtos.SavedView> result = service.list(
                TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS,
                "WORKSPACE_MEMBER", TEAM_HEADER, SURFACE);

        assertThat(result).extracting(SavedViewDtos.SavedView::scope)
                .containsExactly("PERSONAL", "TEAM", "TENANT");
        assertThat(result).extracting(SavedViewDtos.SavedView::editable)
                .containsExactly(true, false, false);
    }

    @Test
    void createsTeamViewOnlyForVerifiedMembership() {
        UUID id = UUID.randomUUID();
        SavedViewRepository.Row created = row(id, ACTOR_ID, "TEAM", TEAM_REF);
        when(repository.create(
                TENANT_ID, ACTOR_ID, SURFACE, "Finance queue", "TEAM", TEAM_REF,
                Map.of("status", "OPEN"))).thenReturn(id);
        when(repository.find(TENANT_ID, ACTOR_ID, id)).thenReturn(Optional.of(created));

        SavedViewDtos.SavedView result = service.create(
                TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS,
                "WORKSPACE_MEMBER", TEAM_HEADER, "corr", SURFACE,
                new SavedViewDtos.CreateRequest(
                        "Finance queue", "TEAM", TEAM_REF,
                        Map.of("status", "OPEN"), true, false));

        assertThat(result.scope()).isEqualTo("TEAM");
        assertThat(result.ownerGroupRef()).isEqualTo(TEAM_REF);
        assertThat(result.editable()).isTrue();
        verify(audit).success(
                TENANT_ID, ACTOR_ID, "workspace.saved-view.created", "SAVED_VIEW",
                id.toString(), "corr", null, created);
    }

    @Test
    void rejectsTeamOwnerOutsideVerifiedMembership() {
        UUID anotherTeam = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(
                TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS,
                "WORKSPACE_MEMBER", TEAM_HEADER, "corr", SURFACE,
                new SavedViewDtos.CreateRequest(
                        "Other team", "TEAM", anotherTeam, Map.of(), false, false)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).create(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void preventsOrdinaryMembersFromPublishingOrganizationViews() {
        assertThatThrownBy(() -> service.create(
                TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS,
                "WORKSPACE_MEMBER", null, "corr", SURFACE,
                new SavedViewDtos.CreateRequest(
                        "Organization queue", "TENANT", null, Map.of(), false, false)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).create(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void masksAnotherUsersPersonalViewAsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.find(TENANT_ID, ACTOR_ID, id))
                .thenReturn(Optional.of(row(id, 99L, "PERSONAL", null)));

        assertThatThrownBy(() -> service.markUsed(
                TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS, null, id))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void allowsTenantAdministratorToEditOrganizationView() {
        UUID id = UUID.randomUUID();
        SavedViewRepository.Row before = row(id, 99L, "TENANT", null);
        SavedViewRepository.Row after = updated(before, "Updated", 3L);
        when(repository.find(TENANT_ID, ACTOR_ID, id))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(repository.update(
                TENANT_ID, ACTOR_ID, id, "Updated", "TENANT", null,
                Map.of("priority", "HIGH"), 2L)).thenReturn(true);

        SavedViewDtos.SavedView result = service.update(
                TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS,
                "TENANT_ADMIN", null, "corr", id,
                new SavedViewDtos.UpdateRequest(
                        "Updated", "TENANT", null, Map.of("priority", "HIGH"), 2L));

        assertThat(result.editable()).isTrue();
        assertThat(result.version()).isEqualTo(3L);
        verify(audit).success(
                TENANT_ID, ACTOR_ID, "workspace.saved-view.updated", "SAVED_VIEW",
                id.toString(), "corr", before, after);
    }

    @Test
    void archivesInsteadOfPhysicallyDeletingAView() {
        UUID id = UUID.randomUUID();
        SavedViewRepository.Row before = row(id, ACTOR_ID, "PERSONAL", null);
        when(repository.find(TENANT_ID, ACTOR_ID, id)).thenReturn(Optional.of(before));
        when(repository.archive(TENANT_ID, ACTOR_ID, id)).thenReturn(true);

        service.delete(TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS,
                "WORKSPACE_MEMBER", null, "corr", id);

        verify(repository).archive(TENANT_ID, ACTOR_ID, id);
        verify(audit).success(
                eq(TENANT_ID), eq(ACTOR_ID), eq("workspace.saved-view.archived"),
                eq("SAVED_VIEW"), eq(id.toString()), eq("corr"), any(), any());
    }

    @Test
    void previewsAnOwnershipPlanWithAStableSnapshotFingerprint() {
        List<SavedViewRepository.Row> views = List.of(
                row(UUID.fromString("0616cbee-72f4-4bd0-b18a-5fa8cbd67b18"), 21L,
                        "PERSONAL", null),
                row(UUID.fromString("7b59a27d-cbb9-483f-9ecf-8334fc494b1d"), 21L,
                        "TEAM", TEAM_REF));
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(views);

        SavedViewDtos.OwnershipPreview preview = service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null));

        assertThat(preview.affectedCount()).isEqualTo(2);
        assertThat(preview.ownershipFingerprint()).matches("^[0-9a-f]{64}$");
        assertThat(preview.views()).extracting(SavedViewDtos.OwnershipCandidate::scope)
                .containsExactly("PERSONAL", "TEAM");
        assertThat(preview.nameConflicts()).isEmpty();
    }

    @Test
    void evaluatesCustodyCandidatesFromOnePrivacyBoundedDirectorySnapshot() {
        SavedViewRepository.Row personal = row(
                UUID.randomUUID(), 21L, "PERSONAL", null);
        SavedViewRepository.Row team = row(
                UUID.randomUUID(), 21L, "TEAM", TEAM_REF);
        when(repository.ownedActive(TENANT_ID, 21L))
                .thenReturn(List.of(personal, team));
        SavedViewSubjectDirectory.DirectorySubject eligible = directorySubject(
                24L, List.of("TENANT_ADMIN"), List.of(TEAM_REF),
                List.of(SURFACE_PERMISSIONS));
        SavedViewSubjectDirectory.DirectorySubject missingTeam = directorySubject(
                25L, List.of("TENANT_ADMIN"), List.of(),
                List.of(SURFACE_PERMISSIONS));

        List<SavedViewDtos.CustodyCandidate> result = service.custodyCandidates(
                TENANT_ID, ACTOR_ID, List.of(eligible, missingTeam), 21L, null);

        assertThat(result).extracting(SavedViewDtos.CustodyCandidate::eligibilityStatus)
                .containsExactly("ELIGIBLE", "INELIGIBLE");
        assertThat(result.get(1).ineligibilityReasons())
                .containsExactly(SavedViewTargetEligibilityPolicy.MISSING_TEAM_MEMBERSHIP);
        verify(subjects, never()).require(TENANT_ID, 24L);
        verify(subjects, never()).require(TENANT_ID, 25L);
    }

    @Test
    void failsClosedPerCandidateWhenBatchEligibilityEvidenceIsUnavailable() {
        when(repository.ownedActive(TENANT_ID, 21L))
                .thenReturn(List.of(row(UUID.randomUUID(), 21L, "PERSONAL", null)));
        SavedViewSubjectDirectory.DirectorySubject incomplete =
                new SavedViewSubjectDirectory.DirectorySubject(
                        TENANT_ID, 24L, UUID.randomUUID(), UUID.randomUUID(),
                        "Incomplete", "incomplete@example.test", null,
                        "ACTIVE", "TENANT", null, null, null);

        SavedViewDtos.CustodyCandidate result = service.custodyCandidates(
                TENANT_ID, ACTOR_ID, List.of(incomplete), 21L, null).getFirst();

        assertThat(result.eligibilityStatus()).isEqualTo("INELIGIBLE");
        assertThat(result.ineligibilityReasons())
                .containsExactly(SavedViewService.EVALUATION_UNAVAILABLE);
    }

    @Test
    void evaluatesOrphanReassignmentAgainstOneRetainedView() {
        UUID savedViewId = UUID.randomUUID();
        SavedViewRepository.Row retained = orphan(
                savedViewId, "TEAM", TEAM_REF, OffsetDateTime.now().plusDays(10), 2L);
        when(repository.orphan(TENANT_ID, savedViewId))
                .thenReturn(Optional.of(retained));
        SavedViewSubjectDirectory.DirectorySubject candidate = directorySubject(
                24L, List.of("TENANT_ADMIN"), List.of(TEAM_REF),
                List.of(SURFACE_PERMISSIONS));

        SavedViewDtos.CustodyCandidate result = service.custodyCandidates(
                TENANT_ID, ACTOR_ID, List.of(candidate), null, savedViewId).getFirst();

        assertThat(result.eligibilityStatus()).isEqualTo("ELIGIBLE");
        assertThat(result.ineligibilityReasons()).isEmpty();
    }

    @Test
    void marksOnlyTheConflictingPersonalTargetIneligibleBeforeReassignment() {
        UUID savedViewId = UUID.randomUUID();
        SavedViewRepository.Row retained = orphan(
                savedViewId, "PERSONAL", null, OffsetDateTime.now().plusDays(10), 2L);
        when(repository.orphan(TENANT_ID, savedViewId))
                .thenReturn(Optional.of(retained));
        SavedViewSubjectDirectory.DirectorySubject candidate = directorySubject(
                24L, List.of(), List.of(), List.of(SURFACE_PERMISSIONS));
        when(ownershipConflicts.orphanCandidateReasons(
                TENANT_ID, savedViewId, List.of(24L)))
                .thenReturn(Map.of(
                        24L,
                        SavedViewOwnershipConflictPolicy.PERSONAL_CANDIDATE_REASON));

        SavedViewDtos.CustodyCandidate result = service.custodyCandidates(
                TENANT_ID, ACTOR_ID, List.of(candidate), null, savedViewId).getFirst();

        assertThat(result.eligibilityStatus()).isEqualTo("INELIGIBLE");
        assertThat(result.ineligibilityReasons())
                .containsExactly(
                        SavedViewOwnershipConflictPolicy.PERSONAL_CANDIDATE_REASON);
    }

    @Test
    void previewsPersonalNameConflictsBeforeTheAdministratorExecutes() {
        UUID incomingId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        SavedViewDtos.OwnershipNameConflict conflict =
                new SavedViewDtos.OwnershipNameConflict(
                        incomingId, "Finance queue", SURFACE,
                        existingId, "FINANCE QUEUE");
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(List.of(
                row(incomingId, 21L, "PERSONAL", null)));
        when(ownershipConflicts.transferConflicts(TENANT_ID, 21L, 22L))
                .thenReturn(List.of(conflict));

        SavedViewDtos.OwnershipPreview preview = service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null));

        assertThat(preview.nameConflicts()).containsExactly(conflict);
    }

    @Test
    void rechecksPersonalNameConflictsImmediatelyBeforeTransfer() {
        List<SavedViewRepository.Row> views = List.of(
                row(UUID.randomUUID(), 21L, "PERSONAL", null));
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(views);
        SavedViewDtos.OwnershipPreview preview = service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null));
        when(repository.transferByIdempotency(TENANT_ID, "offboard-race-001"))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new BaseException(
                        ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT))
                .when(ownershipConflicts).requireTransferClear(TENANT_ID, 21L, 22L);

        assertThatThrownBy(() -> service.transferOwnership(
                TENANT_ID, ACTOR_ID, "corr",
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboard-race-001", 21L, "TRANSFER", 22L,
                        "OFFBOARDING", "Employment ended", "HR-EVENT-883", null,
                        preview.affectedCount(), preview.ownershipFingerprint())))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT);
                    assertThat(exception.getMessage())
                            .contains("same name and surface");
                });

        verify(repository, never()).transfer(
                anyLong(), anyLong(), any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void refusesTransferWhenOwnershipChangedAfterPreview() {
        when(repository.transferByIdempotency(TENANT_ID, "offboard-21-001"))
                .thenReturn(Optional.empty());
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L))
                .thenReturn(List.of(row(UUID.randomUUID(), 21L, "PERSONAL", null)));

        assertThatThrownBy(() -> service.transferOwnership(
                TENANT_ID, ACTOR_ID, "corr",
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboard-21-001", 21L, "TRANSFER", 22L,
                        "OFFBOARDING", "Employment ended", "HR-EVENT-883", null,
                        1, "0".repeat(64))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_CUSTODY_STALE));

        verify(repository, never()).transfer(
                anyLong(), anyLong(), any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void refusesTransferWhenTheDecisionChangedAfterPreview() {
        List<SavedViewRepository.Row> views = List.of(
                row(UUID.randomUUID(), 21L, "PERSONAL", null));
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(views);
        SavedViewDtos.OwnershipPreview preview = service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null));
        when(repository.transferByIdempotency(TENANT_ID, "offboard-21-changed"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferOwnership(
                TENANT_ID, ACTOR_ID, "corr",
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboard-21-changed", 21L, "TRANSFER", 23L,
                        "OFFBOARDING", "Employment ended", "HR-EVENT-883", null,
                        preview.affectedCount(), preview.ownershipFingerprint())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_CUSTODY_STALE));

        verify(repository, never()).transfer(
                anyLong(), anyLong(), any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void executesAValidatedOwnershipTransferAndAuditsTheBatch() {
        List<SavedViewRepository.Row> views = List.of(
                row(UUID.randomUUID(), 21L, "PERSONAL", null));
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(views);
        SavedViewDtos.OwnershipPreview preview = service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null));
        when(repository.transferByIdempotency(TENANT_ID, "offboard-21-002"))
                .thenReturn(Optional.empty());
        when(subjects.require(TENANT_ID, 21L)).thenReturn(subject(21L, "Departing Owner"));
        when(subjects.require(TENANT_ID, 22L)).thenReturn(subject(22L, "Successor Owner"));
        when(repository.transfer(
                eq(TENANT_ID), eq(ACTOR_ID), any(), eq("Departing Owner"),
                eq("Successor Owner"), any(), anyString(), eq(views)))
                .thenAnswer(invocation -> {
                    SavedViewDtos.OwnershipTransferRequest request = invocation.getArgument(5);
                    String requestFingerprint = invocation.getArgument(6);
                    return new SavedViewDtos.OwnershipTransfer(
                            UUID.randomUUID(), request.idempotencyKey(),
                            request.sourceOwnerUserId(), "Departing Owner",
                            request.targetOwnerUserId(), "Successor Owner",
                            request.disposition(), request.reasonCode(), request.reason(),
                            request.sourceReference(),
                            request.retentionUntil(), views.size(), request.ownershipFingerprint(),
                            requestFingerprint, OffsetDateTime.now(), ACTOR_ID);
                });

        SavedViewDtos.OwnershipTransfer result = service.transferOwnership(
                TENANT_ID, ACTOR_ID, "corr",
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboard-21-002", 21L, "TRANSFER", 22L,
                        "OFFBOARDING", "Employment ended", "HR-EVENT-883", null,
                        preview.affectedCount(), preview.ownershipFingerprint()));

        assertThat(result.transferredCount()).isEqualTo(1);
        assertThat(result.requestFingerprint()).matches("^[0-9a-f]{64}$");
        verify(audit).success(
                eq(TENANT_ID), eq(ACTOR_ID),
                eq("admin.saved-view-ownership.transferred"),
                eq("SAVED_VIEW_TRANSFER_BATCH"), anyString(), eq("corr"),
                eq(null), any());
    }

    private SavedViewSubjectDirectory.Subject subject(long userId, String displayName) {
        return new SavedViewSubjectDirectory.Subject(
                TENANT_ID, userId, UUID.randomUUID(), UUID.randomUUID(), displayName,
                displayName.toLowerCase().replace(' ', '.') + "@example.test", null,
                "ACTIVE", "TENANT", List.of("TENANT_ADMIN"), List.of(TEAM_REF),
                List.of(SURFACE_PERMISSIONS));
    }

    private SavedViewSubjectDirectory.DirectorySubject directorySubject(
            long userId,
            List<String> roles,
            List<UUID> groups,
            List<String> permissions) {
        return new SavedViewSubjectDirectory.DirectorySubject(
                TENANT_ID, userId, UUID.randomUUID(), UUID.randomUUID(),
                "Candidate " + userId, "candidate" + userId + "@example.test", null,
                "ACTIVE", "TENANT", roles, groups, permissions);
    }

    @Test
    void refusesReusingAnIdempotencyKeyForDifferentRequestContent() {
        SavedViewDtos.OwnershipTransfer existing = new SavedViewDtos.OwnershipTransfer(
                UUID.randomUUID(), "offboard-21-003", 21L, "Departing Owner",
                22L, "Successor Owner", "TRANSFER", "OFFBOARDING", "Employment ended",
                "HR-EVENT-883", null, 1, "a".repeat(64),
                "f".repeat(64), OffsetDateTime.now(), ACTOR_ID);
        when(repository.transferByIdempotency(TENANT_ID, "offboard-21-003"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transferOwnership(
                TENANT_ID, ACTOR_ID, "corr",
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboard-21-003", 21L, "TRANSFER", 22L,
                        "OWNER_CORRECTION", "Correcting owner", "CASE-77", null,
                        1, "a".repeat(64))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void returnsACompletedReplayBeforeCallingTheMutableIdentityDirectory() throws Exception {
        SavedViewDtos.OwnershipTransferRequest request =
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboard-replay-001", 21L, "TRANSFER", 22L,
                        "OFFBOARDING", "Employment ended", "HR-EVENT-883", null,
                        1, "a".repeat(64));
        String fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(new ObjectMapper().writeValueAsBytes(request)));
        SavedViewDtos.OwnershipTransfer existing = new SavedViewDtos.OwnershipTransfer(
                UUID.randomUUID(), request.idempotencyKey(), 21L, "Former owner",
                22L, "Current owner", "TRANSFER", "OFFBOARDING",
                "Employment ended", "HR-EVENT-883", null, 1,
                "a".repeat(64), fingerprint, OffsetDateTime.now(), ACTOR_ID);
        when(repository.transferByIdempotency(TENANT_ID, request.idempotencyKey()))
                .thenReturn(Optional.of(existing));

        SavedViewDtos.OwnershipTransfer replay = service.transferOwnership(
                TENANT_ID, ACTOR_ID, "corr", request);

        assertThat(replay).isSameAs(existing);
        verifyNoInteractions(subjects);
        verify(repository, never()).ownedActiveForUpdate(anyLong(), anyLong());
    }

    @Test
    void rejectsTeamTransferWhenTheTargetIsNotAnActiveGroupMember() {
        SavedViewRepository.Row team = row(UUID.randomUUID(), 21L, "TEAM", TEAM_REF);
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(List.of(team));
        when(subjects.require(TENANT_ID, 22L)).thenReturn(new SavedViewSubjectDirectory.Subject(
                TENANT_ID, 22L, UUID.randomUUID(), UUID.randomUUID(), "Wrong team",
                "wrong.team@example.test", null, "ACTIVE", "TENANT",
                List.of("TENANT_ADMIN"), List.of(), List.of(SURFACE_PERMISSIONS)));

        assertThatThrownBy(() -> service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "TEAM_REORGANIZATION",
                        "Team ownership changed", "CASE-TEAM-1", null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE));
    }

    @Test
    void rejectsTenantTransferWhenTheTargetCannotAdministerSharedViews() {
        SavedViewRepository.Row tenant = row(UUID.randomUUID(), 21L, "TENANT", null);
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(List.of(tenant));
        when(subjects.require(TENANT_ID, 22L)).thenReturn(new SavedViewSubjectDirectory.Subject(
                TENANT_ID, 22L, UUID.randomUUID(), UUID.randomUUID(), "Ordinary user",
                "ordinary@example.test", null, "ACTIVE", "TENANT",
                List.of("WORKSPACE_MEMBER"), List.of(), List.of(SURFACE_PERMISSIONS)));

        assertThatThrownBy(() -> service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE));
    }

    @Test
    void rejectsTransferWhenTheTargetLacksTheExactProductSurfacePermission() {
        SavedViewRepository.Row personal = row(
                UUID.randomUUID(), 21L, "PERSONAL", null);
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L))
                .thenReturn(List.of(personal));
        when(subjects.require(TENANT_ID, 22L)).thenReturn(
                new SavedViewSubjectDirectory.Subject(
                        TENANT_ID, 22L, UUID.randomUUID(), UUID.randomUUID(),
                        "Successor Owner", "successor@example.test", null,
                        "ACTIVE", "TENANT", List.of("TENANT_ADMIN"), List.of(TEAM_REF),
                        List.of("APP.APPS:VIEW")));

        assertThatThrownBy(() -> service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null)))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE);
                    assertThat(exception.getMessage()).contains(
                            SavedViewSurfaceAccessPolicy.TARGET_NOT_ENTITLED_MESSAGE);
                });
    }

    @Test
    void revalidatesTeamMembershipImmediatelyBeforeExecution() {
        SavedViewRepository.Row team = row(UUID.randomUUID(), 21L, "TEAM", TEAM_REF);
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(List.of(team));
        SavedViewSubjectDirectory.Subject eligible = subject(22L, "Successor Owner");
        SavedViewSubjectDirectory.Subject removedFromTeam =
                new SavedViewSubjectDirectory.Subject(
                        TENANT_ID, 22L, UUID.randomUUID(), UUID.randomUUID(),
                        "Successor Owner", "successor@example.test", null,
                        "ACTIVE", "TENANT", List.of("TENANT_ADMIN"), List.of(),
                        List.of(SURFACE_PERMISSIONS));
        when(subjects.require(TENANT_ID, 22L)).thenReturn(eligible, removedFromTeam);
        SavedViewDtos.OwnershipPreview preview = service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "TEAM_REORGANIZATION",
                        "Team ownership changed", "CASE-TEAM-2", null));
        when(repository.transferByIdempotency(TENANT_ID, "team-transfer-001"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transferOwnership(
                TENANT_ID, ACTOR_ID, "corr",
                new SavedViewDtos.OwnershipTransferRequest(
                        "team-transfer-001", 21L, "TRANSFER", 22L,
                        "TEAM_REORGANIZATION", "Team ownership changed", "CASE-TEAM-2",
                        null, 1, preview.ownershipFingerprint())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE));

        verify(repository, never()).transfer(
                anyLong(), anyLong(), any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void blocksAdministratorsFromAssigningCustodyToThemselves() {
        assertThatThrownBy(() -> service.previewOwnership(
                TENANT_ID, ACTOR_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", ACTOR_ID, "OWNER_CORRECTION",
                        "Correcting the owner", "CASE-SELF-1", null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(subjects);
    }

    @Test
    void reassignsAnOrphanWithVersionedIdempotentAuditEvidence() {
        UUID savedViewId = UUID.randomUUID();
        OffsetDateTime retention = OffsetDateTime.now().plusDays(14);
        SavedViewRepository.Row orphan = orphan(savedViewId, "TEAM", TEAM_REF, retention, 4L);
        SavedViewDtos.OrphanReassignRequest request =
                new SavedViewDtos.OrphanReassignRequest(
                        "recover-orphan-001", 22L, 4L, "OWNER_CORRECTION",
                        "Assigning a verified successor", "CASE-RECOVERY-1");
        when(repository.lifecycleByIdempotency(TENANT_ID, request.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(repository.orphanForUpdate(TENANT_ID, savedViewId))
                .thenReturn(Optional.of(orphan));
        when(repository.applyOrphanLifecycle(
                eq(TENANT_ID), eq(ACTOR_ID), any(), eq(orphan), any()))
                .thenAnswer(invocation -> {
                    SavedViewRepository.LifecycleCommand command = invocation.getArgument(4);
                    return lifecycleResult(
                            savedViewId, command, "ACTIVE", retention, null, ACTOR_ID);
                });

        SavedViewDtos.OrphanLifecycleResult result = service.reassignOrphan(
                TENANT_ID, ACTOR_ID, "corr", savedViewId, request);

        assertThat(result.newLifecycleState()).isEqualTo("ACTIVE");
        assertThat(result.targetOwnerDisplayName()).isEqualTo("Successor Owner");
        verify(audit).success(
                eq(TENANT_ID), eq(ACTOR_ID),
                eq("admin.saved-view-retention.reassigned"), eq("SAVED_VIEW"),
                eq(savedViewId.toString()), eq("corr"), any(), any());
    }

    @Test
    void refusesAnOrphanReassignmentWithAPersonalNameConflictBeforeMutation() {
        UUID savedViewId = UUID.randomUUID();
        OffsetDateTime retention = OffsetDateTime.now().plusDays(14);
        SavedViewRepository.Row orphan = orphan(
                savedViewId, "PERSONAL", null, retention, 4L);
        SavedViewDtos.OrphanReassignRequest request =
                new SavedViewDtos.OrphanReassignRequest(
                        "recover-conflict-001", 22L, 4L, "OWNER_CORRECTION",
                        "Assigning a verified successor", "CASE-RECOVERY-2");
        when(repository.lifecycleByIdempotency(TENANT_ID, request.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(repository.orphanForUpdate(TENANT_ID, savedViewId))
                .thenReturn(Optional.of(orphan));
        org.mockito.Mockito.doThrow(new BaseException(
                        ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT))
                .when(ownershipConflicts)
                .requireOrphanReassignClear(TENANT_ID, savedViewId, 22L);

        assertThatThrownBy(() -> service.reassignOrphan(
                TENANT_ID, ACTOR_ID, "corr", savedViewId, request))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT);
                    assertThat(exception.getMessage())
                            .contains("same name and surface");
                });

        verify(repository, never()).applyOrphanLifecycle(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void mapsASharedUniqueIndexRaceToTheStableSharedConflictCode() {
        UUID savedViewId = UUID.randomUUID();
        OffsetDateTime retention = OffsetDateTime.now().plusDays(14);
        SavedViewRepository.Row orphan = orphan(
                savedViewId, "TEAM", TEAM_REF, retention, 4L);
        SavedViewDtos.OrphanReassignRequest request =
                new SavedViewDtos.OrphanReassignRequest(
                        "recover-shared-race-001", 22L, 4L, "OWNER_CORRECTION",
                        "Assigning a verified successor", "CASE-RECOVERY-RACE");
        org.springframework.dao.DataIntegrityViolationException race =
                new org.springframework.dao.DataIntegrityViolationException(
                        "uk_usr_saved_views_team_name");
        when(repository.lifecycleByIdempotency(TENANT_ID, request.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(repository.orphanForUpdate(TENANT_ID, savedViewId))
                .thenReturn(Optional.of(orphan));
        when(repository.applyOrphanLifecycle(
                eq(TENANT_ID), eq(ACTOR_ID), any(), eq(orphan), any()))
                .thenThrow(race);
        when(ownershipConflicts.conflict("TEAM", race))
                .thenReturn(new BaseException(ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT));

        assertThatThrownBy(() -> service.reassignOrphan(
                TENANT_ID, ACTOR_ID, "corr", savedViewId, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT));

        verify(ownershipConflicts).conflict("TEAM", race);
    }

    @Test
    void refusesToShortenOrOverextendAnOrphanRetentionWindow() {
        UUID savedViewId = UUID.randomUUID();
        OffsetDateTime currentRetention = OffsetDateTime.now().plusDays(30);
        SavedViewRepository.Row orphan = orphan(
                savedViewId, "PERSONAL", null, currentRetention, 2L);
        SavedViewDtos.OrphanRetentionRequest request =
                new SavedViewDtos.OrphanRetentionRequest(
                        "extend-orphan-001", OffsetDateTime.now().plusDays(20), 2L,
                        "OWNER_CORRECTION", "Review period remains required", "CASE-EXTEND-1");
        when(repository.lifecycleByIdempotency(TENANT_ID, request.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(repository.orphanForUpdate(TENANT_ID, savedViewId))
                .thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> service.extendOrphanRetention(
                TENANT_ID, ACTOR_ID, "corr", savedViewId, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(repository, never()).applyOrphanLifecycle(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void returnsTheStableStaleCodeWhenAnOrphanVersionChanged() {
        UUID savedViewId = UUID.randomUUID();
        OffsetDateTime retention = OffsetDateTime.now().plusDays(30);
        SavedViewRepository.Row orphan = orphan(
                savedViewId, "PERSONAL", null, retention, 3L);
        SavedViewDtos.OrphanArchiveRequest request =
                new SavedViewDtos.OrphanArchiveRequest(
                        "archive-stale-001", 2L, "OWNER_CORRECTION",
                        "No longer required after review", "CASE-STALE-1");
        when(repository.lifecycleByIdempotency(TENANT_ID, request.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(repository.orphanForUpdate(TENANT_ID, savedViewId))
                .thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> service.archiveOrphanNow(
                TENANT_ID, ACTOR_ID, "corr", savedViewId, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_CUSTODY_STALE));

        verify(repository, never()).applyOrphanLifecycle(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void archivesExpiredOrphansWithTenantScopedServiceAudit() {
        OffsetDateTime now = OffsetDateTime.now();
        SavedViewRepository.Row orphan = new SavedViewRepository.Row(
                UUID.randomUUID(), SURFACE, "Retained", "PERSONAL", null, null,
                "ORPHANED", now.minusMinutes(1), Map.of(), 4L,
                false, false, null, now.minusDays(4), now.minusDays(1));
        when(repository.expiredOrphansForUpdate(now))
                .thenReturn(List.of(new SavedViewRepository.RetentionRow(TENANT_ID, orphan)));
        when(repository.archiveOrphan(TENANT_ID, orphan.id(), orphan.version(), now))
                .thenReturn(true);

        int archived = service.archiveExpiredOrphans(now);

        assertThat(archived).isEqualTo(1);
        verify(audit).serviceSuccess(
                eq(TENANT_ID), eq("workspace.saved-view.retention-expired"),
                eq("SAVED_VIEW"), eq(orphan.id().toString()), eq(null), any(), any());
    }

    @Test
    void rejectsConfigurationsOverSixteenKibibytes() {
        String oversized = "x".repeat(16_385);

        assertThatThrownBy(() -> service.create(
                TENANT_ID, ACTOR_ID, SURFACE_PERMISSIONS,
                "WORKSPACE_MEMBER", null, "corr", SURFACE,
                new SavedViewDtos.CreateRequest(
                        "Large", "PERSONAL", null,
                        Map.of("query", oversized), false, false)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private SavedViewRepository.Row row(
            UUID id, Long ownerId, String scope, UUID ownerGroupRef) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SavedViewRepository.Row(
                id, SURFACE, "TENANT".equals(scope) ? "Shared view" : "Personal view",
                scope, ownerId, ownerGroupRef, "ACTIVE", null,
                Map.of("status", "OPEN"), 2L, false, false, null,
                now.minusDays(1), now);
    }

    private SavedViewRepository.Row orphan(
            UUID id,
            String scope,
            UUID ownerGroupRef,
            OffsetDateTime retentionUntil,
            long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SavedViewRepository.Row(
                id, SURFACE, "Retained view", scope, null, ownerGroupRef,
                "ORPHANED", retentionUntil, Map.of("status", "OPEN"), version,
                false, false, null, now.minusDays(10), now.minusDays(2));
    }

    private SavedViewDtos.OrphanLifecycleResult lifecycleResult(
            UUID savedViewId,
            SavedViewRepository.LifecycleCommand command,
            String newState,
            OffsetDateTime previousRetention,
            OffsetDateTime nextRetention,
            long actorId) {
        return new SavedViewDtos.OrphanLifecycleResult(
                UUID.randomUUID(), command.idempotencyKey(), savedViewId,
                "Retained view", SURFACE, "PERSONAL",
                command.action(), command.targetOwnerUserId(),
                command.targetOwnerDisplayName(), "ORPHANED", newState,
                previousRetention, nextRetention, command.reasonCode(), command.reason(),
                command.sourceReference(), command.requestFingerprint(), command.version(),
                command.version() + 1, OffsetDateTime.now(), actorId);
    }

    private SavedViewRepository.Row updated(
            SavedViewRepository.Row source, String name, long version) {
        return new SavedViewRepository.Row(
                source.id(), source.surfaceKey(), name, source.scope(), source.ownerUserId(),
                source.ownerGroupRef(), source.lifecycleState(), source.retentionUntil(),
                Map.of("priority", "HIGH"), version, false, false, null,
                source.createdAt(), OffsetDateTime.now());
    }
}
