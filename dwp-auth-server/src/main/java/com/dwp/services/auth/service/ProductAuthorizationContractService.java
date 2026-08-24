package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ProductAuthorizationContractService {

    private final ProductAuthorizationContractRepository repository;
    private final ProductAuthorizationContractValidator validator;

    public ProductAuthorizationContractService(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationContractValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public ProductAuthorizationContractDtos.BundleView importDraft(
            ProductAuthorizationContractDtos.BundleContract contract) {
        validator.validate(contract);
        if (!"DRAFT".equals(contract.bundleStatus())) {
            throw invalid("Generated registry seeds must enter the database as DRAFT.");
        }
        repository.lockBundleKey(contract.bundleKey());
        Optional<ProductAuthorizationContractRepository.StoredBundle> existing =
                repository.lock(contract.bundleKey(), contract.version());
        if (existing.isPresent()) {
            if (!existing.get().checksum().equals(contract.checksum())) {
                throw conflict("An immutable bundle version already exists with a different checksum.");
            }
            return view(existing.get());
        }
        UUID bundleId = repository.insertDraft(contract);
        ProductAuthorizationContractRepository.StoredBundle inserted = repository.lock(bundleId)
                .orElseThrow(() -> new IllegalStateException("Inserted authorization bundle disappeared."));
        return view(inserted);
    }

    @Transactional
    public ProductAuthorizationContractDtos.BundleView approve(
            String bundleKey, long version, String approver) {
        requireActor(approver);
        repository.lockBundleKey(bundleKey);
        ProductAuthorizationContractRepository.StoredBundle bundle =
                requireBundle(repository.lock(bundleKey, version));
        if ("APPROVED".equals(bundle.bundleStatus()) || "ACTIVE".equals(bundle.bundleStatus())) {
            return view(bundle);
        }
        if (!"DRAFT".equals(bundle.bundleStatus()) || !repository.approve(bundle.bundleId(), approver)) {
            throw conflict("Only a DRAFT authorization bundle can be approved.");
        }
        return view(requireBundle(repository.find(bundleKey, version)));
    }

    @Transactional
    public ProductAuthorizationContractDtos.ActivationResult activate(
            String bundleKey,
            long version,
            String actorRef,
            long expectedRevision) {
        requireActor(actorRef);
        if (expectedRevision < 0) throw invalid("Active pointer revision cannot be negative.");
        repository.lockBundleKey(bundleKey);
        ProductAuthorizationContractRepository.StoredBundle target =
                requireBundle(repository.lock(bundleKey, version));
        if (!"APPROVED".equals(target.bundleStatus()) && !"ACTIVE".equals(target.bundleStatus())) {
            throw conflict("Only an approved authorization bundle can be activated.");
        }
        Optional<ProductAuthorizationContractRepository.ActivePointer> pointer =
                repository.lockActivePointer(bundleKey);
        if (pointer.isEmpty()) {
            if (expectedRevision != 0 || !"APPROVED".equals(target.bundleStatus())) {
                throw conflict("The authorization active pointer revision changed.");
            }
            requireChanged(repository.markActive(target.bundleId()),
                    "The approved bundle could not be activated.");
            repository.insertActivePointer(bundleKey, target.bundleId(), actorRef, 1);
            repository.insertActivationEvent(
                    bundleKey, null, target.bundleId(), "ACTIVATE", 0, actorRef);
            return result(target, "ACTIVATE", 1);
        }

        ProductAuthorizationContractRepository.ActivePointer active = pointer.get();
        if (active.revision() != expectedRevision) {
            throw conflict("The authorization active pointer revision changed.");
        }
        if (active.bundleId().equals(target.bundleId())) {
            return result(target, "ACTIVATE", active.revision());
        }
        ProductAuthorizationContractRepository.StoredBundle current =
                requireBundle(repository.lock(active.bundleId()));
        if (target.version() <= current.version()) {
            throw conflict("Activation must advance to a newer approved bundle; use rollback otherwise.");
        }
        switchPointer(current, target, actorRef, expectedRevision, "ACTIVATE");
        return result(target, "ACTIVATE", expectedRevision + 1);
    }

    @Transactional
    public ProductAuthorizationContractDtos.ActivationResult rollback(
            String bundleKey,
            long targetVersion,
            String actorRef,
            long expectedRevision) {
        requireActor(actorRef);
        repository.lockBundleKey(bundleKey);
        ProductAuthorizationContractRepository.ActivePointer pointer =
                repository.lockActivePointer(bundleKey)
                        .orElseThrow(() -> conflict("There is no active bundle to roll back."));
        if (pointer.revision() != expectedRevision) {
            throw conflict("The authorization active pointer revision changed.");
        }
        ProductAuthorizationContractRepository.StoredBundle current =
                requireBundle(repository.lock(pointer.bundleId()));
        ProductAuthorizationContractRepository.StoredBundle previous = repository
                .findImmediatePreviousApproved(bundleKey, current.version())
                .orElseThrow(() -> conflict("There is no previous approved bundle."));
        if (previous.version() != targetVersion) {
            throw conflict("Rollback is restricted to the immediately previous approved bundle.");
        }
        previous = requireBundle(repository.lock(previous.bundleId()));
        switchPointer(current, previous, actorRef, expectedRevision, "ROLLBACK");
        return result(previous, "ROLLBACK", expectedRevision + 1);
    }

    @Transactional(readOnly = true)
    public ProductAuthorizationContractDtos.BundleView active(String bundleKey) {
        return view(repository.findActive(bundleKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND,
                        "No active authorization bundle exists for the key.")));
    }

    @Transactional(readOnly = true)
    public ProductAuthorizationContractDtos.BundleView version(String bundleKey, long version) {
        return view(requireBundle(repository.find(bundleKey, version)));
    }

    private void switchPointer(
            ProductAuthorizationContractRepository.StoredBundle current,
            ProductAuthorizationContractRepository.StoredBundle target,
            String actorRef,
            long expectedRevision,
            String operation) {
        requireChanged(repository.markApproved(current.bundleId()),
                "The current active bundle state changed.");
        requireChanged(repository.markActive(target.bundleId()),
                "The target approved bundle state changed.");
        requireChanged(repository.replaceActivePointer(
                        target.bundleKey(), target.bundleId(), actorRef, expectedRevision),
                "The authorization active pointer revision changed.");
        repository.insertActivationEvent(
                target.bundleKey(), current.bundleId(), target.bundleId(), operation,
                expectedRevision, actorRef);
    }

    private ProductAuthorizationContractDtos.BundleView view(
            ProductAuthorizationContractRepository.StoredBundle bundle) {
        long activeRevision = repository.findActivePointer(bundle.bundleKey())
                .filter(pointer -> pointer.bundleId().equals(bundle.bundleId()))
                .map(ProductAuthorizationContractRepository.ActivePointer::revision)
                .orElse(0L);
        return new ProductAuthorizationContractDtos.BundleView(
                bundle.bundleId(), bundle.bundleKey(), bundle.version(), bundle.bundleStatus(),
                activeRevision, bundle.checksum(), bundle.owner(), bundle.approvedBy(),
                bundle.approvedAt(), bundle.activatedAt(), repository.loadContract(bundle));
    }

    private ProductAuthorizationContractDtos.ActivationResult result(
            ProductAuthorizationContractRepository.StoredBundle bundle,
            String operation,
            long revision) {
        return new ProductAuthorizationContractDtos.ActivationResult(
                bundle.bundleKey(), bundle.version(), operation, revision, bundle.checksum());
    }

    private ProductAuthorizationContractRepository.StoredBundle requireBundle(
            Optional<ProductAuthorizationContractRepository.StoredBundle> value) {
        return value.orElseThrow(() -> new BaseException(
                ErrorCode.NOT_FOUND, "The authorization bundle was not found."));
    }

    private void requireActor(String actorRef) {
        if (actorRef == null || actorRef.isBlank()) {
            throw invalid("An approval or activation actor reference is required.");
        }
    }

    private void requireChanged(boolean changed, String message) {
        if (!changed) throw conflict(message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
