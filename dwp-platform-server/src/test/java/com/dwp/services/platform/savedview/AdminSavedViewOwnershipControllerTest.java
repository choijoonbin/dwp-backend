package com.dwp.services.platform.savedview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminSavedViewOwnershipControllerTest {

    @Mock
    private SavedViewOwnershipCoordinator coordinator;
    @Mock
    private SavedViewCustodyAccessGuard access;

    private AdminSavedViewOwnershipController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminSavedViewOwnershipController(coordinator, access);
    }

    @Test
    void protectsPreviewWithManagePermissionAndVerifiedActorContext() {
        SavedViewDtos.OwnershipPlanRequest request = new SavedViewDtos.OwnershipPlanRequest(
                11L, "TRANSFER", 12L, "OFFBOARDING",
                "Employee lifecycle change", "HR-2026-0813", null);

        controller.preview(3L, 7L, "ADMIN.SAVED_VIEW_CUSTODY:MANAGE", request);

        verify(access).manage("ADMIN.SAVED_VIEW_CUSTODY:MANAGE");
        verify(coordinator).preview(3L, 7L, request);
    }

    @Test
    void protectsOrphanRecoveryWithManagePermission() {
        UUID savedViewId = UUID.randomUUID();
        SavedViewDtos.OrphanReassignRequest request =
                new SavedViewDtos.OrphanReassignRequest(
                        "recover-view-001", 12L, 3L, "OWNER_CORRECTION",
                        "Assigning the approved successor", "CASE-2026-11");

        controller.reassignOrphan(
                3L, 7L, "ADMIN.SAVED_VIEW_CUSTODY:MANAGE", "corr",
                savedViewId, request);

        verify(access).manage("ADMIN.SAVED_VIEW_CUSTODY:MANAGE");
        verify(coordinator).reassignOrphan(3L, 7L, "corr", savedViewId, request);
    }

    @Test
    void permitsReadOnlyCustodiansToListRetainedViews() {
        controller.orphaned(3L, "ADMIN.SAVED_VIEW_CUSTODY:VIEW");

        verify(access).view("ADMIN.SAVED_VIEW_CUSTODY:VIEW");
        verify(coordinator).orphaned(3L);
    }

    @Test
    void permitsReadOnlyCustodiansToListRetentionActionHistory() {
        controller.orphanActions(3L, "ADMIN.SAVED_VIEW_CUSTODY:VIEW", 250);

        verify(access).view("ADMIN.SAVED_VIEW_CUSTODY:VIEW");
        verify(coordinator).orphanActions(3L, 250);
    }

    @Test
    void forwardsPlanContextForPrivacyBoundedCandidateEligibility() {
        UUID savedViewId = UUID.randomUUID();

        controller.users(
                3L, 7L, "ADMIN.SAVED_VIEW_CUSTODY:VIEW",
                "target", true, 30, null, savedViewId);

        verify(access).view("ADMIN.SAVED_VIEW_CUSTODY:VIEW");
        verify(coordinator).users(
                3L, 7L, "target", true, 30, null, savedViewId);
    }

    @Test
    void missingPermissionEvidenceIsRejectedAsForbiddenByTheDomainGuard() {
        AdminSavedViewOwnershipController guarded =
                new AdminSavedViewOwnershipController(
                        coordinator, new SavedViewCustodyAccessGuard());

        assertThatThrownBy(() -> guarded.orphaned(3L, null))
                .isInstanceOfSatisfying(
                        com.dwp.core.exception.BaseException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        verifyNoInteractions(coordinator);
    }
}
