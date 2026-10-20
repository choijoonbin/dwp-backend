package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;

import java.util.Objects;
import java.util.Optional;

/**
 * Current signed handler-manifest authority for SYS-006.
 *
 * <p>No production binding is supplied by this contract slice. Callers must resolve the exact
 * three-part identity at every revalidation boundary; an unavailable, inactive or mismatched
 * registry response is deliberately represented as no authority.
 */
@FunctionalInterface
public interface AutomationHandlerRegistryPort {

    Optional<SignedManifest> findCurrent(HandlerIdentity identity);

    /** Performs a fresh exact-match lookup and fails closed for every unavailable response. */
    default Optional<AutomationHandlerManifestV1> verifyCurrentActive(
            SignedManifest approvedEvidence) {
        if (approvedEvidence == null) return Optional.empty();
        Optional<SignedManifest> resolved;
        try {
            resolved = findCurrent(approvedEvidence.identity());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
        if (resolved == null) return Optional.empty();
        return resolved
                .filter(current -> current.exactActiveMatch(approvedEvidence))
                .map(SignedManifest::manifest);
    }

    record HandlerIdentity(
            String ownerService,
            String handlerKey,
            String handlerContractVersion) {

        public HandlerIdentity {
            requireText(ownerService, "ownerService");
            requireText(handlerKey, "handlerKey");
            requireText(handlerContractVersion, "handlerContractVersion");
        }
    }

    /**
     * Cryptographically revisioned registry evidence around the canonical PDX_004 manifest.
     * The additional digests are authority evidence and do not duplicate the manifest payload.
     */
    record SignedManifest(
            AutomationHandlerManifestV1 manifest,
            String signatureKeyId,
            String inputSchemaDigest,
            String receiptSchemaDigest,
            String compensationPolicyDigest,
            long manifestRevision) {

        public SignedManifest {
            Objects.requireNonNull(manifest, "manifest must not be null");
            requireText(signatureKeyId, "signatureKeyId");
            requireDigest(inputSchemaDigest, "inputSchemaDigest");
            requireDigest(receiptSchemaDigest, "receiptSchemaDigest");
            requireDigest(compensationPolicyDigest, "compensationPolicyDigest");
            if (manifestRevision < 1L
                    || manifestRevision > Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER) {
                throw new IllegalArgumentException(
                        "manifestRevision must be a positive JSON-safe integer");
            }
        }

        public HandlerIdentity identity() {
            return new HandlerIdentity(
                    manifest.ownerService(), manifest.handlerKey(), manifest.contractVersion());
        }

        boolean exactActiveMatch(SignedManifest approved) {
            if (approved == null
                    || !"ACTIVE".equals(manifest.state())
                    || !"ACTIVE".equals(approved.manifest().state())) {
                return false;
            }
            return manifest.equals(approved.manifest())
                    && signatureKeyId.equals(approved.signatureKeyId())
                    && inputSchemaDigest.equals(approved.inputSchemaDigest())
                    && receiptSchemaDigest.equals(approved.receiptSchemaDigest())
                    && compensationPolicyDigest.equals(approved.compensationPolicyDigest())
                    && manifestRevision == approved.manifestRevision();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
    }
}
