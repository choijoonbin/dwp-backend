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

    private static final String PROVIDER_SERVICE_IDENTITY = "dwp-provider-server";
    private static final String PLATFORM_SERVICE_IDENTITY = "dwp-platform-server";

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

    /**
     * Production/shared approval lane. Import remains a separate, non-authoritative
     * operation; this transition binds an exact immutable checksum to independent
     * requester/approver evidence and never changes the active pointer.
     */
    @Transactional
    public ProductAuthorizationContractDtos.BundleView approveGoverned(
            String bundleKey,
            long version,
            String checksum,
            String requestedBy,
            String approvedBy,
            String changeRef) {
        String requester = normalizedRef(requestedBy, "requester");
        String approver = normalizedRef(approvedBy, "approver");
        String change = normalizedChangeRef(changeRef);
        requireSeparated(requester, approver,
                "Authorization bundle requester and approver must differ.");
        repository.lockBundleKey(bundleKey);
        ProductAuthorizationContractRepository.StoredBundle target =
                requireBundle(repository.lock(bundleKey, version));
        requireChecksum(target, checksum);

        if ("APPROVED".equals(target.bundleStatus()) || "ACTIVE".equals(target.bundleStatus())) {
            if (!sameActor(target.approvedBy(), approver)
                    || !repository.hasGovernanceEvent(
                            target.bundleId(),
                            "APPROVE",
                            requester,
                            approver,
                            change,
                            null,
                            PROVIDER_SERVICE_IDENTITY)) {
                throw conflict("The bundle already carries different immutable approval evidence.");
            }
            return view(target);
        }
        if (!"DRAFT".equals(target.bundleStatus())
                || !repository.approve(target.bundleId(), approver)) {
            throw conflict("Only an exact DRAFT authorization bundle can be approved.");
        }
        ProductAuthorizationContractRepository.StoredBundle approved =
                requireBundle(repository.lock(bundleKey, version));
        repository.insertGovernanceEvent(
                approved,
                "APPROVE",
                null,
                null,
                requester,
                approver,
                change,
                null,
                PROVIDER_SERVICE_IDENTITY);
        return view(approved);
    }

    /**
     * Production/shared release lane. Approval evidence is reused as the maker
     * evidence and the independently authenticated platform actor is the checker.
     */
    @Transactional
    public ProductAuthorizationContractDtos.ActivationResult activateGoverned(
            String bundleKey,
            long version,
            String checksum,
            String activatedBy,
            long expectedRevision,
            String changeRef) {
        String activator = normalizedRef(activatedBy, "activation actor");
        String change = normalizedChangeRef(changeRef);
        if (expectedRevision < 0) {
            throw invalid("Active pointer revision cannot be negative.");
        }
        repository.lockBundleKey(bundleKey);
        ProductAuthorizationContractRepository.StoredBundle target =
                requireBundle(repository.lock(bundleKey, version));
        requireChecksum(target, checksum);
        ProductAuthorizationContractRepository.GovernedApprovalEvidence approval =
                requireGovernedApprovalEvidence(target);
        requireReleaseActorSeparated(approval, activator, "activator");
        if (!approval.changeRef().equals(change)) {
            throw conflict(
                    "Activation change reference must match the governed approval evidence.");
        }

        ProductAuthorizationContractDtos.ActivationResult result = activate(
                bundleKey, version, activator, expectedRevision);
        if (result.revision() == expectedRevision + 1) {
            repository.insertGovernanceEvent(
                    target,
                    "ACTIVATE",
                    expectedRevision,
                    result.revision(),
                    approval.requestedBy(),
                    activator,
                    change,
                    null,
                    PLATFORM_SERVICE_IDENTITY);
        } else if (!repository.hasGovernanceEvent(
                target.bundleId(),
                "ACTIVATE",
                approval.requestedBy(),
                activator,
                change,
                result.revision(),
                PLATFORM_SERVICE_IDENTITY)) {
            throw conflict(
                    "An already-active bundle requires matching governed activation evidence.");
        }
        return result;
    }

    /**
     * Production/shared rollback lane. The target must be the exact immediately
     * previous approved bundle; rollback only moves the CAS pointer and preserves
     * every immutable bundle and audit event.
     */
    @Transactional
    public ProductAuthorizationContractDtos.ActivationResult rollbackGoverned(
            String bundleKey,
            long targetVersion,
            String checksum,
            String rolledBackBy,
            long expectedRevision,
            String changeRef,
            String reason) {
        String rollbackActor = normalizedRef(rolledBackBy, "rollback actor");
        String change = normalizedChangeRef(changeRef);
        String rollbackReason = normalizedReason(reason);
        if (expectedRevision < 1) {
            throw invalid("Rollback requires a positive active pointer revision.");
        }
        repository.lockBundleKey(bundleKey);
        ProductAuthorizationContractRepository.StoredBundle target =
                requireBundle(repository.lock(bundleKey, targetVersion));
        requireChecksum(target, checksum);
        ProductAuthorizationContractRepository.GovernedApprovalEvidence approval =
                requireGovernedApprovalEvidence(target);
        requireReleaseActorSeparated(approval, rollbackActor, "rollback actor");

        ProductAuthorizationContractDtos.ActivationResult result = rollback(
                bundleKey, targetVersion, rollbackActor, expectedRevision);
        repository.insertGovernanceEvent(
                target,
                "ROLLBACK",
                expectedRevision,
                result.revision(),
                approval.requestedBy(),
                rollbackActor,
                change,
                rollbackReason,
                PLATFORM_SERVICE_IDENTITY);
        return result;
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
        ProductAuthorizationContractRepository.BundleSnapshot snapshot =
                repository.findActiveSnapshot(bundleKey)
                        .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND,
                                "No active authorization bundle exists for the key."));
        return view(snapshot.bundle(), snapshot.activeRevision());
    }

    @Transactional(readOnly = true)
    public ProductAuthorizationContractDtos.BundleView version(String bundleKey, long version) {
        ProductAuthorizationContractRepository.BundleSnapshot snapshot =
                requireSnapshot(repository.findVersionSnapshot(bundleKey, version));
        return view(snapshot.bundle(), snapshot.activeRevision());
    }

    @Transactional(readOnly = true)
    public ProductAuthorizationContractDtos.GovernedBundlePreflight governedReleaseVersion(
            String bundleKey,
            long version) {
        ProductAuthorizationContractRepository.BundleSnapshot snapshot =
                requireSnapshot(repository.findVersionSnapshot(bundleKey, version));
        ProductAuthorizationContractRepository.StoredBundle bundle = snapshot.bundle();
        ProductAuthorizationContractRepository.GovernedApprovalEvidence evidence =
                requireGovernedApprovalEvidence(bundle);
        return new ProductAuthorizationContractDtos.GovernedBundlePreflight(
                view(bundle, snapshot.activeRevision()),
                new ProductAuthorizationContractDtos.GovernedApprovalEvidence(
                        evidence.requestedBy(),
                        evidence.approvedBy(),
                        evidence.changeRef(),
                        evidence.approvedAt()));
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

    private ProductAuthorizationContractDtos.BundleView view(
            ProductAuthorizationContractRepository.StoredBundle bundle,
            long activeRevision) {
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

    private ProductAuthorizationContractRepository.BundleSnapshot requireSnapshot(
            Optional<ProductAuthorizationContractRepository.BundleSnapshot> value) {
        return value.orElseThrow(() -> new BaseException(
                ErrorCode.NOT_FOUND, "The authorization bundle was not found."));
    }

    private void requireActor(String actorRef) {
        if (actorRef == null || actorRef.isBlank()) {
            throw invalid("An approval or activation actor reference is required.");
        }
    }

    private void requireChecksum(
            ProductAuthorizationContractRepository.StoredBundle bundle,
            String checksum) {
        if (checksum == null || !checksum.matches("^[0-9a-f]{64}$")) {
            throw invalid("An exact lowercase SHA-256 checksum is required.");
        }
        if (!bundle.checksum().equals(checksum)) {
            throw conflict("The immutable authorization bundle checksum does not match.");
        }
    }

    private void requireApprovedEvidence(
            ProductAuthorizationContractRepository.StoredBundle bundle) {
        if (!("APPROVED".equals(bundle.bundleStatus())
                || "ACTIVE".equals(bundle.bundleStatus()))
                || bundle.approvedBy() == null
                || bundle.approvedBy().isBlank()
                || bundle.approvedAt() == null) {
            throw conflict("Independent authorization bundle approval evidence is required.");
        }
    }

    private ProductAuthorizationContractRepository.GovernedApprovalEvidence
            requireGovernedApprovalEvidence(
                    ProductAuthorizationContractRepository.StoredBundle bundle) {
        requireApprovedEvidence(bundle);
        ProductAuthorizationContractRepository.GovernedApprovalEvidence evidence =
                repository.findGovernedApprovalEvidence(bundle)
                        .orElseThrow(() -> conflict(
                                "Provider governed approval evidence is required."));
        if (!sameActor(bundle.approvedBy(), evidence.approvedBy())
                || evidence.requestedBy() == null
                || evidence.requestedBy().isBlank()
                || evidence.approvedAt() == null
                || evidence.changeRef() == null
                || evidence.changeRef().isBlank()
                || sameActor(evidence.requestedBy(), evidence.approvedBy())) {
            throw conflict("Governed approval evidence is incomplete or inconsistent.");
        }
        return evidence;
    }

    private void requireReleaseActorSeparated(
            ProductAuthorizationContractRepository.GovernedApprovalEvidence approval,
            String releaseActor,
            String purpose) {
        if (sameActor(approval.requestedBy(), releaseActor)
                || sameActor(approval.approvedBy(), releaseActor)) {
            throw invalid("Authorization bundle requester, approver and "
                    + purpose + " must all differ.");
        }
    }

    private void requireSeparated(String first, String second, String message) {
        if (sameActor(first, second)) throw invalid(message);
    }

    private boolean sameActor(String first, String second) {
        return first != null && second != null
                && first.strip().equalsIgnoreCase(second.strip());
    }

    private String normalizedRef(String value, String purpose) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("^[A-Za-z0-9][A-Za-z0-9@._:/+\\-]{0,159}$")) {
            throw invalid("A valid " + purpose + " reference is required.");
        }
        return normalized;
    }

    private String normalizedChangeRef(String value) {
        String normalized = normalizedRef(value, "change");
        if (normalized.length() < 3) {
            throw invalid("A change reference between 3 and 160 characters is required.");
        }
        return normalized;
    }

    private String normalizedReason(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 10 || normalized.length() > 1000) {
            throw invalid("A rollback reason between 10 and 1000 characters is required.");
        }
        return normalized;
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
