package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;
import static org.assertj.core.api.Assertions.*;

import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.Rules;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignatureProviderContractTest {
    private static final String SHA = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private final SignatureProviderPolicyCompiler compiler = new SignatureProviderPolicyCompiler(new ObjectMapper());

    @Test void keepsEveryPublicRecordSchemaDistinctAndClosed() {
        var names = new java.util.HashSet<String>();
        for (var owner : List.of(ApprovalSignatureProviderDtos.class, ApprovalSignatureProviderPolicyDtos.class))
            for (var record : owner.getDeclaredClasses()) if (record.isRecord()) {
                var annotation = record.getAnnotation(Schema.class);
                assertThat(annotation).isNotNull(); assertThat(names.add(annotation.name())).isTrue();
                assertThat(annotation.additionalProperties()).isEqualTo(Schema.AdditionalPropertiesValue.FALSE);
            }
    }

    @Test void representsMissingPolicyWithNullValuesRatherThanAnInventedEmptyDenominator() {
        var policy = new Policy(PolicySourceState.NOT_CONFIGURED, null, null, null, null, null);
        var kpis = new Kpis(1, 0, 0, null, null, GateState.NOT_EVALUATED, List.of("POLICY_NOT_CONFIGURED"), null, null);
        assertThat(policy.requiredProviderKinds()).isNull(); assertThat(kpis.requiredProviderCount()).isNull();
        assertThatThrownBy(() -> new Policy(PolicySourceState.NOT_CONFIGURED, null, List.of(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Kpis(1, 0, 0, null, null, GateState.ELIGIBLE, List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void doesNotCallAnImplementedButDisabledAdapterMissingDevelopment() {
        assertThat(card(ProviderKind.DOCUSIGN, true, Readiness.DISABLED)).extracting(ProviderCard::readiness).isEqualTo(Readiness.DISABLED);
        assertThatThrownBy(() -> card(ProviderKind.DOCUSIGN, true, Readiness.MISSING_INTERNAL)).isInstanceOf(IllegalArgumentException.class);
        assertThat(card(ProviderKind.CUSTOM, false, Readiness.MISSING_INTERNAL).providerId()).isNotNull();
    }

    @Test void rejectsTypeAndCredentialRegistrationAsProductionVerification() {
        assertThatThrownBy(() -> new ProviderCard(UUID.randomUUID(), ProviderKind.DOCUSIGN, "DocuSign", 0L, SHA,
                true, true, true, false, null, Environment.PRODUCTION, Readiness.VERIFIED_PRODUCTION,
                List.of(), NOW, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderCard(UUID.randomUUID(), ProviderKind.INTERNAL, "Internal", 0L, SHA,
                true, true, true, true, null, Environment.PRODUCTION, Readiness.VERIFIED_PRODUCTION,
                List.of(), NOW, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void requiresActualEvidenceForPassAndPreservesPairedEvidence() {
        assertThatThrownBy(() -> new Check("PROVIDER_ACCOUNT", ObservationState.PASS, List.of(), NOW, NOW.plusSeconds(60), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Check("PROVIDER_ACCOUNT", ObservationState.NOT_OBSERVED, List.of(), null, null, UUID.randomUUID(), SHA))
                .isInstanceOf(IllegalArgumentException.class);
        var check = new Check("PROVIDER_ACCOUNT", ObservationState.PASS, List.of(), NOW, NOW.plusSeconds(60), UUID.randomUUID(), SHA);
        assertThat(check.evidenceSha256()).isEqualTo(SHA);
    }

    @Test void internalAndKmsKeysDoNotProveHardwareTokens() {
        assertThatThrownBy(() -> new Kms(KmsBackend.AWS_KMS, VerificationKind.HARDWARE_TOKEN, ObservationState.PASS,
                "RSASSA_PSS_SHA_256", SHA, "AWS_KMS", NOW, NOW.plusSeconds(60), UUID.randomUUID(), SHA, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void requiresAnActualVersionReceiptAndRetentionFloorForWormSuccess() {
        assertThatThrownBy(() -> new Worm(ObservationState.PASS, SHA, SHA, ObjectLockMode.COMPLIANCE,
                NOW.plusSeconds(59), false, new SourcePin(UUID.randomUUID(), 0, SHA), 60L,
                NOW, NOW.plusSeconds(30), UUID.randomUUID(), SHA, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void freezesTheWholeProbeCohortAndRejectsDuplicateOrForeignResults() {
        var target = new ProviderTarget(UUID.randomUUID(), 0, SHA, null);
        var targets = new ArrayList<>(List.of(target));
        var input = new ProbeInput("sigp-" + SHA, SHA, true, targets, "probe-1"); targets.clear();
        assertThat(input.targets()).containsExactly(target);
        assertThatThrownBy(() -> new ProbeInput("sigp-" + SHA, SHA, true, List.of(target, target), "probe-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProbeRun(scope(), UUID.randomUUID(), ProbeState.COMPLETE, NOW, NOW,
                SHA, List.of(target), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void doesNotFabricateContinuationOrOfficialConfigurationUrls() {
        assertThatThrownBy(() -> new History(scope(), List.of(), null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuideSection("DS", List.of("CHECK"), List.of("https://www.docusign.com@localhost/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuideSection("DS", List.of("CHECK"), List.of("https://www.docusign.com/redirect?url=https://localhost")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void initializesAnExplicitDisabledPolicyWithoutThreeInventedRequiredProviders() {
        var rules = compiler.disabledInitialRules(); var compiled = compiler.compile(rules);
        assertThat(rules.signingEnabled()).isFalse(); assertThat(rules.requiredProviderKinds()).isEmpty();
        assertThat(compiled.sha256()).matches("[a-f0-9]{64}");
        assertThat(compiled.canonicalJson()).startsWith("{\"allowedClassifications\":[]");
    }

    @Test void rejectsMissingOrLoweredMandatorySecurityFlagsAndInvalidPolicyBounds() {
        assertThatThrownBy(() -> rules(false, false, true, 365L, 3600L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules(false, true, false, 365L, 3600L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules(false, true, true, 0L, 3600L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules(false, true, true, 365L, 86_401L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules(null, true, true, 365L, 3600L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void bindsReviewToTheOriginalMakerLastEditorAndExactDraft() {
        var compiled = compiler.compile(compiler.disabledInitialRules()); UUID id = UUID.randomUUID(), draft = UUID.randomUUID();
        UUID maker = UUID.randomUUID(), editor = UUID.randomUUID();
        String digest = compiler.reviewDigest(id, 1, draft, 0, maker, editor, compiled);
        assertThat(digest).isNotEqualTo(compiler.reviewDigest(id, 2, draft, 0, maker, editor, compiled));
        assertThat(digest).isNotEqualTo(compiler.reviewDigest(id, 1, UUID.randomUUID(), 0, maker, editor, compiled));
        assertThat(digest).isNotEqualTo(compiler.reviewDigest(id, 1, draft, 0, editor, maker, compiled));
    }

    @Test void keepsNineteenNativeOperationsSeparateFromOldSelfRoutesAndHeadAliases() {
        assertThat(SignatureProviderOperation.values()).hasSize(19);
        var routes = new java.util.HashSet<String>();
        for (var operation : SignatureProviderOperation.values()) {
            assertThat(routes.add(operation.routeContractKey())).isTrue();
            UUID target = operation.pathTemplate().contains("{") ? UUID.randomUUID() : null;
            UUID artifact = operation == SignatureProviderOperation.EXTERNAL_ARTIFACT ? UUID.randomUUID() : null;
            String path = operation.path(target, artifact);
            assertThat(SignatureProviderOperation.resolve(operation.method(), path)).contains(operation);
            assertThat(SignatureProviderOperation.resolve("HEAD", path)).isEmpty();
            assertThat(SignatureProviderOperation.resolve(operation.method(), path + "/")).isEmpty();
        }
        assertThat(SignatureProviderOperation.resolve("POST", "/v1/signature-requests/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/sign")).isEmpty();
        assertThat(SignatureProviderOperation.resolve("POST", "/v1/admin/signatures/policies/AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA/publish")).isEmpty();
    }

    @Test void bindsAuthoritativePublishReviewAndNeverInfersHighApprovalFromReadAccess() {
        var rules = compiler.disabledInitialRules(); UUID draftId = UUID.randomUUID();
        var draft = new ApprovalSignatureProviderPolicyDtos.Draft(draftId, 2, rules, SHA, UUID.randomUUID(), UUID.randomUUID(), NOW);
        var review = new ApprovalSignatureProviderPolicyDtos.PublishReview(draftId, 3, 2, SHA,
                GateState.NOT_EVALUATED, List.of("PUBLISH_AUTHORITY_NOT_EVALUATED"), true, null);
        var view = new ApprovalSignatureProviderPolicyDtos.View(scope(), UUID.randomUUID(), 3, draft, null, review);
        assertThat(view.publishReview().eligibility()).isEqualTo(GateState.NOT_EVALUATED);
        assertThatThrownBy(() -> new ApprovalSignatureProviderPolicyDtos.View(scope(), UUID.randomUUID(), 4, draft, null, review))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalSignatureProviderPolicyDtos.PublishReview(draftId, 3, 2, SHA,
                GateState.ELIGIBLE, List.of(), true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalSignatureProviderPolicyDtos.PublishReview(draftId, 3, 2, SHA,
                GateState.ELIGIBLE, List.of(), false, NOW.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pinsPolicyKpisAndProviderRequirementsToTheSameActualPublishedVersion() {
        var published = new Policy(PolicySourceState.AVAILABLE, new SourcePin(UUID.randomUUID(), 3, SHA), List.of(), 3600L, 60L, 365L);
        var kpis = new Kpis(0, 0, 0, 0, List.of(), GateState.NOT_EVALUATED, List.of("REQUEST_NOT_SELECTED"), null, 60L);
        var kms = new Kms(KmsBackend.NONE, VerificationKind.NONE, ObservationState.NOT_CONFIGURED,
                null, null, "SERVER_REGISTERED_CONFIG", null, null, null, null, List.of("KEY_NOT_CONFIGURED"));
        var worm = new Worm(ObservationState.NOT_CONFIGURED, null, null, ObjectLockMode.NONE,
                null, null, null, null, null, null, null, null, List.of("STORAGE_NOT_CONFIGURED"));
        assertThat(new Overview(scope(), published, kpis, List.of(), List.of(), kms, worm).policy().pin()).isEqualTo(published.pin());
        var drift = new Kpis(0, 0, 0, 1, List.of(ProviderKind.DOCUSIGN), GateState.NOT_EVALUATED, List.of("REQUEST_NOT_SELECTED"), null, 60L);
        assertThatThrownBy(() -> new Overview(scope(), published, drift, List.of(), List.of(), kms, worm)).isInstanceOf(IllegalArgumentException.class);
        var intervalDrift = new Kpis(0, 0, 0, 0, List.of(), GateState.NOT_EVALUATED, List.of("REQUEST_NOT_SELECTED"), null, 120L);
        assertThatThrownBy(() -> new Overview(scope(), published, intervalDrift, List.of(), List.of(), kms, worm)).isInstanceOf(IllegalArgumentException.class);
        var unknown = new Policy(PolicySourceState.NOT_CONFIGURED, null, null, null, null, null);
        var declared = new ProviderCard(UUID.randomUUID(), ProviderKind.DOCUSIGN, "DocuSign", 0L, SHA,
                false, false, false, false, false, Environment.UNCONFIGURED, Readiness.MISSING_INTERNAL, List.of(), null, List.of());
        var settings = new Settings(null, Environment.UNCONFIGURED, null, null, false, "NOT_CONFIGURED", "SERVER_REGISTERED");
        var guide = new Guide(List.of());
        assertThatThrownBy(() -> new ProviderDiagnostics(scope(), unknown, declared, settings, List.of(), guide, List.of(), kms, worm))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Rules rules(Boolean enabled, Boolean account, Boolean webhook, Long days, Long maxAge) {
        return new Rules(enabled, List.of(), List.of(), account, webhook, true, true, true, true, days, maxAge, null, null);
    }
    private ProviderCard card(ProviderKind kind, boolean installed, Readiness readiness) {
        return new ProviderCard(UUID.randomUUID(), kind, kind.name(), 0L, SHA, installed, false, false, false,
                null, Environment.UNCONFIGURED, readiness, List.of(), null, List.of());
    }
    private Scope scope() { return new Scope("RS_APPROVALS", "opaque", "psr-" + SHA, SHA, "sigp-" + SHA, SHA, NOW); }
}
