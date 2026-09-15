package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.Scope;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.SourcePin;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ApprovalSignatureProviderPolicyDtos {
    private ApprovalSignatureProviderPolicyDtos() { }

    @Schema(name="ApprovalSignatureProviderPolicyRules", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Rules(Boolean signingEnabled, List<ProviderKind> requiredProviderKinds,
                        List<String> allowedClassifications, Boolean requireVerifiedProviderAccount,
                        Boolean requireAuthenticatedWebhook, Boolean requireTrustedCertificateChain,
                        Boolean requireFreshRevocationEvidence, Boolean requireTrustedTimestamp,
                        Boolean requireComplianceWormStorage, Long minimumRetentionDays, Long probeMaxAgeSeconds,
                        UUID trustBundleId, SourcePin configurationBinding) {
        public Rules {
            required(signingEnabled); required(requireVerifiedProviderAccount); required(requireAuthenticatedWebhook);
            required(requireTrustedCertificateChain); required(requireFreshRevocationEvidence);
            required(requireTrustedTimestamp); required(requireComplianceWormStorage);
            requiredProviderKinds = unique(requiredProviderKinds, 3);
            if (requiredProviderKinds.contains(ProviderKind.INTERNAL)) throw invalid("Internal attestation is not an external provider requirement");
            allowedClassifications = unique(allowedClassifications, 3);
            if (!Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED").containsAll(allowedClassifications))
                throw invalid("Unknown data classification");
            if (!requireVerifiedProviderAccount || !requireAuthenticatedWebhook)
                throw invalid("Provider identity and authenticated callbacks are mandatory");
            if (minimumRetentionDays == null || minimumRetentionDays < 1 || minimumRetentionDays > 36_500
                    || probeMaxAgeSeconds == null || probeMaxAgeSeconds < 60 || probeMaxAgeSeconds > 86_400)
                throw invalid("Invalid policy bounds");
            if (signingEnabled && (requiredProviderKinds.isEmpty() || allowedClassifications.isEmpty()))
                throw invalid("Enabled signing requires explicit providers and classifications");
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyDraft", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Draft(UUID versionId, long revision, Rules rules, String rulesSha256,
                        UUID originalMakerPersonPublicId, UUID lastEditorPersonPublicId, Instant createdAt) {
        public Draft {
            required(versionId); version(revision); required(rules); sha(rulesSha256);
            required(originalMakerPersonPublicId); required(lastEditorPersonPublicId); required(createdAt);
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyPublished", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Published(UUID versionId, long revision, Rules rules, String rulesSha256,
                            UUID originalMakerPersonPublicId, UUID lastEditorPersonPublicId,
                            UUID checkerPersonPublicId, UUID reviewEvidenceId, String reviewContentSha256,
                            Instant publishedAt) {
        public Published {
            required(versionId); version(revision); required(rules); sha(rulesSha256);
            required(originalMakerPersonPublicId); required(lastEditorPersonPublicId); required(checkerPersonPublicId);
            required(reviewEvidenceId); sha(reviewContentSha256); required(publishedAt);
            if (checkerPersonPublicId.equals(originalMakerPersonPublicId) || checkerPersonPublicId.equals(lastEditorPersonPublicId))
                throw invalid("Policy publication requires an independent checker");
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyView", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record View(Scope scope, UUID policyId, long version, Draft workingDraft, Published published,
                       PublishReview publishReview) {
        public View {
            required(scope); required(policyId); SignatureProviderModel.version(version);
            if (workingDraft == null && published == null) throw invalid("A policy requires an actual draft or publication");
            if (workingDraft != null && published != null && workingDraft.versionId().equals(published.versionId()))
                throw invalid("A published version cannot also be the working draft");
            if (publishReview != null && (workingDraft == null || publishReview.policyVersion() != version
                    || !publishReview.draftVersionId().equals(workingDraft.versionId())
                    || publishReview.draftRevision() != workingDraft.revision()))
                throw invalid("Publish review must bind the current draft and head CAS");
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyPublishReview", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PublishReview(UUID draftVersionId, long policyVersion, long draftRevision,
                                String reviewContentSha256, GateState eligibility, List<String> reasonCodes,
                                boolean stepUpRequired, Instant validUntil) {
        public PublishReview {
            required(draftVersionId); version(policyVersion); version(draftRevision); sha(reviewContentSha256);
            required(eligibility); reasonCodes = reasons(reasonCodes);
            if (!stepUpRequired) throw invalid("Reviewed policy publication always requires native HIGH approval");
            if (eligibility == GateState.ELIGIBLE && (validUntil == null || !reasonCodes.isEmpty()))
                throw invalid("Current publish eligibility requires a bounded authority window without denial reasons");
            if (eligibility != GateState.ELIGIBLE && reasonCodes.isEmpty())
                throw invalid("Unevaluated or blocked publication must report its actual reasons");
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyInitializeInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record InitializeInput(Boolean expectedAbsent, String expectedSourceRevision, String expectedSourceSha256,
                                  Rules rules, String idempotencyKey) {
        public InitializeInput {
            if (!Boolean.TRUE.equals(expectedAbsent)) throw invalid("Explicit absence CAS is required");
            source(expectedSourceRevision, expectedSourceSha256); required(rules); key(idempotencyKey);
            if (rules.signingEnabled() || !rules.requiredProviderKinds().isEmpty())
                throw invalid("Initial policy must disable signing without fabricated required providers");
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyDraftInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record DraftInput(Long expectedVersion, UUID expectedDraftVersionId, String expectedSourceRevision,
                             String expectedSourceSha256, Rules rules, String idempotencyKey) {
        public DraftInput {
            version(required(expectedVersion)); required(expectedDraftVersionId);
            source(expectedSourceRevision, expectedSourceSha256); required(rules); key(idempotencyKey);
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyPublishInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PublishInput(Long expectedVersion, UUID expectedDraftVersionId, String expectedSourceRevision,
                               String expectedSourceSha256, String reviewContentSha256, String idempotencyKey) {
        public PublishInput {
            version(required(expectedVersion)); required(expectedDraftVersionId); source(expectedSourceRevision, expectedSourceSha256);
            sha(reviewContentSha256); key(idempotencyKey);
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyHistoryItem", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record HistoryItem(UUID versionId, long revision, String state, Rules rules, String rulesSha256,
                              UUID originalMakerPersonPublicId, UUID lastEditorPersonPublicId,
                              UUID checkerPersonPublicId, UUID reviewEvidenceId, String reviewContentSha256,
                              Instant createdAt, Instant publishedAt) {
        public HistoryItem {
            required(versionId); version(revision); required(rules); sha(rulesSha256); required(createdAt);
            required(originalMakerPersonPublicId); required(lastEditorPersonPublicId);
            if (!Set.of("DRAFT", "PUBLISHED").contains(state)) throw invalid("Unknown policy version state");
            if (state.equals("PUBLISHED")) new Published(versionId, revision, rules, rulesSha256,
                    originalMakerPersonPublicId, lastEditorPersonPublicId, checkerPersonPublicId,
                    reviewEvidenceId, reviewContentSha256, required(publishedAt));
            else if (checkerPersonPublicId != null || reviewEvidenceId != null || reviewContentSha256 != null || publishedAt != null)
                throw invalid("Draft history cannot fabricate publication evidence");
        }
    }

    @Schema(name="ApprovalSignatureProviderPolicyHistory", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record History(Scope scope, UUID policyId, List<HistoryItem> items, String nextCursor, boolean truncated) {
        public History {
            required(scope); required(policyId); items = bounded(items, MAX_HISTORY);
            if (nextCursor != null) text(nextCursor, 4096);
            if (truncated != (nextCursor != null)) throw invalid("History continuation must be truthful");
            long previous = Long.MAX_VALUE;
            for (var item : items) {
                if (item.revision() >= previous) throw invalid("Policy history must be descending and unique");
                previous = item.revision();
            }
        }
    }

    private static void source(String revision, String digest) {
        sha(digest); if (!("sigp-" + digest).equals(revision)) throw invalid("Invalid source revision");
    }
}
