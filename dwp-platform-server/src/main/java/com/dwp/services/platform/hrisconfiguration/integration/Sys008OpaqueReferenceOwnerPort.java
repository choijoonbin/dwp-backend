package com.dwp.services.platform.hrisconfiguration.integration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-only resolution port for the eight sealed opaque-reference types.
 * Every denial and provider failure returns the same empty result.
 */
public interface Sys008OpaqueReferenceOwnerPort {

    int PRODUCTION_PROVIDER_BINDING_COUNT = 0;
    String REQUIRED_PURPOSE = "HRIS_INTEGRATION_ADMIN";

    Optional<ResolvedReference> findOpaque(String opaqueReference);

    default Optional<ResolvedReference> resolveAuthorized(ResolutionRequest request) {
        if (request == null || !request.authenticatedAndScopeAuthorized()
                || !REQUIRED_PURPOSE.equals(request.purpose())) {
            return Optional.empty();
        }
        try {
            return findOpaque(request.opaqueReference()).filter(resolved ->
                    resolved.tenantId() == request.tenantId()
                            && resolved.connectorId().equals(request.connectorId())
                            && resolved.subjectRef().equals(request.subjectRef())
                            && resolved.purpose().equals(request.purpose())
                            && resolved.type() == request.type()
                            && resolved.namespace().equals(request.namespace())
                            && resolved.canonicalDigest().equals(
                                    request.canonicalDigest())
                            && resolved.authorizationRevision()
                                    == request.authorizationRevision()
                            && resolved.constraint().equals(request.constraint()));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    enum ReferenceType {
        SECRET_REF("secretRef"),
        SAMPLE_REF("sampleRef"),
        MAPPING_REF("mappingRef"),
        PAYLOAD_REF("payloadRef"),
        PROVIDER_RECEIPT_REF("providerReceiptRef"),
        COMPENSATION_POLICY_REF("compensationPolicyRef"),
        RESULT_REF("resultRef"),
        ERROR_REPORT_REF("errorReportRef");

        private final String wireField;

        ReferenceType(String wireField) {
            this.wireField = wireField;
        }

        public String wireField() {
            return wireField;
        }
    }

    record ResolutionRequest(
            String opaqueReference,
            long tenantId,
            UUID connectorId,
            String subjectRef,
            String purpose,
            ReferenceType type,
            String namespace,
            String canonicalDigest,
            long authorizationRevision,
            ReferenceConstraint constraint,
            boolean authenticatedAndScopeAuthorized) {

        public ResolutionRequest {
            requireBinding(opaqueReference, tenantId, connectorId, subjectRef,
                    purpose, type, namespace, canonicalDigest,
                    authorizationRevision);
            requireConstraint(type, constraint);
        }
    }

    record ResolvedReference(
            String opaqueReference,
            long tenantId,
            UUID connectorId,
            String subjectRef,
            String purpose,
            ReferenceType type,
            String namespace,
            String canonicalDigest,
            long authorizationRevision,
            ReferenceConstraint constraint,
            NonExportableProviderHandle providerHandle) {

        public ResolvedReference {
            requireBinding(opaqueReference, tenantId, connectorId, subjectRef,
                    purpose, type, namespace, canonicalDigest,
                    authorizationRevision);
            requireConstraint(type, constraint);
            if (!REQUIRED_PURPOSE.equals(purpose)) {
                throw new IllegalArgumentException("resolved purpose is not sealed");
            }
            Objects.requireNonNull(providerHandle,
                    "providerHandle must not be null");
        }
    }

    /** Contains only a provider-side handle identifier, never a secret value. */
    record NonExportableProviderHandle(String handleId) {
        public NonExportableProviderHandle {
            if (handleId == null
                    || !handleId.matches("^[A-Za-z0-9][A-Za-z0-9:/._-]{0,255}$")) {
                throw new IllegalArgumentException(
                        "provider handle identifier is invalid");
            }
        }
    }

    sealed interface ReferenceConstraint permits UnpinnedReferenceConstraint,
            ProviderReceiptConstraint, CompensationPolicyConstraint {}

    enum UnpinnedReferenceConstraint implements ReferenceConstraint {
        INSTANCE
    }

    record ProviderReceiptConstraint(
            UUID executionId,
            String providerKey,
            String originalProviderIdempotencyKey) implements ReferenceConstraint {

        public ProviderReceiptConstraint {
            Objects.requireNonNull(executionId, "executionId must not be null");
            if (!token(providerKey) || !token(originalProviderIdempotencyKey)) {
                throw new IllegalArgumentException(
                        "provider receipt pin is invalid");
            }
        }
    }

    record CompensationPolicyConstraint(
            UUID executedDefinitionId,
            long executedDefinitionVersion,
            String providerOperation,
            boolean approved) implements ReferenceConstraint {

        public CompensationPolicyConstraint {
            Objects.requireNonNull(executedDefinitionId,
                    "executedDefinitionId must not be null");
            if (executedDefinitionVersion < 1 || !token(providerOperation)
                    || !approved) {
                throw new IllegalArgumentException(
                        "compensation policy must be approved and execution pinned");
            }
        }
    }

    private static void requireBinding(
            String opaqueReference,
            long tenantId,
            UUID connectorId,
            String subjectRef,
            String purpose,
            ReferenceType type,
            String namespace,
            String canonicalDigest,
            long authorizationRevision) {
        if (!validOpaqueReference(opaqueReference, type)) {
            throw new IllegalArgumentException("opaque reference is invalid");
        }
        if (tenantId < 1 || authorizationRevision < 1) {
            throw new IllegalArgumentException(
                    "tenant and authorization revision must be positive");
        }
        Objects.requireNonNull(connectorId, "connectorId must not be null");
        if (subjectRef == null
                || !subjectRef.matches("^[A-Za-z0-9][A-Za-z0-9:/._-]{0,511}$")) {
            throw new IllegalArgumentException("subjectRef is invalid");
        }
        if (purpose == null
                || !purpose.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("purpose is invalid");
        }
        Objects.requireNonNull(type, "type must not be null");
        if (namespace == null
                || !namespace.matches("^[A-Za-z][A-Za-z0-9._-]{0,127}$")) {
            throw new IllegalArgumentException("namespace is invalid");
        }
        if (canonicalDigest == null
                || !canonicalDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("canonicalDigest is invalid");
        }
    }

    private static boolean validOpaqueReference(
            String reference, ReferenceType type) {
        if (reference == null || type == null
                || reference.contains("://") || reference.startsWith("/")
                || reference.contains("?") || reference.contains("#")) {
            return false;
        }
        return switch (type) {
            case SECRET_REF -> reference.matches(
                    "^secret_[A-Za-z0-9_-]{16,128}$");
            case SAMPLE_REF, PAYLOAD_REF -> validCanonicalReference(
                    reference, 256);
            case RESULT_REF, ERROR_REPORT_REF -> validCanonicalReference(
                    reference, 512);
            case COMPENSATION_POLICY_REF -> reference.matches(
                    "^policy_[A-Za-z0-9_-]{16,200}$");
            case MAPPING_REF, PROVIDER_RECEIPT_REF -> reference.matches(
                    "^ref_[A-Za-z0-9_-]{16,240}$");
        };
    }

    private static boolean validCanonicalReference(
            String reference, int maximumLength) {
        return reference.length() <= maximumLength
                && reference.matches("^[A-Za-z0-9][A-Za-z0-9:/._-]{0,511}$");
    }

    private static void requireConstraint(
            ReferenceType type, ReferenceConstraint constraint) {
        Objects.requireNonNull(constraint, "constraint must not be null");
        if (type == ReferenceType.PROVIDER_RECEIPT_REF
                && !(constraint instanceof ProviderReceiptConstraint)) {
            throw new IllegalArgumentException(
                    "provider receipt must be execution and provider pinned");
        }
        if (type == ReferenceType.COMPENSATION_POLICY_REF
                && !(constraint instanceof CompensationPolicyConstraint)) {
            throw new IllegalArgumentException(
                    "compensation policy must be execution pinned");
        }
        if (type != ReferenceType.PROVIDER_RECEIPT_REF
                && type != ReferenceType.COMPENSATION_POLICY_REF
                && constraint != UnpinnedReferenceConstraint.INSTANCE) {
            throw new IllegalArgumentException(
                    "reference constraint does not match its type");
        }
    }

    private static boolean token(String value) {
        return value != null
                && value.matches("^[A-Za-z0-9][A-Za-z0-9:/._-]{0,255}$");
    }
}
