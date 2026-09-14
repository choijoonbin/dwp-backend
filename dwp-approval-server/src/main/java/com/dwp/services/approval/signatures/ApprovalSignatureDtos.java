package com.dwp.services.approval.signatures;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.math.BigInteger;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public final class ApprovalSignatureDtos {
    private ApprovalSignatureDtos() { }
    public static final long MAX_VERSION = 9007199254740991L;
    public enum SignerKind { SELF_ATTESTATION }
    public enum State { AWAITING_CONSENT, CONSENTED, ATTESTED, CANCELLED }
    public interface Strict {
        @JsonAnySetter default void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown signature input field: " + key); }
    }
    public static final class StrictVersion extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return context.reportInputMismatch(Long.class,"Signature version must be an exact JSON integer.");
            BigInteger value=parser.getBigIntegerValue();
            if (value.signum()<0 || value.compareTo(BigInteger.valueOf(MAX_VERSION))>0)
                return context.reportInputMismatch(Long.class,"Signature version is outside the supported integer range.");
            return value.longValueExact();
        }
    }
    public static final class StrictConsent extends JsonDeserializer<Boolean> {
        @Override public Boolean getNullValue(DeserializationContext context) { return false; }
        @Override public Boolean deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_TRUE)) return true;
            if (parser.hasToken(JsonToken.VALUE_FALSE)) return false;
            return context.reportInputMismatch(Boolean.class,"Signature consent must be an explicit JSON boolean.");
        }
    }
    @Schema(name="ApprovalSignatureSourcePin",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties={"requestId","requestVersion","payloadRevision","payloadSha256","formVersionId","formSchemaSha256","workflowVersionId","workflowSha256",
                    "resourceSetKey","manifestSha256","documentPolicyId","documentPolicyVersion","documentPolicyRevision","documentPolicySha256",
                    "attachmentPolicyId","attachmentPolicyVersion","attachmentPolicyRevision","attachmentPolicySha256","providerId","providerVersion","providerSha256",
                    "ownerUserId","rendererVersion","artifactSha256"})
    public record SourcePin(UUID requestId, long requestVersion, int payloadRevision, String payloadSha256,
            UUID formVersionId, String formSchemaSha256, UUID workflowVersionId, String workflowSha256,
            String resourceSetKey, String manifestSha256, UUID documentPolicyId, long documentPolicyVersion,
            int documentPolicyRevision, String documentPolicySha256, UUID attachmentPolicyId,
            long attachmentPolicyVersion, int attachmentPolicyRevision, String attachmentPolicySha256,
            UUID providerId, long providerVersion, String providerSha256, long ownerUserId,
            String rendererVersion, String artifactSha256, @Schema(nullable=true) String signingKeySha256) { }
    @Schema(name="ApprovalSignatureTerms",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"termsId","version","sha256","locale","text","expiresAt"})
    public record Terms(String termsId, long version, String sha256, String locale, String text, Instant expiresAt) { }
    @Schema(name="ApprovalSignatureArtifact",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"rendererVersion","mediaType","sha256","sizeBytes","content"})
    public record Artifact(String rendererVersion, String mediaType, String sha256, long sizeBytes, String content) { }
    @Schema(name="ApprovalSignatureContext",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"signerKind","source","sourceDigest","artifact","terms","signingReadiness","consentRequired","evaluatedAt"})
    public record Context(SignerKind signerKind, SourcePin source, String sourceDigest, Artifact artifact, Terms terms,
            String signingReadiness, boolean consentRequired, Instant evaluatedAt) { }
    @Schema(name="ApprovalSignatureCreate",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"expectedVersion","signerKind","locale","sourceDigest","idempotencyKey"})
    public record Create(@JsonDeserialize(using=StrictVersion.class) @NotNull @Min(0) @Max(MAX_VERSION) Long expectedVersion,
            @NotNull SignerKind signerKind, @NotBlank @Pattern(regexp="ko|en") String locale,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceDigest,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalSignatureConsent",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"expectedVersion","sourceDigest","termsId","termsVersion","termsSha256","locale","accepted","idempotencyKey"})
    public record Consent(@JsonDeserialize(using=StrictVersion.class) @NotNull @Min(0) @Max(MAX_VERSION) Long expectedVersion,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceDigest,
            @NotBlank @Size(max=80) String termsId, @JsonDeserialize(using=StrictVersion.class) @Min(1) @Max(MAX_VERSION) long termsVersion,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String termsSha256,
            @NotBlank @Pattern(regexp="ko|en") String locale, @Schema(defaultValue="false") @JsonDeserialize(using=StrictConsent.class) boolean accepted,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalSignatureSign",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"expectedVersion","sourceDigest","consentReceiptId","idempotencyKey"})
    public record Sign(@JsonDeserialize(using=StrictVersion.class) @NotNull @Min(0) @Max(MAX_VERSION) Long expectedVersion,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceDigest, @NotNull UUID consentReceiptId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalSignatureCancel",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"expectedVersion","sourceDigest","idempotencyKey"})
    public record Cancel(@JsonDeserialize(using=StrictVersion.class) @NotNull @Min(0) @Max(MAX_VERSION) Long expectedVersion,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceDigest,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalSignatureEvidence",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"evidenceId","proofKind","keyId","publicKeyJson","artifactSha256","sourceDigest","compactJws","attestedAt"})
    public record Evidence(UUID evidenceId, String proofKind, String keyId, String publicKeyJson, String artifactSha256,
            String sourceDigest, String compactJws, Instant attestedAt) { }
    @Schema(name="ApprovalSignatureNullEvidence",types={"null"})
    public static final class NullEvidence { private NullEvidence() { } }
    @Schema(name="ApprovalSignatureCeremony",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"signatureRequestId","requestId","signerKind","state","version","source","sourceDigest","artifact","terms","expiresAt","preservationState"})
    public record Ceremony(UUID signatureRequestId, UUID requestId, SignerKind signerKind, State state,
            long version, SourcePin source, String sourceDigest, Artifact artifact, Terms terms, Instant expiresAt,
            @Schema(nullable=true) UUID consentReceiptId,
            @Schema(implementation=Object.class,schemaResolution=Schema.SchemaResolution.INLINE,
                    oneOf={Evidence.class,NullEvidence.class}) Evidence evidence, String preservationState) { }
    @Schema(name="ApprovalSignatureReceipt",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"commandReceiptId","outcome","committedAt","ceremony"})
    public record Receipt(UUID commandReceiptId, String outcome, Instant committedAt, Ceremony ceremony) { }
    @Schema(name="ApprovalSignatureEvent",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"eventId","sequence","action","sourceDigest","occurredAt"})
    public record Event(UUID eventId, long sequence, String action, String sourceDigest, Instant occurredAt) { }
    @Schema(name="ApprovalSignatureAudit",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"items","truncated"})
    public record Audit(List<Event> items, boolean truncated) { }
}
