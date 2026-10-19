package com.dwp.platform.contracts.hris.referenceconfiguration;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed, provider-neutral PER-004 boundary. No production provider is bound by this contract. */
public final class HrisReferenceConfigurationV1 {
    public static final Set<String> SCHEMAS = Set.of(
            "dwp.hris.performance.review-form.v1",
            "dwp.hris.performance.rating-scale.v1",
            "dwp.hris.performance.policy.v1");
    public static final int MAX_OWNER_PAYLOAD_UTF8 = 262144;
    public static final String FEATURE = "HRIS_PERFORMANCE_CONFIGURATION";
    public static final String FEATURE_ROUTE = "route.hcm.configuration.settings-performance.data";
    public static final String FINALIZE_EVENT = "dwp.hris.configuration.owner-binding-finalize-requested.v1";
    public static final String ARTIFACT_REFERENCE_PREFIX = "ref_per_artifact_";

    private HrisReferenceConfigurationV1() { }

    public static boolean typedOwner(String domain, String schemaKey) {
        return "PER".equals(domain) && schemaKey != null && SCHEMAS.contains(schemaKey);
    }
    public static boolean ownerReference(String reference) {
        if (reference == null || !reference.startsWith(ARTIFACT_REFERENCE_PREFIX)) return false;
        String raw = reference.substring(ARTIFACT_REFERENCE_PREFIX.length());
        try { return UUID.fromString(raw).toString().equals(raw); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public enum ReceiptKind { PREPARATION, FINALIZATION, VALIDATION, SIMULATION }
    public enum LookupState { BOUND, ABSENT_FINAL, UNKNOWN }

    public record FeatureRequest(
            long tenantId, UUID subjectPrincipalPublicId, String exactSharedActionRouteContractKey,
            UUID configurationId, String resolvedDomain, String scopeDigest,
            long commandAuthorizationRevision, UUID correlationId) { }

    public record FeatureDecision(
            long tenantId, UUID subjectPrincipalPublicId, String exactSharedActionRouteContractKey,
            UUID configurationId, String resolvedDomain, String scopeDigest,
            long commandAuthorizationRevision, UUID correlationId,
            String featureKey, String decisionRoute, String featureState,
            String decision, String featureDecisionRevision,
            OffsetDateTime evaluatedAt, OffsetDateTime revalidateAt) {
        public FeatureDecision(FeatureRequest binding, String featureKey, String decisionRoute, String featureState,
                String decision, String featureDecisionRevision, OffsetDateTime evaluatedAt, OffsetDateTime revalidateAt) {
            this(binding.tenantId(), binding.subjectPrincipalPublicId(), binding.exactSharedActionRouteContractKey(),
                    binding.configurationId(), binding.resolvedDomain(), binding.scopeDigest(),
                    binding.commandAuthorizationRevision(), binding.correlationId(), featureKey, decisionRoute,
                    featureState, decision, featureDecisionRevision, evaluatedAt, revalidateAt);
        }
        @com.fasterxml.jackson.annotation.JsonIgnore public FeatureRequest binding() {
            return new FeatureRequest(tenantId, subjectPrincipalPublicId, exactSharedActionRouteContractKey,
                    configurationId, resolvedDomain, scopeDigest, commandAuthorizationRevision, correlationId);
        }
    }

    @FunctionalInterface
    public interface FeatureDecisionPort {
        FeatureDecision decide(FeatureRequest request);
    }

    public record CreateIdentity(
            long tenantId, UUID configurationId, UUID createSubjectPrincipalPublicId,
            String originatingAction, String createIdempotencyKey, String createRequestDigest,
            UUID ownerPreparationReceiptId) { }

    public record OwnerTuple(
            long tenantId, UUID configurationId, Long version, String domain, String schemaKey,
            int schemaVersion, String payloadReference, String payloadDigest, String scopeDigest) { }

    /** Opaque object is transit-only. Never place this object in audit, outbox or exception text. */
    public record PrepareRequest(
            long tenantId, UUID configurationId, UUID subjectPrincipalPublicId, String schemaKey,
            int schemaVersion, String scopeDigest, String createIdempotencyKey,
            String createRequestDigest, UUID correlationId, JsonNode ownerPayload) {
        @Override public String toString() { return "PrepareRequest[ownerPayload=REDACTED]"; }
    }

    public record OwnerReceipt(
            ReceiptKind kind, OwnerTuple tuple, String ownerService, UUID ownerReceiptId,
            UUID priorOwnerReceiptId, String evidenceDigest, String outcome,
            OffsetDateTime occurredAt, OffsetDateTime revalidateAt,
            String impactDigest) { }

    public record FinalizeRequest(
            OwnerTuple tuple, UUID ownerPreparationReceiptId, UUID platformCreateReceiptId,
            long expectedAggregateVersion) { }

    public record ValidateRequest(OwnerTuple tuple, UUID ownerFinalizationReceiptId) { }

    public record SimulateRequest(
            OwnerTuple tuple, UUID ownerValidationReceiptId, String sampleScopeRef,
            String sampleScopeDigest) { }

    public interface OwnerPort {
        OwnerReceipt prepare(PrepareRequest request);
        OwnerReceipt finalizeBinding(FinalizeRequest request);
        OwnerReceipt validate(ValidateRequest request);
        OwnerReceipt simulate(SimulateRequest request);
    }

    public record FinalizationAcknowledgement(
            UUID configurationId, long version, UUID ownerFinalizationReceiptId,
            UUID platformCreateReceiptId, long aggregateVersion) { }

    public record CreateLookupRequest(CreateIdentity identity) { }

    /** ABSENT_FINAL is valid only with durable fence evidence for this exact identity. */
    public record CreateLookupResponse(
            CreateIdentity identity, LookupState state, Long version, UUID platformCreateReceiptId,
            UUID durableAdmissionFenceId, String evidenceDigest, OffsetDateTime evaluatedAt,
            String signedBindingEvidence) { }

    @FunctionalInterface public interface LookupEvidenceSigner {
        String sign(CreateLookupResponse exactLookup);
    }

    public record ProjectionTarget(UUID configurationId, String scopeDigest) { }
    public record OwnerProjectionFilter(Set<String> lifecycleStates, Long minimumVersion) { }
    public record OwnerProjectionRequest(OffsetDateTime asOf, List<ProjectionTarget> targets,
            String cursor, Integer limit, Set<String> schemaKeys, OwnerProjectionFilter filter, List<String> sort) {
        public OwnerProjectionRequest(OffsetDateTime asOf, List<ProjectionTarget> targets) {
            this(asOf, targets, null, 100, SCHEMAS, new OwnerProjectionFilter(Set.of(), null), List.of("configurationId:ASC"));
        }
    }
    public record OwnerProjection(
            UUID configurationId, Long version, Long aggregateVersion, String lifecycleState,
            String schemaKey, String scopeDigest, OffsetDateTime effectiveFrom,
            OffsetDateTime scheduledAt, boolean ownerFinalized, boolean createEligible,
            String key, OffsetDateTime effectiveTo, Integer schemaVersion,
            String payloadReference, String payloadDigest) {
        public OwnerProjection(UUID configurationId, Long version, Long aggregateVersion, String lifecycleState,
                String schemaKey, String scopeDigest, OffsetDateTime effectiveFrom, OffsetDateTime scheduledAt,
                boolean ownerFinalized, boolean createEligible) {
            this(configurationId, version, aggregateVersion, lifecycleState, schemaKey, scopeDigest,
                    effectiveFrom, scheduledAt, ownerFinalized, createEligible, null, null, null, null, null);
        }
    }
    public record OwnerProjectionResponse(
            OffsetDateTime asOf, List<OwnerProjection> items, String freshness, String nextCursor) {
        public OwnerProjectionResponse(OffsetDateTime asOf, List<OwnerProjection> items, String freshness) {
            this(asOf, items, freshness, null);
        }
    }

    /** V2 descriptors deliberately do not alter the generic V1 hrefTemplate contract. */
    public record TargetActionV2(
            String actionKey, String method, String targetResourceType, UUID targetConfigurationId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long targetVersion,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long expectedObjectVersion, String resolvedHref,
            String idempotencyPolicy, String stepUpPolicy) { }
}
