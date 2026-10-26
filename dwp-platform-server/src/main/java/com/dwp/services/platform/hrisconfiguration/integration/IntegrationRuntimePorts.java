package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;

import java.net.InetAddress;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fail-closed ports; no production provider implementation is bound in SYS-008. */
public final class IntegrationRuntimePorts {

    public static final int PRODUCTION_PROVIDER_BINDING_COUNT = 0;

    private IntegrationRuntimePorts() {}

    public interface DefinitionEvidencePort {
        ValidationEvidence validate(DefinitionValidationRequest request);

        ConnectionTestEvidence test(ConnectionTestRequest request);

        DryRunEvidence dryRun(DryRunRequest request);
    }

    /** Resolves policy, DNS and opaque handles after trusted owner authorization. */
    public interface BoundaryResolutionPort {
        ResolvedDefinitionBoundary resolveDefinition(
                long tenantId,
                UUID connectorId,
                long definitionVersion,
                long authorizationRevision,
                String purposeCode);

        ResolvedMappingBoundary resolveMapping(
                long tenantId,
                UUID connectorId,
                long mappingVersion,
                long authorizationRevision,
                String purposeCode);

        ResolvedPayloadManifest resolvePayload(
                long tenantId,
                UUID connectorId,
                UUID executionId,
                String payloadRef,
                String payloadDigest,
                long authorizationRevision,
                String purposeCode);
    }

    public interface ProviderEffectPort {
        ProviderEvidence dispatch(ProviderRequest request);

        ProviderEvidence reconcile(ReconcileRequest request);

        ProviderEvidence compensate(CompensationRequest request);
    }

    public interface OperationsExceptionPort {
        void record(OperationsException exception);
    }

    public record ResolvedDefinitionBoundary(
            String egressPolicyKey,
            URI endpoint,
            String secretRef,
            Sys008EgressBoundary.Allowlist allowlist,
            List<InetAddress> resolvedDestinations,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle secretHandle,
            long endpointPolicyRevision,
            long secretRevision) {

        public ResolvedDefinitionBoundary {
            if (egressPolicyKey == null
                    || !egressPolicyKey.matches("^[A-Za-z][A-Za-z0-9._-]{0,127}$")) {
                throw new IllegalArgumentException("egress policy key is invalid");
            }
            Objects.requireNonNull(endpoint, "endpoint must not be null");
            if (secretRef == null
                    || !secretRef.matches("^secret_[A-Za-z0-9_-]{16,128}$")) {
                throw new IllegalArgumentException("secret reference is invalid");
            }
            Objects.requireNonNull(allowlist, "allowlist must not be null");
            Objects.requireNonNull(secretHandle, "secretHandle must not be null");
            if (endpointPolicyRevision < 1 || secretRevision < 1) {
                throw new IllegalArgumentException(
                        "definition boundary revisions must be positive");
            }
            Objects.requireNonNull(resolvedDestinations,
                    "resolvedDestinations must not be null");
            resolvedDestinations = List.copyOf(resolvedDestinations);
        }
    }

    public record ResolvedMappingBoundary(
            String direction,
            String mappingRef,
            String mappingDigest,
            String sourceSchemaDigest,
            String targetSchemaDigest,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle mappingHandle) {

        public ResolvedMappingBoundary {
            if (!List.of("INBOUND", "OUTBOUND").contains(direction)) {
                throw new IllegalArgumentException("mapping direction is not sealed");
            }
            if (sourceSchemaDigest == null
                    || !sourceSchemaDigest.matches("^[a-f0-9]{64}$")
                    || targetSchemaDigest == null
                    || !targetSchemaDigest.matches("^[a-f0-9]{64}$")) {
                throw new IllegalArgumentException("schema digests are not sealed");
            }
        }
    }

    public record SourceManifestItem(
            String sourceKeyDigest,
            String sourceDigest) {

        public SourceManifestItem {
            if (sourceKeyDigest == null
                    || !sourceKeyDigest.matches("^[a-f0-9]{64}$")
                    || sourceDigest == null
                    || !sourceDigest.matches("^[a-f0-9]{64}$")) {
                throw new IllegalArgumentException(
                        "source manifest item digests are invalid");
            }
        }
    }

