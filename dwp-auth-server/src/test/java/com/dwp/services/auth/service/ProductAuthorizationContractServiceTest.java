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
}
