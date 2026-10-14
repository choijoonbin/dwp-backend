package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NavigationPolicyStepUpEvidenceVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-14T04:00:00Z");
    private static final String ISSUER = "https://auth.example.test";
    private static final String AUDIENCE = "dwp-platform-server";
    private static final String KEY_ID = "platform-step-up-2026-10";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final NavigationPolicyStepUpReplayRepository replay =
            mock(NavigationPolicyStepUpReplayRepository.class);
    private KeyPair keyPair;
    private NavigationPolicyStepUpEvidenceVerifier verifier;
    private ProductSurfaceStepUpChallengeVerifier payloads;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        String publicKey = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        verifier = new NavigationPolicyStepUpEvidenceVerifier(
                mapper, replay, Clock.fixed(NOW, ZoneOffset.UTC),
                publicKey, ISSUER, AUDIENCE, KEY_ID, "urn:dwp:acr:mfa", 600, 900);
        payloads = new ProductSurfaceStepUpChallengeVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                ProductSurfaceStepUpChallengeVerifier.parsePublicKey(publicKey),
                new ProductSurfaceStepUpChallengeVerifier.Policy(
                        "platform", ISSUER, AUDIENCE, KEY_ID,
                        "urn:dwp:acr:mfa", 600, 900));
    }

    @Test
    void rejectsMissingExactRequestAuthorityWithoutConsumingReplay() {
        assertThatThrownBy(() -> verifier.verify(evidence(null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Exact HCM Platform NAV policy command authority");
        verifyNoInteractions(replay);
    }

    @Test
    void exactAuthorityStillFailsClosedWhenSignatureKeyIsUnconfigured() {
        NavigationPolicyStepUpEvidenceVerifier unconfigured =
                new NavigationPolicyStepUpEvidenceVerifier(
                        mapper, replay, Clock.fixed(NOW, ZoneOffset.UTC),
                        "", "", AUDIENCE, "", "urn:dwp:acr:mfa", 600, 900);
        HcmPlatformPepRegistry.RouteAuthority authority =
                authority();

        assertThatThrownBy(() -> unconfigured.verify(evidence(authority)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not configured");
        verifyNoInteractions(replay);
    }

    @Test
    void verifiesAuthSignedPublicGatewayPathWhileFencingTheServiceRequestPath()
            throws Exception {
        HcmPlatformPepRegistry.RouteAuthority authority = authority();
        StepUpEvidenceVerifier.StepUpEvidence unsigned = evidence(authority);
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        101, 42,
                        "route.hcm.configuration.navigation-policy-revision-publish.action",
                        "hcm-context-1", "STEPUP-MGMT-HIGH-V1",
                        "hcm.configuration.navigation-policy.publish",
                        "hcm-scope-" + "a".repeat(40),
                        "HRIS_NAVIGATION_POLICY_REVISION",
                        unsigned.revisionId().toString(), 7, "POST",
                        "/api/platform/v1/admin/hris/navigation-policies/revisions/"
                                + unsigned.revisionId() + "/publish",
                        "publish-policy-0001",
                        payloads.payloadSha256(unsigned.canonicalPayload()),
                        "decision-7");
        String token = token(binding);

        assertThat(verifier.verify(evidence(authority, token))).isTrue();
        verify(replay).consume(any(ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge.class));

        StepUpEvidenceVerifier.StepUpEvidence hostileServicePath =
                new StepUpEvidenceVerifier.StepUpEvidence(
                        unsigned.tenantId(), unsigned.actorUserId(), unsigned.action(),
                        unsigned.revisionId(), unsigned.expectedObjectVersion(),
                        unsigned.contextKey(), unsigned.contextScopeKey(),
                        unsigned.idempotencyKey(), unsigned.commandMethod(),
                        "/api/platform/v1/admin/hris/navigation-policies/revisions/"
                                + unsigned.revisionId() + "/publish",
                        unsigned.canonicalPayload(), unsigned.decisionRevision(), token,
                        unsigned.requestAuthority());
        assertThatThrownBy(() -> verifier.verify(hostileServicePath))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("request path mismatch");
    }

    private StepUpEvidenceVerifier.StepUpEvidence evidence(
            HcmPlatformPepRegistry.RouteAuthority authority) {
        return evidence(authority, "unsigned");
    }

    private StepUpEvidenceVerifier.StepUpEvidence evidence(
            HcmPlatformPepRegistry.RouteAuthority authority,
            String proof) {
        UUID revision = UUID.fromString("00000000-0000-0000-0000-000000000077");
        return new StepUpEvidenceVerifier.StepUpEvidence(
                42, 101, "PUBLISH", revision, 7,
                "hcm-context-1", "hcm-scope-" + "a".repeat(40),
                "publish-policy-0001", "POST",
                "/v1/admin/hris/navigation-policies/revisions/" + revision + "/publish",
                Map.of("expectedVersion", 7, "requestDigest", "b".repeat(64),
                        "reason", "GOVERNED_CHANGE"),
                "decision-7", proof, authority);
    }

    private HcmPlatformPepRegistry.RouteAuthority authority() {
        return new HcmPlatformPepRegistry.RouteAuthority(
                "route.hcm.configuration.navigation-policy-revision-publish.action",
                "ACTION", "POST",
                "/v1/admin/hris/navigation-policies/revisions/{revisionId}/publish",
                "configuration-publisher", true,
                "hcm.configuration.navigation-policy.publish",
                "STEPUP-MGMT-HIGH-V1",
                new HcmPlatformPepRegistry.StepUpCommandBinding(
                        "HRIS_NAVIGATION_POLICY_REVISION", "revisionId",
                        "COMMAND_BODY", "expectedVersion", "platform",
                        AUDIENCE));
    }

    private String token(ProductSurfaceStepUpChallengeVerifier.CommandBinding binding)
            throws Exception {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KEY_ID);
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", ISSUER);
        claims.put("sub", Long.toString(binding.actorUserId()));
        claims.put("aud", AUDIENCE);
        claims.put("jti", "nav-challenge-1");
        claims.put("nonce", "nav-nonce-1");
        claims.put("iat", NOW.minusSeconds(10).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(10).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.put("auth_time", NOW.minusSeconds(30).getEpochSecond());
        claims.put("acr", "urn:dwp:acr:mfa");
        claims.putArray("amr").add("pwd").add("mfa");
        claims.put("tenant_id", binding.tenantId());
        claims.put("owner_service_key", "platform");
        claims.put("command_contract_key", binding.commandContractKey());
        claims.put("activation_policy", binding.activationPolicy());
        claims.put("capability_contract_key", binding.capabilityContractKey());
        claims.put("context_key", binding.contextKey());
        claims.put("scope_ref", binding.scopeRef());
        claims.put("target_type", binding.targetType());
        claims.put("target_id", binding.targetId());
        claims.put("target_version", binding.targetVersion());
        claims.put("command_method", binding.commandMethod());
        claims.put("command_path", binding.commandPath());
        claims.put("idempotency_key", binding.idempotencyKey());
        claims.put("payload_sha256", binding.payloadSha256());
        claims.put("command_sha256", ProductSurfaceStepUpChallengeContract.commandSha256(
                new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                        binding.commandContractKey(), "platform", AUDIENCE,
                        binding.commandMethod(), binding.commandPath(), binding.contextKey(),
                        binding.scopeRef(), binding.targetType(), binding.targetId(),
                        binding.targetVersion(), binding.idempotencyKey(),
                        binding.payloadSha256(), binding.decisionRevision())));
        claims.put("decision_revision", binding.decisionRevision());
        String input = encoded(header) + '.' + encoded(claims);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + '.' + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
    }

    private String encoded(ObjectNode value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(value));
    }
}