    public record ResolvedPayloadManifest(
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle payloadHandle,
            List<SourceManifestItem> items) {

        public ResolvedPayloadManifest {
            Objects.requireNonNull(payloadHandle,
                    "payloadHandle must not be null");
            items = List.copyOf(items);
            if (items.isEmpty() || items.size() > 10_000
                    || items.stream().map(SourceManifestItem::sourceKeyDigest)
                    .distinct().count() != items.size()
                    || !items.equals(items.stream()
                    .sorted(java.util.Comparator.comparing(
                            SourceManifestItem::sourceKeyDigest)).toList())) {
                throw new IllegalArgumentException(
                        "source manifest must be non-empty, unique and canonical");
            }
        }
    }

    public record DefinitionValidationRequest(
            long tenantId,
            UUID connectorId,
            long definitionVersion,
            String definitionDigest,
            URI endpoint,
            Sys008EgressBoundary.Allowlist allowlist,
            List<InetAddress> resolvedDestinations,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle secretHandle,
            long endpointPolicyRevision,
            long secretRevision) {

        public DefinitionValidationRequest {
            resolvedDestinations = List.copyOf(resolvedDestinations);
            Sys008EgressBoundary.requireAllowed(
                    endpoint, allowlist, resolvedDestinations);
        }
    }

    public record ConnectionTestRequest(
            DefinitionValidationRequest definition,
            String mode,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle sampleHandle,
            String sampleDigest) {}

    public record DryRunRequest(
            ConnectorExecutionRequestV1 execution,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle mappingHandle,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle sampleHandle,
            int maxRows,
            OffsetDateTime asOf) {}

    public record ValidationEvidence(
            boolean valid,
            String evidenceDigest,
            OffsetDateTime validatedAt,
            List<String> errors) {

        public ValidationEvidence {
            errors = List.copyOf(errors);
        }
    }

    public record ConnectionTestEvidence(
            boolean succeeded,
            String receiptRef,
            String receiptDigest,
            OffsetDateTime completedAt) {}

    public record DryRunEvidence(
            boolean valid,
            long rowCount,
            String resultRef,
            String resultDigest,
            OffsetDateTime completedAt,
            Map<String, Long> outcomeCounts) {

        public DryRunEvidence {
            outcomeCounts = Map.copyOf(outcomeCounts);
        }
    }

    public record ProviderRequest(
            ConnectorExecutionRequestV1 execution,
            String providerIdempotencyKey,
            String requestDigest,
            long attemptFence,
            URI endpoint,
            Sys008EgressBoundary.ResolvedEgressPin egressPin,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle secretHandle,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle payloadHandle,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle mappingHandle,
            List<ProviderItemAttempt> selectedAttempts) {

        public ProviderRequest {
            selectedAttempts = List.copyOf(selectedAttempts);
            if (selectedAttempts.isEmpty()) {
                throw new IllegalArgumentException(
                        "provider request requires selected attempts");
            }
        }
    }

    public record ProviderItemAttempt(
            UUID itemPublicId,
            String providerIdempotencyKey,
            String requestDigest,
            long attemptFence) {

        public ProviderItemAttempt {
            Objects.requireNonNull(itemPublicId, "itemPublicId must not be null");
            if (providerIdempotencyKey == null
                    || !providerIdempotencyKey.matches(
                    "^[A-Za-z0-9][A-Za-z0-9._:-]{15,159}$")
                    || requestDigest == null
                    || !requestDigest.matches("^[a-f0-9]{64}$")
                    || attemptFence < 1) {
                throw new IllegalArgumentException(
                        "provider item attempt is invalid");
            }
        }
    }

    public record ReconcileRequest(
            ProviderRequest original,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle
                    providerReceiptHandle,
            String providerReceiptDigest,
            long expectedSourceCount) {

        public ReconcileRequest {
            Objects.requireNonNull(original, "original must not be null");
            Objects.requireNonNull(providerReceiptHandle,
                    "providerReceiptHandle must not be null");
            if (providerReceiptDigest == null
                    || !providerReceiptDigest.matches("^[a-f0-9]{64}$")
                    || expectedSourceCount < 0) {
                throw new IllegalArgumentException(
                        "reconciliation evidence is invalid");
            }
        }
    }

