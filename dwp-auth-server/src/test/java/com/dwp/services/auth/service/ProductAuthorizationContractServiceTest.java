package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationContractServiceTest {

    @Mock
    private ProductAuthorizationContractRepository repository;

    @Mock
    private ProductAuthorizationContractValidator validator;

    private ProductAuthorizationContractService service;

    @BeforeEach
    void setUp() {
        service = new ProductAuthorizationContractService(repository, validator);
    }

    @Test
    void importsDraftIdempotentlyByBundleVersionAndChecksum() {
        ProductAuthorizationContractDtos.BundleContract contract = contract("a".repeat(64));
        ProductAuthorizationContractRepository.StoredBundle stored =
                stored(UUID.randomUUID(), 1, "DRAFT", contract.checksum());
        when(repository.lock("product-surfaces", 1)).thenReturn(Optional.of(stored));
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.empty());
        when(repository.loadContract(stored)).thenReturn(contract);

        ProductAuthorizationContractDtos.BundleView result = service.importDraft(contract);

        assertThat(result.bundleId()).isEqualTo(stored.bundleId());
        assertThat(result.bundleStatus()).isEqualTo("DRAFT");
        verify(validator).validate(contract);
    }

    @Test
    void refusesToReplaceAnImmutableVersionWithAnotherChecksum() {
        ProductAuthorizationContractDtos.BundleContract contract = contract("a".repeat(64));
        ProductAuthorizationContractRepository.StoredBundle stored =
                stored(UUID.randomUUID(), 1, "DRAFT", "b".repeat(64));
        when(repository.lock("product-surfaces", 1)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.importDraft(contract))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("different checksum");
    }

    @Test
    void governedApprovalBindsExactChecksumAndIndependentMakerCheckerAudit() {
        String checksum = "a".repeat(64);
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle draft =
                stored(bundleId, 3, "DRAFT", checksum);
        ProductAuthorizationContractRepository.StoredBundle approved =
                new ProductAuthorizationContractRepository.StoredBundle(
                        bundleId, "product-surfaces", 3, "APPROVED", 1, "SHA-256",
                        checksum, "Identity + Security", "security-approver",
                        OffsetDateTime.now(ZoneOffset.UTC), null,
                        OffsetDateTime.now(ZoneOffset.UTC));
        doReturn(Optional.of(draft))
                .doReturn(Optional.of(approved))
                .when(repository).lock("product-surfaces", 3);
        when(repository.approve(bundleId, "security-approver")).thenReturn(true);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.empty());

        ProductAuthorizationContractDtos.BundleView result = service.approveGoverned(
                "product-surfaces", 3, checksum,
                "change-owner", "security-approver", "CHG-1001");

        assertThat(result.bundleStatus()).isEqualTo("APPROVED");
        verify(repository).insertGovernanceEvent(
                approved, "APPROVE", null, null,
                "change-owner", "security-approver", "CHG-1001", null,
                "dwp-provider-server");
        verify(repository, never()).markActive(bundleId);
    }

    @Test
    void governedApprovalRejectsCollapsedMakerCheckerBeforeMutation() {
        assertThatThrownBy(() -> service.approveGoverned(
                "product-surfaces", 3, "a".repeat(64),
                "Security.Reviewer", "security.reviewer", "CHG-1001"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("must differ");

        verify(repository, never()).approve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void releasePreflightReturnsExactGovernedApprovalEvidence() {
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(UUID.randomUUID(), 3, "APPROVED", "a".repeat(64));
        ProductAuthorizationContractRepository.GovernedApprovalEvidence evidence =
                approval("CHG-1001");
        when(repository.findVersionSnapshot("product-surfaces", 3)).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.BundleSnapshot(target, 0)));
        when(repository.findGovernedApprovalEvidence(target))
                .thenReturn(Optional.of(evidence));
        ProductAuthorizationContractDtos.GovernedBundlePreflight result =
                service.governedReleaseVersion("product-surfaces", 3);

        assertThat(result.bundle().bundleStatus()).isEqualTo("APPROVED");
        assertThat(result.bundle().checksum()).isEqualTo("a".repeat(64));
        assertThat(result.approvalEvidence().requestedBy()).isEqualTo("change-owner");
        assertThat(result.approvalEvidence().approvedBy()).isEqualTo("reviewer");
        assertThat(result.approvalEvidence().changeRef()).isEqualTo("CHG-1001");
    }

    @Test
    void activePreflightReturnsTheBundleAndCasRevisionFromOneRepositorySnapshot() {
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(UUID.randomUUID(), 3, "ACTIVE", "a".repeat(64));
        when(repository.findActiveSnapshot("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.BundleSnapshot(target, 11)));

        ProductAuthorizationContractDtos.BundleView result =
                service.active("product-surfaces");

        assertThat(result.bundleId()).isEqualTo(target.bundleId());
        assertThat(result.bundleStatus()).isEqualTo("ACTIVE");
        assertThat(result.activeRevision()).isEqualTo(11);
        verify(repository, never()).findActivePointer("product-surfaces");
    }

    @Test
    void releasePreflightRejectsLegacyApprovedBundleWithoutGovernedEvidence() {
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(UUID.randomUUID(), 3, "APPROVED", "a".repeat(64));
        when(repository.findVersionSnapshot("product-surfaces", 3)).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.BundleSnapshot(target, 0)));
        when(repository.findGovernedApprovalEvidence(target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.governedReleaseVersion("product-surfaces", 3))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("governed approval evidence");
    }

    @Test
    void releasePreflightRejectsDraftBeforeItCanBeMistakenForReleaseReady() {
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(UUID.randomUUID(), 3, "DRAFT", "a".repeat(64));
        when(repository.findVersionSnapshot("product-surfaces", 3)).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.BundleSnapshot(target, 0)));

        assertThatThrownBy(() -> service.governedReleaseVersion("product-surfaces", 3))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("approval evidence");

        verify(repository, never()).findGovernedApprovalEvidence(target);
    }

    @Test
    void governedActivationRequiresExactApprovedEvidenceAndWritesSameTransactionAudit() {
        String checksum = "b".repeat(64);
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 3, "APPROVED", checksum);
        doReturn(Optional.of(target))
                .doReturn(Optional.of(target))
                .when(repository).lock("product-surfaces", 3);
        when(repository.findGovernedApprovalEvidence(target))
                .thenReturn(Optional.of(approval("CHG-1001")));
        when(repository.lockActivePointer("product-surfaces")).thenReturn(Optional.empty());
        when(repository.markActive(bundleId)).thenReturn(true);

        ProductAuthorizationContractDtos.ActivationResult result = service.activateGoverned(
                "product-surfaces", 3, checksum,
                "platform-release-manager", 0, "CHG-1001");

        assertThat(result.revision()).isEqualTo(1);
        verify(repository).insertActivationEvent(
                "product-surfaces", null, bundleId,
                "ACTIVATE", 0, "platform-release-manager");
        verify(repository).insertGovernanceEvent(
                target, "ACTIVATE", 0L, 1L,
                "change-owner", "platform-release-manager", "CHG-1001", null,
                "dwp-platform-server");
    }

    @Test
    void governedActivationRejectsChecksumMismatchBeforePointerMutation() {
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 3, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 3)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.activateGoverned(
                "product-surfaces", 3, "b".repeat(64),
                "platform-release-manager", 0, "CHG-1001"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("checksum");

        verify(repository, never()).markActive(bundleId);
    }

    @Test
    void governedActivationRejectsTheStoredApproverAsReleaseActor() {
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 3, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 3)).thenReturn(Optional.of(target));
        when(repository.findGovernedApprovalEvidence(target))
                .thenReturn(Optional.of(approval("CHG-1001")));

        assertThatThrownBy(() -> service.activateGoverned(
                "product-surfaces", 3, "a".repeat(64),
                "Reviewer", 0, "CHG-1001"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("must all differ");

        verify(repository, never()).markActive(bundleId);
    }

    @Test
    void governedActivationFailsClosedOnAStalePointerRevision() {
        String checksum = "b".repeat(64);
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 3, "APPROVED", checksum);
        doReturn(Optional.of(target))
                .doReturn(Optional.of(target))
                .when(repository).lock("product-surfaces", 3);
        when(repository.findGovernedApprovalEvidence(target))
                .thenReturn(Optional.of(approval("CHG-1001")));
        when(repository.lockActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", UUID.randomUUID(), 8, "current-release",
                        OffsetDateTime.now(ZoneOffset.UTC))));

        assertThatThrownBy(() -> service.activateGoverned(
                "product-surfaces", 3, checksum,
                "platform-release-manager", 7, "CHG-1001"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("revision changed");

        verify(repository, never()).markActive(bundleId);
    }

    @Test
    void governedActivationRejectsLegacyApprovalWithoutProviderGovernanceEvidence() {
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 3, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 3)).thenReturn(Optional.of(target));
        when(repository.findGovernedApprovalEvidence(target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateGoverned(
                "product-surfaces", 3, "a".repeat(64),
                "platform-release-manager", 0, "CHG-1001"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("governed approval evidence");

        verify(repository, never()).markActive(bundleId);
    }

    @Test
    void governedActivationRejectsTheOriginalRequesterAsReleaseActor() {
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 3, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 3)).thenReturn(Optional.of(target));
        when(repository.findGovernedApprovalEvidence(target))
                .thenReturn(Optional.of(approval("CHG-1001")));

        assertThatThrownBy(() -> service.activateGoverned(
                "product-surfaces", 3, "a".repeat(64),
                "change-owner", 0, "CHG-1001"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("must all differ");

        verify(repository, never()).markActive(bundleId);
    }

    @Test
    void governedActivationRequiresTheApprovedChangeReference() {
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 3, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 3)).thenReturn(Optional.of(target));
        when(repository.findGovernedApprovalEvidence(target))
                .thenReturn(Optional.of(approval("CHG-1001")));

        assertThatThrownBy(() -> service.activateGoverned(
                "product-surfaces", 3, "a".repeat(64),
                "platform-release-manager", 0, "CHG-OTHER"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("change reference");

        verify(repository, never()).markActive(bundleId);
    }

    @Test
    void governedActivationCanReactivateARolledBackBundleAtANewCasRevision() {
        String checksum = "b".repeat(64);
        UUID currentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle current =
                stored(currentId, 1, "ACTIVE", "a".repeat(64));
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(targetId, 2, "APPROVED", checksum);
        ProductAuthorizationContractRepository.ActivePointer pointer =
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", currentId, 3, "incident-manager",
                        OffsetDateTime.now(ZoneOffset.UTC));
        doReturn(Optional.of(target))
                .doReturn(Optional.of(target))
                .when(repository).lock("product-surfaces", 2);
        when(repository.findGovernedApprovalEvidence(target))
                .thenReturn(Optional.of(approval("CHG-0002")));
        when(repository.lockActivePointer("product-surfaces")).thenReturn(Optional.of(pointer));
        when(repository.lock(currentId)).thenReturn(Optional.of(current));
        when(repository.markApproved(currentId)).thenReturn(true);
        when(repository.markActive(targetId)).thenReturn(true);
        when(repository.replaceActivePointer(
                "product-surfaces", targetId, "platform-release-manager", 3))
                .thenReturn(true);

        ProductAuthorizationContractDtos.ActivationResult result = service.activateGoverned(
                "product-surfaces", 2, checksum,
                "platform-release-manager", 3, "CHG-0002");

        assertThat(result.revision()).isEqualTo(4);
        verify(repository).insertGovernanceEvent(
                target, "ACTIVATE", 3L, 4L,
                "change-owner", "platform-release-manager", "CHG-0002", null,
                "dwp-platform-server");
    }

    @Test
    void createsTheFirstActivePointerOnlyFromAnApprovedBundle() {
        UUID bundleId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle target =
                stored(bundleId, 1, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 1)).thenReturn(Optional.of(target));
        when(repository.lockActivePointer("product-surfaces")).thenReturn(Optional.empty());
        when(repository.markActive(bundleId)).thenReturn(true);

        ProductAuthorizationContractDtos.ActivationResult result =
                service.activate("product-surfaces", 1, "security-reviewer", 0);

        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.operation()).isEqualTo("ACTIVATE");
        verify(repository).insertActivePointer(
                "product-surfaces", bundleId, "security-reviewer", 1);
        verify(repository).insertActivationEvent(
                "product-surfaces", null, bundleId, "ACTIVATE", 0, "security-reviewer");
    }

    @Test
    void rollsBackOnlyToTheImmediatelyPreviousApprovedVersion() {
        UUID currentId = UUID.randomUUID();
        UUID previousId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle current =
                stored(currentId, 2, "ACTIVE", "b".repeat(64));
        ProductAuthorizationContractRepository.StoredBundle previous =
                stored(previousId, 1, "APPROVED", "a".repeat(64));
        ProductAuthorizationContractRepository.ActivePointer pointer =
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", currentId, 7, "release", OffsetDateTime.now(ZoneOffset.UTC));
        when(repository.lockActivePointer("product-surfaces")).thenReturn(Optional.of(pointer));
        when(repository.lock(currentId)).thenReturn(Optional.of(current));
        when(repository.findImmediatePreviousApproved("product-surfaces", 2))
                .thenReturn(Optional.of(previous));
        when(repository.lock(previousId)).thenReturn(Optional.of(previous));
        when(repository.markApproved(currentId)).thenReturn(true);
        when(repository.markActive(previousId)).thenReturn(true);
        when(repository.replaceActivePointer(
                "product-surfaces", previousId, "release-manager", 7)).thenReturn(true);

        ProductAuthorizationContractDtos.ActivationResult result = service.rollback(
                "product-surfaces", 1, "release-manager", 7);

        assertThat(result.operation()).isEqualTo("ROLLBACK");
        assertThat(result.revision()).isEqualTo(8);
        verify(repository).insertActivationEvent(
                "product-surfaces", currentId, previousId,
                "ROLLBACK", 7, "release-manager");
    }

    @Test
    void governedRollbackPreservesExactPreviousApprovalAndAuditsTheReason() {
        String previousChecksum = "a".repeat(64);
        UUID currentId = UUID.randomUUID();
        UUID previousId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle current =
                stored(currentId, 3, "ACTIVE", "c".repeat(64));
        ProductAuthorizationContractRepository.StoredBundle previous =
                stored(previousId, 2, "APPROVED", previousChecksum);
        ProductAuthorizationContractRepository.ActivePointer pointer =
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", currentId, 7, "release",
                        OffsetDateTime.now(ZoneOffset.UTC));
        when(repository.lock("product-surfaces", 2)).thenReturn(Optional.of(previous));
        when(repository.findGovernedApprovalEvidence(previous))
                .thenReturn(Optional.of(approval("CHG-0002")));
        when(repository.lockActivePointer("product-surfaces")).thenReturn(Optional.of(pointer));
        when(repository.lock(currentId)).thenReturn(Optional.of(current));
        when(repository.findImmediatePreviousApproved("product-surfaces", 3))
                .thenReturn(Optional.of(previous));
        when(repository.lock(previousId)).thenReturn(Optional.of(previous));
        when(repository.markApproved(currentId)).thenReturn(true);
        when(repository.markActive(previousId)).thenReturn(true);
        when(repository.replaceActivePointer(
                "product-surfaces", previousId, "platform-release-manager", 7))
                .thenReturn(true);

        ProductAuthorizationContractDtos.ActivationResult result = service.rollbackGoverned(
                "product-surfaces", 2, previousChecksum,
                "platform-release-manager", 7, "INC-2002",
                "Rollback after failed release gate.");

        assertThat(result.revision()).isEqualTo(8);
        verify(repository).insertGovernanceEvent(
                previous, "ROLLBACK", 7L, 8L,
                "change-owner", "platform-release-manager", "INC-2002",
                "Rollback after failed release gate.", "dwp-platform-server");
    }

    @Test
    void governedRollbackRejectsTargetWithoutProviderGovernanceEvidence() {
        UUID previousId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle previous =
                stored(previousId, 2, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 2)).thenReturn(Optional.of(previous));
        when(repository.findGovernedApprovalEvidence(previous)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollbackGoverned(
                "product-surfaces", 2, "a".repeat(64),
                "platform-release-manager", 7, "INC-2002",
                "Rollback after failed release gate."))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("governed approval evidence");

        verify(repository, never()).markActive(previousId);
    }

    @Test
    void governedRollbackRejectsTheOriginalRequesterAsIncidentActor() {
        UUID previousId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle previous =
                stored(previousId, 2, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 2)).thenReturn(Optional.of(previous));
        when(repository.findGovernedApprovalEvidence(previous))
                .thenReturn(Optional.of(approval("CHG-0002")));

        assertThatThrownBy(() -> service.rollbackGoverned(
                "product-surfaces", 2, "a".repeat(64),
                "change-owner", 7, "INC-2002",
                "Rollback after failed release gate."))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("must all differ");

        verify(repository, never()).markActive(previousId);
    }

    @Test
    void governedRollbackRejectsTheTargetApproverAsIncidentActor() {
        UUID previousId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle previous =
                stored(previousId, 2, "APPROVED", "a".repeat(64));
        when(repository.lock("product-surfaces", 2)).thenReturn(Optional.of(previous));
        when(repository.findGovernedApprovalEvidence(previous))
                .thenReturn(Optional.of(approval("CHG-0002")));

        assertThatThrownBy(() -> service.rollbackGoverned(
                "product-surfaces", 2, "a".repeat(64),
                "Reviewer", 7, "INC-2002",
                "Rollback after failed release gate."))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("must all differ");

        verify(repository, never()).markActive(previousId);
    }

    @Test
    void rejectsRollbackThatSkipsThePreviousApprovedVersion() {
        UUID currentId = UUID.randomUUID();
        ProductAuthorizationContractRepository.StoredBundle current =
                stored(currentId, 3, "ACTIVE", "c".repeat(64));
        ProductAuthorizationContractRepository.StoredBundle previous =
                stored(UUID.randomUUID(), 2, "APPROVED", "b".repeat(64));
        when(repository.lockActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", currentId, 4, "release",
                        OffsetDateTime.now(ZoneOffset.UTC))));
        when(repository.lock(currentId)).thenReturn(Optional.of(current));
        when(repository.findImmediatePreviousApproved("product-surfaces", 3))
                .thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> service.rollback(
                "product-surfaces", 1, "release-manager", 4))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("immediately previous");
    }

    private ProductAuthorizationContractDtos.BundleContract contract(String checksum) {
        return new ProductAuthorizationContractDtos.BundleContract(
                1, "product-surfaces", 1, "DRAFT", "Identity + Security",
                "SHA-256", checksum, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private ProductAuthorizationContractRepository.StoredBundle stored(
            UUID id, long version, String status, String checksum) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ProductAuthorizationContractRepository.StoredBundle(
                id, "product-surfaces", version, status, 1, "SHA-256", checksum,
                "Identity + Security", status.equals("DRAFT") ? null : "reviewer",
                status.equals("DRAFT") ? null : now,
                status.equals("ACTIVE") ? now : null, now);
    }

    private ProductAuthorizationContractRepository.GovernedApprovalEvidence approval(
            String changeRef) {
        return new ProductAuthorizationContractRepository.GovernedApprovalEvidence(
                "change-owner", "reviewer", changeRef,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
