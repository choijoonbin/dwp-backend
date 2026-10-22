package com.dwp.services.people.integration;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceInputEnvelopeV1;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

/** Explicit fail-closed boundaries; this slice binds no production provider. */
public final class Hrm012WorkforceIngestionPorts {
    private Hrm012WorkforceIngestionPorts() {
    }

    public interface TrustedAuthoritySnapshotPort {
        Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot resolve(
                SnapshotRequest request);
    }

    /**
     * Auth-owned post-receipt verdict. Implementations must return values actually
     * attested by Auth; copying request values into a narrower response is forbidden.
     */
    public interface PostReceiptAuthorityPort {
        PostReceiptAuthorityVerdict verify(PostReceiptAuthorityRequest request);
    }

    public interface OwnerMetadataPort {
        Hrm012WorkforceIngestionContract.OwnerMetadata resolve(MetadataRequest request);
    }

    public interface ArtifactPort {
        Hrm012WorkforceIngestionContract.Artifact fetch(ArtifactRequest request);
    }

    public interface MappingPort {
        MappingResult validateAndMap(MappingRequest request);
    }

    public interface DomainEffectPort {
        EffectResult apply(EffectRequest request);
    }

    /**
     * Marker for an effect adapter whose writes enlist in the caller's Spring
     * transaction. External or independently committed side effects are not a
     * valid HRM-012 production binding.
     */
    public interface TransactionalDomainEffectPort extends DomainEffectPort {
    }

    public interface QuarantineProtectorPort {
        ProtectedQuarantine protect(QuarantineRequest request);
    }

    public record SnapshotRequest(
            PeopleRequestContext.Actor actor,
            Hrm012OwnerReceiptAuthorityVerifier.Evidence marker,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            String canonicalRequestHash) {
    }

    public record PostReceiptAuthorityRequest(
            long tenantId,
            long numericUserId,
            UUID personPublicId,
            String routeContractKey,
            String originatingAction,
            String commandMethod,
            String publicCommandPath,
            String serviceCommandPath,
            String contextKey,
            String scopeRef,
            String populationScopeDigest,
            long authorizationRevision,
            long populationRevision,
            long fieldPolicyRevision,
            String postReceiptDecisionRevision,
            String dutyEvidenceRevision,
            String featureDecisionRevision,
            String purpose,
            UUID correlationId,
            String idempotencyKey,
            String idempotencyKeySha256,
            String canonicalRequestHash,
            UUID receiptPublicId,
            String objectRef,
            String ifMatch,
            String payloadSha256,
            String opaqueStepUpChallengeRef) {
    }

    /** Complete Auth attestation compared field-for-field with the frozen receipt. */
    public record PostReceiptAuthorityVerdict(
            String decision,
            long tenantId,
            long numericUserId,
            UUID personPublicId,
            String routeContractKey,
            String originatingAction,
            String commandMethod,
            String publicCommandPath,
            String serviceCommandPath,
            String contextKey,
            String scopeRef,
            String populationScopeDigest,
            long authorizationRevision,
            long populationRevision,
            long fieldPolicyRevision,
            String postReceiptDecisionRevision,
            String dutyEvidenceRevision,
            String featureDecisionRevision,
            String purpose,
            UUID correlationId,
            String idempotencyKeySha256,
            String canonicalRequestHash,
            UUID receiptPublicId,
            String objectRef,
            String ifMatch,
            String capability,
            String duty,
            String stepUpPolicy,
            String stepUpChallengeId,
            Instant stepUpExpiresAt) {
    }

    public record MetadataRequest(
            long tenantId,
            UUID personPublicId,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot authority,
            long expectedObjectVersion) {
    }

    public record ArtifactRequest(
            long tenantId,
            UUID personPublicId,
            String objectRef,
            long objectVersion) {
    }

    public record MappingRequest(
            long tenantId,
            UUID runPublicId,
            UUID connectorInstanceId,
            UUID mappingProfileId,
            long mappingVersion,
            String mappingSha256,
            String schemaKey,
            int schemaVersion,
            LocalDate effectiveDate,
            Hrm012WorkforceInputEnvelopeV1.Record record) {
    }

    public record MappingResult(
            boolean accepted,
            String decisionCode,
            String redactedSummary,
            JsonNode restrictedMappedValue) {
        public MappingResult {
            restrictedMappedValue = restrictedMappedValue == null
                    ? null : restrictedMappedValue.deepCopy();
        }

        @Override
        public JsonNode restrictedMappedValue() {
            return restrictedMappedValue == null ? null : restrictedMappedValue.deepCopy();
        }
    }

    public record EffectRequest(
            long tenantId,
            UUID runPublicId,
            int itemSequence,
            String idempotencyDigest,
            JsonNode restrictedMappedValue) {
    }

    public record EffectResult(boolean applied, String effectDigest, String redactedReason) {
    }

    public record QuarantineRequest(
            long tenantId,
            UUID runPublicId,
            int itemSequence,
            JsonNode restrictedMappedValue,
            String redactedReason) {
    }

    public record ProtectedQuarantine(
            byte[] ciphertext,
            String keyReference,
            String envelopeDigest) {
        public ProtectedQuarantine {
            ciphertext = ciphertext == null ? null : ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext == null ? null : ciphertext.clone();
        }
    }

    public record ArtifactBatch(
            Hrm012WorkforceInputEnvelopeV1 envelope,
            List<Hrm012WorkforceIngestionContract.ItemEvidence> items) {
        public ArtifactBatch {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