    public record CompensationRequest(
            ProviderRequest original,
            Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle
                    compensationPolicyHandle,
            String compensationPolicyDigest,
            String reasonCode,
            String compensationIdempotencyKey,
            String commandRequestDigest) {

        public CompensationRequest {
            Objects.requireNonNull(original, "original must not be null");
            Objects.requireNonNull(compensationPolicyHandle,
                    "compensationPolicyHandle must not be null");
            if (compensationPolicyDigest == null
                    || !compensationPolicyDigest.matches("^[a-f0-9]{64}$")) {
                throw new IllegalArgumentException(
                        "compensation policy digest is invalid");
            }
            if (reasonCode == null
                    || !reasonCode.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
                throw new IllegalArgumentException(
                        "compensation reason is invalid");
            }
            if (compensationIdempotencyKey == null
                    || !compensationIdempotencyKey.matches(
                    "^[A-Za-z0-9][A-Za-z0-9._:-]{15,255}$")) {
                throw new IllegalArgumentException(
                        "compensation idempotency key is invalid");
            }
            if (commandRequestDigest == null
                    || !commandRequestDigest.matches("^[a-f0-9]{64}$")) {
                throw new IllegalArgumentException(
                        "compensation command digest is invalid");
            }
        }
    }

    public record ProviderEvidence(
            Outcome outcome,
            String providerIdempotencyKey,
            String requestDigest,
            String receiptRef,
            String receiptDigest,
            long sourceCount,
            long acceptedCount,
            long rejectedCount,
            long quarantinedCount,
            String reconciliationDigest,
            OffsetDateTime observedAt,
            List<ProviderItemEvidence> items) {

        public ProviderEvidence {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public ProviderEvidence(
                Outcome outcome,
                String providerIdempotencyKey,
                String requestDigest,
                String receiptRef,
                String receiptDigest,
                long sourceCount,
                long acceptedCount,
                long rejectedCount,
                long quarantinedCount,
                String reconciliationDigest,
                OffsetDateTime observedAt) {
            this(outcome, providerIdempotencyKey, requestDigest, receiptRef,
                    receiptDigest, sourceCount, acceptedCount, rejectedCount,
                    quarantinedCount, reconciliationDigest, observedAt, List.of());
        }

        public enum Outcome {
            ACKNOWLEDGED, RESULT_UNKNOWN, RECONCILED, PARTIAL, FAILED, CANCELLED
        }
    }

    public record ProviderItemEvidence(
            UUID itemPublicId,
            ProviderEvidence.Outcome outcome,
            String providerIdempotencyKey,
            String requestDigest,
            String receiptRef,
            String receiptDigest,
            String reconciliationDigest,
            boolean quarantined,
            String failureClass,
            boolean retryable) {

        public ProviderItemEvidence {
            Objects.requireNonNull(itemPublicId, "itemPublicId must not be null");
            Objects.requireNonNull(outcome, "item outcome must not be null");
        }
    }

    public record OperationsException(
            long tenantId,
            UUID executionId,
            String category,
            String evidenceDigest,
            OffsetDateTime observedAt) {

        public OperationsException {
            if (tenantId < 1 || executionId == null || category == null
                    || !category.matches("^[A-Z][A-Z0-9_]{2,79}$")
                    || evidenceDigest == null
                    || !evidenceDigest.matches("^[a-f0-9]{64}$")
                    || observedAt == null) {
                throw new IllegalArgumentException(
                        "operations exception evidence is invalid");
            }
        }

        public UUID eventId() {
            return UUID.nameUUIDFromBytes((tenantId + "\u001f" + executionId
                    + "\u001f" + category + "\u001f" + evidenceDigest)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public String evidenceReference() {
            return "urn:dwp:hris:evidence:domain-event:" + eventId();
        }
    }
}
