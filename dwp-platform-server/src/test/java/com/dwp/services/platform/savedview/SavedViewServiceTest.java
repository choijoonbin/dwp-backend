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

import java.time.OffsetDateTime;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long ACTOR_ID = 7L;
    private static final String SURFACE = "workspace.work";
    private static final UUID TEAM_REF = UUID.fromString(
            "58fa4516-dc70-4785-ac9f-3606992c3f6b");
    private static final String TEAM_HEADER = TEAM_REF.toString();

    @Mock
    private SavedViewRepository repository;
    @Mock
    private PlatformAuditService audit;

    private SavedViewService service;

    @BeforeEach
    void setUp() {
        service = new SavedViewService(repository, audit, new ObjectMapper());
    }

    @Test
    void resolvesPersonalTeamAndTenantVisibilityFromVerifiedGroups() {
        SavedViewRepository.Row personal = row(UUID.randomUUID(), ACTOR_ID, "PERSONAL", null);
        SavedViewRepository.Row team = row(UUID.randomUUID(), 99L, "TEAM", TEAM_REF);
        SavedViewRepository.Row tenant = row(UUID.randomUUID(), 99L, "TENANT", null);
        when(repository.visible(TENANT_ID, ACTOR_ID, Set.of(TEAM_REF), SURFACE))
                .thenReturn(List.of(personal, team, tenant));

        List<SavedViewDtos.SavedView> result = service.list(
                TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", TEAM_HEADER, SURFACE);

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
                TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", TEAM_HEADER, "corr", SURFACE,
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
                TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", TEAM_HEADER, "corr", SURFACE,
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
                TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", null, "corr", SURFACE,
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

        assertThatThrownBy(() -> service.markUsed(TENANT_ID, ACTOR_ID, null, id))
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
                TENANT_ID, ACTOR_ID, "TENANT_ADMIN", null, "corr", id,
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

        service.delete(TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", null, "corr", id);

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
                TENANT_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null));

        assertThat(preview.affectedCount()).isEqualTo(2);
        assertThat(preview.ownershipFingerprint()).matches("^[0-9a-f]{64}$");
        assertThat(preview.views()).extracting(SavedViewDtos.OwnershipCandidate::scope)
                .containsExactly("PERSONAL", "TEAM");
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
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(repository, never()).transfer(
                anyLong(), anyLong(), any(), any(), anyString(), any());
    }

    @Test
    void executesAValidatedOwnershipTransferAndAuditsTheBatch() {
        List<SavedViewRepository.Row> views = List.of(
                row(UUID.randomUUID(), 21L, "PERSONAL", null));
        when(repository.ownedActiveForUpdate(TENANT_ID, 21L)).thenReturn(views);
        SavedViewDtos.OwnershipPreview preview = service.previewOwnership(
                TENANT_ID,
                new SavedViewDtos.OwnershipPlanRequest(
                        21L, "TRANSFER", 22L, "OFFBOARDING",
                        "Employment ended", "HR-EVENT-883", null));
        when(repository.transferByIdempotency(TENANT_ID, "offboard-21-002"))
                .thenReturn(Optional.empty());
        when(repository.transfer(
                eq(TENANT_ID), eq(ACTOR_ID), any(), any(), anyString(), eq(views)))
                .thenAnswer(invocation -> {
                    SavedViewDtos.OwnershipTransferRequest request = invocation.getArgument(3);
                    String requestFingerprint = invocation.getArgument(4);
                    return new SavedViewDtos.OwnershipTransfer(
                            UUID.randomUUID(), request.idempotencyKey(),
                            request.sourceOwnerUserId(), request.targetOwnerUserId(),
                            request.disposition(), request.reasonCode(), request.sourceReference(),
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

    @Test
    void refusesReusingAnIdempotencyKeyForDifferentRequestContent() {
        SavedViewDtos.OwnershipTransfer existing = new SavedViewDtos.OwnershipTransfer(
                UUID.randomUUID(), "offboard-21-003", 21L, 22L, "TRANSFER",
                "OFFBOARDING", "HR-EVENT-883", null, 1, "a".repeat(64),
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
                TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", null, "corr", SURFACE,
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

    private SavedViewRepository.Row updated(
            SavedViewRepository.Row source, String name, long version) {
        return new SavedViewRepository.Row(
                source.id(), source.surfaceKey(), name, source.scope(), source.ownerUserId(),
                source.ownerGroupRef(), source.lifecycleState(), source.retentionUntil(),
                Map.of("priority", "HIGH"), version, false, false, null,
                source.createdAt(), OffsetDateTime.now());
    }
}
