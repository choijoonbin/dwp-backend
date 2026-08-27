package com.dwp.services.platform.savedview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewOwnershipCoordinatorTest {

    @Mock
    private SavedViewService service;
    @Mock
    private SavedViewSubjectDirectory subjects;
    @Mock
    private SavedViewOrphanLifecycleService orphanLifecycle;

    private SavedViewOwnershipCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new SavedViewOwnershipCoordinator(service, subjects, orphanLifecycle);
    }

    @Test
    void delegatesPreviewToTheTransactionalDomainService() {
        SavedViewDtos.OwnershipPlanRequest request = new SavedViewDtos.OwnershipPlanRequest(
                11L, "TRANSFER", 12L, "OFFBOARDING",
                "Employee lifecycle change", "HR-2026-0813", null);

        coordinator.preview(3L, 7L, request);

        verify(service).previewOwnership(3L, 7L, request);
    }

    @Test
    void delegatesTransferSoTheServiceCanResolveReplayBeforeIdentityLookup() {
        SavedViewDtos.OwnershipTransferRequest request =
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboarding-11-20260813", 11L, "TRANSFER", 12L,
                        "OFFBOARDING", "Employee lifecycle change", "HR-2026-0813",
                        null, 2, "a".repeat(64));
        coordinator.transfer(3L, 7L, "corr", request);

        verify(service).transferOwnership(3L, 7L, "corr", request);
    }

    @Test
    void delegatesRetainedViewRecoveryCommands() {
        UUID savedViewId = UUID.randomUUID();
        SavedViewDtos.OrphanArchiveRequest request =
                new SavedViewDtos.OrphanArchiveRequest(
                        "archive-retained-001", 4L, "OWNER_CORRECTION",
                        "No longer required", "CASE-2026-9");

        coordinator.archiveOrphanNow(3L, 7L, "corr", savedViewId, request);

        verify(service).archiveOrphanNow(3L, 7L, "corr", savedViewId, request);
    }

    @Test
    void delegatesBoundedCustodyUserSearchToTheInternalDirectory() {
        var candidate = new SavedViewSubjectDirectory.DirectorySubject(
                3L, 12L, UUID.randomUUID(), UUID.randomUUID(), "Target",
                "target@example.com", "Manager", "ACTIVE", "TENANT",
                List.of("TENANT_ADMIN"), List.of(), List.of("APP.WORK:VIEW"));
        var evaluated = new SavedViewDtos.CustodyCandidate(
                candidate.tenantId(), candidate.userId(), candidate.publicId(),
                candidate.personPublicId(), candidate.displayName(), candidate.email(),
                candidate.jobTitle(), candidate.status(), candidate.identityPlane(),
                "ELIGIBLE", List.of());
        when(subjects.search(3L, "target", true, 30))
                .thenReturn(List.of(candidate));
        when(service.custodyCandidates(
                3L, 7L, List.of(candidate), 11L, null))
                .thenReturn(List.of(evaluated));

        assertThat(coordinator.users(
                3L, 7L, "target", true, 500, 11L, null))
                .containsExactly(evaluated);

        verify(service).custodyCandidates(
                3L, 7L, List.of(candidate), 11L, null);
    }

    @Test
    void keepsRetentionActionsSeparateFromOwnershipTransferHistory() {
        when(orphanLifecycle.actions(3L, 50)).thenReturn(List.of());

        assertThat(coordinator.orphanActions(3L, 50)).isEmpty();

        verify(orphanLifecycle).actions(3L, 50);
    }

}
