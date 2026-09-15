package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed, credential-safe transport. Nullable observations never mean a successful probe. */
public final class ApprovalSignatureProviderDtos {
    private ApprovalSignatureProviderDtos() { }

    @Schema(name="ApprovalSignatureProviderScope", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Scope(String resourceSetKey, String contextScopeKey, String decisionRevision,
                        String registrySha256, String sourceRevision, String sourceSha256, Instant evaluatedAt) {
        public Scope {
            text(resourceSetKey, 80); text(contextScopeKey, 512); sha(registrySha256); sha(sourceSha256);
            if (!resourceSetKey.matches("RS_[A-Z0-9_]{1,76}") || decisionRevision == null
                    || !decisionRevision.matches("psr-[a-f0-9]{64}")
                    || !("sigp-" + sourceSha256).equals(sourceRevision)) throw invalid("Invalid scope evidence");
            required(evaluatedAt);
        }
    }

    @Schema(name="ApprovalSignatureProviderSourcePin", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record SourcePin(UUID sourceId, long version, String sha256) {
        public SourcePin { required(sourceId); SignatureProviderModel.version(version); sha(sha256); }
    }

    @Schema(name="ApprovalSignatureProviderPolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Policy(PolicySourceState sourceState, SourcePin pin, List<ProviderKind> requiredProviderKinds,
                         Long maxProbeAgeSeconds, Long probeIntervalSeconds, Long retentionFloorSeconds) {
        public Policy {
            required(sourceState);
            if (sourceState == PolicySourceState.AVAILABLE) {
                required(pin); requiredProviderKinds = unique(requiredProviderKinds, MAX_PROVIDERS);
                positiveSeconds(maxProbeAgeSeconds); positiveSeconds(probeIntervalSeconds);
                positiveSeconds(retentionFloorSeconds);
            } else if (pin != null || requiredProviderKinds != null || maxProbeAgeSeconds != null
                    || probeIntervalSeconds != null || retentionFloorSeconds != null)
                throw invalid("Unrecorded policy cannot fabricate values");
        }
    }

    @Schema(name="ApprovalSignatureProviderKpis", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Kpis(int registeredProviderCount, int configuredProviderCount, int verifiedProductionProviderCount,
                       Integer requiredProviderCount, List<ProviderKind> requiredProviderKinds,
                       GateState externalGateState, List<String> gateReasonCodes, Instant lastProbeAt,
                       Long probeIntervalSeconds) {
        public Kpis {
            count(registeredProviderCount); count(configuredProviderCount); count(verifiedProductionProviderCount);
            if (configuredProviderCount > registeredProviderCount || verifiedProductionProviderCount > configuredProviderCount)
                throw invalid("Provider counts are inconsistent");
            if ((requiredProviderCount == null) != (requiredProviderKinds == null)) throw invalid("Unknown policy count must be null");
            if (requiredProviderCount != null) {
                count(requiredProviderCount); requiredProviderKinds = unique(requiredProviderKinds, MAX_PROVIDERS);
                if (requiredProviderCount != requiredProviderKinds.size()) throw invalid("Policy denominator mismatch");
            }
            required(externalGateState); gateReasonCodes = reasons(gateReasonCodes);
            if (requiredProviderCount == null && externalGateState == GateState.ELIGIBLE)
                throw invalid("Missing policy cannot enable an external gate");
            if (probeIntervalSeconds != null) positiveSeconds(probeIntervalSeconds);
        }
    }

    @Schema(name="ApprovalSignatureProviderCheck", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Check(String checkKey, ObservationState state, List<String> reasonCodes,
                        Instant observedAt, Instant validUntil, UUID evidenceId, String evidenceSha256) {
        public Check {
            key(checkKey); required(state); reasonCodes = reasons(reasonCodes);
            interval(observedAt, validUntil); evidence(evidenceId, evidenceSha256);
            if ((state == ObservationState.PASS || state == ObservationState.FAIL)
                    && (observedAt == null || evidenceId == null)) throw invalid("Observed checks require evidence");
            if ((state == ObservationState.NOT_CONFIGURED || state == ObservationState.NOT_OBSERVED)
                    && (observedAt != null || evidenceId != null)) throw invalid("Missing checks cannot fabricate evidence");
        }
    }

    @Schema(name="ApprovalSignatureProviderCard", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ProviderCard(UUID providerId, ProviderKind kind, String displayName, Long providerVersion,
                               String providerSha256, boolean adapterInstalled, boolean configurationRegistered,
                               boolean credentialRegistered, boolean credentialVerified, Boolean requiredByPolicy,
                               Environment environment, Readiness readiness, List<String> gateReasonCodes,
                               Instant lastProbeAt, List<Check> checks) {
        public ProviderCard {
            required(kind); text(displayName, 160); required(environment); required(readiness);
            gateReasonCodes = reasons(gateReasonCodes); checks = bounded(checks, 32); uniqueChecks(checks);
            if (providerId == null) {
                if (kind == ProviderKind.CUSTOM || providerVersion != null || providerSha256 != null
                        || configurationRegistered || credentialRegistered || credentialVerified)
                    throw invalid("Unregistered provider cannot have registration evidence");
            } else { required(providerVersion); SignatureProviderModel.version(providerVersion); sha(providerSha256); }
            if (credentialVerified && (!credentialRegistered || !configurationRegistered || !adapterInstalled))
                throw invalid("Verified credentials require a current installed configuration");
            if (readiness == Readiness.MISSING_INTERNAL && adapterInstalled)
                throw invalid("Implemented adapters are not missing internal development");
            if (readiness == Readiness.VERIFIED_PRODUCTION && (kind == ProviderKind.INTERNAL
                    || environment != Environment.PRODUCTION || !credentialVerified || lastProbeAt == null))
                throw invalid("Production verification cannot be inferred from provider type");
            if (readiness == Readiness.VERIFIED_SANDBOX && (environment != Environment.SANDBOX || !credentialVerified))
                throw invalid("Sandbox verification requires observed sandbox credentials");
            if (readiness == Readiness.VERIFIED_INTERNAL_KEY && (kind != ProviderKind.INTERNAL
                    || environment != Environment.INTERNAL || !adapterInstalled)) throw invalid("Invalid internal verification");
        }
    }

    @Schema(name="ApprovalSignatureProviderSettings", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Settings(SourcePin configuration, Environment environment, String endpointOriginSha256,
                           String accountBindingSha256, boolean credentialRegistered,
                           String callbackAuthenticationMode, String configurationOwner) {
        public Settings {
            required(environment); text(callbackAuthenticationMode, 80); text(configurationOwner, 160);
            if (endpointOriginSha256 != null) sha(endpointOriginSha256);
            if (accountBindingSha256 != null) sha(accountBindingSha256);
            if (configuration == null && (credentialRegistered || endpointOriginSha256 != null || accountBindingSha256 != null))
                throw invalid("Settings must not invent registered configuration");
        }
    }

    @Schema(name="ApprovalSignatureProviderPhase", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Phase(PhaseKind phaseKind, GateState gateState, List<String> reasonCodes,
                        List<String> checkKeys, List<UUID> evidenceIds) {
        public Phase {
            required(phaseKind); required(gateState); reasonCodes = reasons(reasonCodes);
            checkKeys = unique(checkKeys, 32); checkKeys.forEach(SignatureProviderModel::key);
            evidenceIds = unique(evidenceIds, 32);
        }
    }

    @Schema(name="ApprovalSignatureProviderGuideSection", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record GuideSection(String sectionKey, List<String> stepKeys, List<String> officialDocumentationLinks) {
        public GuideSection {
            key(sectionKey); stepKeys = unique(stepKeys, 32); stepKeys.forEach(SignatureProviderModel::key);
            officialDocumentationLinks = unique(officialDocumentationLinks, 8);
            officialDocumentationLinks.forEach(ApprovalSignatureProviderDtos::officialLink);
        }
    }

    @Schema(name="ApprovalSignatureProviderGuide", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Guide(List<GuideSection> sections) {
        public Guide { sections = bounded(sections, 10); }
    }

    @Schema(name="ApprovalSignatureProviderKms", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Kms(KmsBackend backend, VerificationKind verificationKind, ObservationState state,
                      String algorithm, String keySha256, String source, Instant checkedAt, Instant validUntil,
                      UUID evidenceId, String evidenceSha256, List<String> reasonCodes) {
        public Kms {
            required(backend); required(verificationKind); required(state); text(source, 160);
            if (algorithm != null) text(algorithm, 80); if (keySha256 != null) sha(keySha256);
            interval(checkedAt, validUntil); evidence(evidenceId, evidenceSha256); reasonCodes = reasons(reasonCodes);
            if (state == ObservationState.PASS && (backend == KmsBackend.NONE || keySha256 == null
                    || algorithm == null || checkedAt == null || evidenceId == null || verificationKind == VerificationKind.NONE))
                throw invalid("KMS success requires actual key evidence");
            if (verificationKind == VerificationKind.HARDWARE_TOKEN && backend != KmsBackend.PKCS11
                    || verificationKind == VerificationKind.CONFIGURED_KMS && backend != KmsBackend.AWS_KMS
                    || verificationKind == VerificationKind.INTERNAL_KEY && backend != KmsBackend.INTERNAL_JCA)
                throw invalid("Internal and configured KMS observations do not prove hardware tokens");
        }
    }

    @Schema(name="ApprovalSignatureProviderWorm", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Worm(ObservationState state, String storageLocatorSha256, String objectVersionSha256,
                       ObjectLockMode objectLockMode, Instant retainUntil, Boolean legalHold,
                       SourcePin policy, Long retentionFloorSeconds, Instant checkedAt, Instant validUntil,
                       UUID evidenceId, String evidenceSha256, List<String> reasonCodes) {
        public Worm {
            required(state); required(objectLockMode); reasonCodes = reasons(reasonCodes);
            if (storageLocatorSha256 != null) sha(storageLocatorSha256);
            if (objectVersionSha256 != null) sha(objectVersionSha256);
            if (retentionFloorSeconds != null) positiveSeconds(retentionFloorSeconds);
            interval(checkedAt, validUntil); evidence(evidenceId, evidenceSha256);
            if (state == ObservationState.PASS && (storageLocatorSha256 == null || objectVersionSha256 == null
                    || objectLockMode == ObjectLockMode.NONE || retainUntil == null || legalHold == null
                    || policy == null || retentionFloorSeconds == null || checkedAt == null || evidenceId == null
                    || retainUntil.isBefore(checkedAt.plusSeconds(retentionFloorSeconds))))
                throw invalid("WORM requires actual version retention and a policy floor");
        }
    }

    @Schema(name="ApprovalSignatureProviderOverview", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Overview(Scope scope, Policy policy, Kpis kpis, List<ProviderCard> providers,
                           List<Phase> phases, Kms kms, Worm worm) {
        public Overview {
            required(scope); required(policy); required(kpis); required(kms); required(worm);
            providers = bounded(providers, MAX_PROVIDERS); phases = bounded(phases, 3);
            if (policy.sourceState() != PolicySourceState.AVAILABLE && (kpis.requiredProviderCount() != null
                    || kpis.probeIntervalSeconds() != null || providers.stream().anyMatch(p -> p.requiredByPolicy() != null)))
                throw invalid("Unknown policy must remain unknown in all projections");
            if (policy.sourceState() == PolicySourceState.AVAILABLE && (!java.util.Objects.equals(kpis.requiredProviderKinds(), policy.requiredProviderKinds())
                    || !java.util.Objects.equals(kpis.probeIntervalSeconds(), policy.probeIntervalSeconds())))
                throw invalid("KPI values must bind the same immutable published policy");
            providers.forEach(provider -> requirePolicyProjection(policy, provider));
        }
    }

    @Schema(name="ApprovalSignatureProviderDiagnostics", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ProviderDiagnostics(Scope scope, Policy policy, ProviderCard provider, Settings settings,
                                      List<String> gateReasons, Guide guide, List<Phase> phases, Kms kms, Worm worm) {
        public ProviderDiagnostics {
            required(scope); required(policy); required(provider); required(settings); required(guide); required(kms); required(worm);
            gateReasons = reasons(gateReasons); phases = bounded(phases, 3);
            requirePolicyProjection(policy, provider);
        }
    }

    @Schema(name="ApprovalSignatureProviderTarget", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ProviderTarget(UUID providerId, long expectedProviderVersion, String expectedProviderSha256,
                                 SourcePin expectedConfiguration) {
        public ProviderTarget {
            required(providerId); SignatureProviderModel.version(expectedProviderVersion); sha(expectedProviderSha256);
        }
    }

    @Schema(name="ApprovalSignatureProviderProbeInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ProbeInput(String expectedSourceRevision, String expectedSourceSha256, Boolean allProviders,
                             List<ProviderTarget> targets, String idempotencyKey) {
        public ProbeInput {
            source(expectedSourceRevision, expectedSourceSha256); key(idempotencyKey); required(allProviders);
            targets = bounded(targets, MAX_PROVIDERS); uniqueTargets(targets);
            if (targets.isEmpty() || !allProviders && targets.size() != 1) throw invalid("Probe cohort must be explicit and nonempty");
        }
    }

    @Schema(name="ApprovalSignatureProviderKmsProbeInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record KmsProbeInput(String expectedSourceRevision, String expectedSourceSha256,
                                ProviderTarget target, String idempotencyKey) {
        public KmsProbeInput { source(expectedSourceRevision, expectedSourceSha256); required(target); key(idempotencyKey); }
    }

    @Schema(name="ApprovalSignatureProviderWormInspectionInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record WormInspectionInput(String expectedSourceRevision, String expectedSourceSha256,
                                      ProviderTarget target, UUID artifactId, String idempotencyKey) {
        public WormInspectionInput { source(expectedSourceRevision, expectedSourceSha256); required(target); key(idempotencyKey); }
    }

    @Schema(name="ApprovalSignatureProviderProbeResult", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ProbeResult(UUID providerId, ProviderTarget originalTarget, ProbeOutcome outcome,
                              Instant observedAt, Instant cooldownUntil, List<String> reasonCodes, List<Check> checks) {
        public ProbeResult {
            required(providerId); required(originalTarget); required(outcome);
            if (!providerId.equals(originalTarget.providerId())) throw invalid("Probe target changed");
            reasonCodes = reasons(reasonCodes); checks = bounded(checks, 32); uniqueChecks(checks);
        }
    }

    @Schema(name="ApprovalSignatureProviderProbeRun", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ProbeRun(Scope scope, UUID probeRunId, ProbeState state, Instant startedAt, Instant completedAt,
                           String originalBodySha256, List<ProviderTarget> originalTargets, List<ProbeResult> providerResults) {
        public ProbeRun {
            required(scope); required(probeRunId); required(state); required(startedAt); sha(originalBodySha256);
            originalTargets = bounded(originalTargets, MAX_PROVIDERS); uniqueTargets(originalTargets);
            providerResults = bounded(providerResults, MAX_PROVIDERS);
            var seen = new java.util.HashSet<UUID>();
            for (var result : providerResults) if (!seen.add(result.providerId()) || !originalTargets.contains(result.originalTarget()))
                throw invalid("Probe result does not match the frozen cohort");
            if ((state == ProbeState.COMPLETE || state == ProbeState.PARTIAL) && (completedAt == null
                    || providerResults.size() != originalTargets.size())) throw invalid("Finished cohorts must be complete, including denied seats");
            if (completedAt != null && completedAt.isBefore(startedAt)) throw invalid("Invalid completion time");
        }
    }

    @Schema(name="ApprovalSignatureProviderHistoryItem", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record HistoryItem(UUID probeRunId, UUID providerId, String sourceRevision, String sourceSha256,
                              ProbeState state, Instant occurredAt, List<String> reasonCodes, UUID evidenceId, String evidenceSha256) {
        public HistoryItem {
            required(probeRunId); source(sourceRevision, sourceSha256); required(state); required(occurredAt);
            reasonCodes = reasons(reasonCodes); evidence(evidenceId, evidenceSha256);
        }
    }

    @Schema(name="ApprovalSignatureProviderHistory", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record History(Scope scope, List<HistoryItem> items, String nextCursor, boolean truncated) {
        public History {
            required(scope); items = bounded(items, MAX_HISTORY);
            if (nextCursor != null) text(nextCursor, 4096);
            if (truncated != (nextCursor != null)) throw invalid("History continuation must be truthful");
        }
    }

    @Schema(name="ApprovalExternalSignatureSource", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalSource(UUID requestId, long requestVersion, String resourceSetKey,
                                 String dataClassification, UUID workflowVersionId, UUID formVersionId,
                                 int payloadRevision, String payloadSha256, String sourceSha256) {
        public ExternalSource {
            required(requestId); version(requestVersion); text(resourceSetKey, 80);
            if (!resourceSetKey.matches("RS_[A-Z0-9_]{1,76}")) throw invalid("Invalid request resource set");
            if (!Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED").contains(dataClassification))
                throw invalid("Invalid request classification");
            required(workflowVersionId); required(formVersionId);
            if (payloadRevision < 1) throw invalid("Invalid payload revision");
            sha(payloadSha256); sha(sourceSha256);
        }
    }

    @Schema(name="ApprovalExternalSignatureContext", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalContext(Scope scope, ExternalSource source, Policy policy,
                                  List<ProviderCard> providers, GateState gateState,
                                  List<String> reasonCodes, Instant evaluatedAt) {
        public ExternalContext {
            required(scope); required(source); required(policy); required(gateState); required(evaluatedAt);
            providers = bounded(providers, MAX_PROVIDERS); reasonCodes = reasons(reasonCodes);
            if (gateState == GateState.ELIGIBLE && (policy.sourceState() != PolicySourceState.AVAILABLE
                    || policy.requiredProviderKinds() == null
                    || policy.requiredProviderKinds().isEmpty() || !reasonCodes.isEmpty()))
                throw invalid("External signing eligibility requires a current explicit policy");
            if (gateState != GateState.ELIGIBLE && reasonCodes.isEmpty())
                throw invalid("Blocked external signing requires exact reasons");
        }
    }

    @Schema(name="ApprovalExternalSignatureCreateInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalCreateInput(Long expectedRequestVersion, String expectedSourceRevision,
                                      String expectedSourceSha256, ProviderTarget provider,
                                      String idempotencyKey) {
        public ExternalCreateInput {
            version(required(expectedRequestVersion)); source(expectedSourceRevision, expectedSourceSha256);
            required(provider); key(idempotencyKey);
        }
    }

    @Schema(name="ApprovalExternalSignatureCommandInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalCommandInput(Long expectedVersion, String expectedSourceRevision,
                                       String expectedSourceSha256, String idempotencyKey) {
        public ExternalCommandInput {
            version(required(expectedVersion)); source(expectedSourceRevision, expectedSourceSha256); key(idempotencyKey);
        }
    }

    @Schema(name="ApprovalExternalSignatureRequest", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalRequest(Scope scope, UUID signatureRequestId, ExternalSource source,
                                  ProviderTarget provider, UUID policyId, UUID policyVersionId,
                                  String policySha256, ExternalState state, long version,
                                  String remoteReferenceSha256, List<String> reasonCodes,
                                  Instant createdAt, Instant updatedAt) {
        public ExternalRequest {
            required(scope); required(signatureRequestId); required(source); required(provider);
            required(policyId); required(policyVersionId); sha(policySha256); required(state);
            SignatureProviderModel.version(version);
            if (remoteReferenceSha256 != null) sha(remoteReferenceSha256);
            reasonCodes = reasons(reasonCodes); required(createdAt); required(updatedAt);
            if (updatedAt.isBefore(createdAt)) throw invalid("External signature timeline changed");
            if (state == ExternalState.COMPLETED_VERIFIED && remoteReferenceSha256 == null)
                throw invalid("Verified completion requires a provider reference");
        }
    }

    @Schema(name="ApprovalExternalSignatureReceipt", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalReceipt(UUID receiptId, String outcome, Instant committedAt,
                                  ExternalRequest signatureRequest) {
        public ExternalReceipt {
            required(receiptId); required(committedAt); required(signatureRequest);
            if (!"COMMITTED".equals(outcome)) throw invalid("Only committed external command receipts are exposed");
        }
    }

    @Schema(name="ApprovalExternalSignatureEvent", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalEvent(UUID eventId, long sequence, String action, ExternalState state,
                                List<String> reasonCodes, UUID evidenceId, String evidenceSha256,
                                Instant occurredAt) {
        public ExternalEvent {
            required(eventId); version(sequence); key(action); required(state);
            reasonCodes = reasons(reasonCodes); evidence(evidenceId, evidenceSha256); required(occurredAt);
        }
    }

    @Schema(name="ApprovalExternalSignatureAudit", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalAudit(List<ExternalEvent> items, boolean truncated) {
        public ExternalAudit { items = bounded(items, 1_000); }
    }

    @Schema(name="ApprovalExternalSignatureArtifact", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ExternalArtifact(UUID artifactId, ArtifactKind kind, String mediaType, String sha256,
                                   long sizeBytes, String storageLocatorSha256, String objectVersionSha256,
                                   Instant retainUntil, UUID evidenceId, String evidenceSha256,
                                   Instant recordedAt) {
        public ExternalArtifact {
            required(artifactId); required(kind); text(mediaType, 120); sha(sha256);
            if (sizeBytes < 1 || sizeBytes > 52_428_800L) throw invalid("Invalid external artifact size");
            sha(storageLocatorSha256); sha(objectVersionSha256); required(retainUntil);
            evidence(evidenceId, evidenceSha256); required(recordedAt);
            if (!retainUntil.isAfter(recordedAt) || evidenceId == null)
                throw invalid("External artifacts require retained verification evidence");
        }
    }

    private static void source(String revision, String digest) {
        sha(digest); if (!("sigp-" + digest).equals(revision)) throw invalid("Invalid source revision");
    }
    private static void requirePolicyProjection(Policy policy, ProviderCard provider) {
        Boolean expected = policy.sourceState() == PolicySourceState.AVAILABLE
                ? policy.requiredProviderKinds().contains(provider.kind()) : null;
        if (!java.util.Objects.equals(expected, provider.requiredByPolicy()))
            throw invalid("Provider requirements must bind the same immutable published policy");
    }
    private static void positiveSeconds(Long value) {
        if (value == null || value <= 0 || value > 3_153_600_000L) throw invalid("Invalid bounded duration");
    }
    private static void count(int value) { if (value < 0 || value > MAX_PROVIDERS) throw invalid("Invalid provider count"); }
    private static void uniqueChecks(List<Check> checks) {
        if (checks.stream().map(Check::checkKey).distinct().count() != checks.size()) throw invalid("Duplicate check key");
    }
    private static void uniqueTargets(List<ProviderTarget> targets) {
        if (targets.stream().map(ProviderTarget::providerId).distinct().count() != targets.size()) throw invalid("Duplicate provider target");
    }
    private static void officialLink(String value) {
        text(value, 2048); URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException bad) { throw invalid("Invalid official documentation link"); }
        var hosts = Set.of("developers.docusign.com", "www.docusign.com", "developer.adobe.com", "docs.aws.amazon.com", "docs.oracle.com");
        if (!"https".equals(uri.getScheme()) || !hosts.contains(uri.getHost()) || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || uri.getRawPath().contains("..") || uri.getRawPath().contains("%"))
            throw invalid("Only fixed official documentation origins are allowed");
    }
}
