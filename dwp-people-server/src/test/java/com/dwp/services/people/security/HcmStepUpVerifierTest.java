package com.dwp.services.people.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HcmStepUpVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-25T01:00:00Z");
    private static final String ISSUER = "https://auth.example.test";
    private static final String AUDIENCE = "dwp-people-server";
    private static final String KEY_ID = "step-up-2026-08";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private KeyPair keyPair;
    private HcmStepUpVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        String publicKey = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        verifier = new HcmStepUpVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), publicKey, ISSUER,
                AUDIENCE, KEY_ID, "urn:dwp:acr:mfa", 600, 900);
    }

    @Test
    void verifiesPeopleOwnerAudienceAndExactCommandDigest() throws Exception {
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = binding();
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verified =
                verifier.verify(token(binding, AUDIENCE), binding);

        assertThat(verified.challengeId()).isEqualTo("challenge-1");
        assertThat(verified.nonce()).isEqualTo("nonce-1");
        assertThat(verified.binding()).isEqualTo(binding);
    }

    @Test
    void wrongTargetPayloadOrOwnerAudienceFailsClosed() throws Exception {
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = binding();
        String token = token(binding, AUDIENCE);

        assertMismatch(token, new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                binding.actorUserId(), binding.tenantId(), binding.commandContractKey(),
                binding.contextKey(), binding.activationPolicy(),
                binding.capabilityContractKey(), binding.scopeRef(), binding.targetType(),
                "different-target", binding.targetVersion(), binding.commandMethod(),
                binding.commandPath(), binding.idempotencyKey(), binding.payloadSha256(),
                binding.decisionRevision()));
        assertMismatch(token, new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                binding.actorUserId(), binding.tenantId(), binding.commandContractKey(),
                binding.contextKey(), binding.activationPolicy(),
                binding.capabilityContractKey(), binding.scopeRef(), binding.targetType(),
                binding.targetId(), binding.targetVersion(), binding.commandMethod(),
                binding.commandPath(), binding.idempotencyKey(), "c".repeat(64),
                binding.decisionRevision()));
        assertMismatch(token(binding, "another-service"), binding);
    }

    @Test
    void missingChallengeRequiresFreshStepUp() {
        assertThatThrownBy(() -> verifier.verify(null, binding()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    @Test
    void exportEnvelopeHashBindsEveryCommandField() throws Exception {
        Map<String, Object> command = Map.of(
                "idempotencyKey", "idem-1",
                "datasetKey", "WORKFORCE_DIRECTORY",
                "selection", Map.of("status", "ACTIVE"),
                "recipientReference", "governor@example.test",
                "purpose", "Quarterly controlled workforce evidence");
        Map<String, Object> envelope = Map.of(
                "dataset", "WORKFORCE_DIRECTORY@v3",
                "population", "hcm-scope-1234",
                "command", command);
        String digest = verifier.payloadSha256(envelope);
        ProductSurfaceStepUpChallengeVerifier.CommandBinding signed = exportBinding(digest);
        String token = token(signed, AUDIENCE);
        assertThat(verifier.verify(token, signed).binding()).isEqualTo(signed);

        Map<String, Object> tampered = Map.of(
                "dataset", "WORKFORCE_DIRECTORY@v3",
                "population", "hcm-scope-1234",
                "command", Map.of(
                        "idempotencyKey", "idem-1",
                        "datasetKey", "WORKFORCE_DIRECTORY",
                        "selection", Map.of("status", "ACTIVE"),
                        "recipientReference", "attacker@example.test",
                        "purpose", "Quarterly controlled workforce evidence"));
        assertMismatch(token, exportBinding(verifier.payloadSha256(tampered)));
    }

    private ProductSurfaceStepUpChallengeVerifier.CommandBinding exportBinding(String digest) {
        return new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                17L, 3L, "route.hcm.management.controlled-export-create.action",
                "hcm.management", "STEPUP-MGMT-CRITICAL-V1",
                "hcm.controlled-export.create", "hcm-scope-1234", "EXPORT_DATASET",
                "WORKFORCE_DIRECTORY@v3:hcm-scope-1234", 3L, "POST",
                "/api/people/v1/workforce/exports", "idem-1", digest,
                "psr-" + "a".repeat(64));
    }

    private void assertMismatch(
            String token,
            ProductSurfaceStepUpChallengeVerifier.CommandBinding expected) {
        assertThatThrownBy(() -> verifier.verify(token, expected))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    private ProductSurfaceStepUpChallengeVerifier.CommandBinding binding() {
        return new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                17L, 3L, "route.hcm.management.org-publish.action",
                "hcm.management", "STEPUP-MGMT-HIGH-V1", "hcm.org-design.publish",
                "scope-1", "ORG_SCENARIO", "scenario-1", 7L, "POST",
                "/api/people/v1/workforce/organization/scenarios/scenario-1/publish",
                "idem-1", "b".repeat(64), "psr-" + "a".repeat(64));
    }

    private String token(
            ProductSurfaceStepUpChallengeVerifier.CommandBinding binding,
            String audience) throws Exception {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KEY_ID);
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", ISSUER);
        claims.put("sub", Long.toString(binding.actorUserId()));
        claims.put("aud", audience);
        claims.put("jti", "challenge-1");
        claims.put("nonce", "nonce-1");
        claims.put("iat", NOW.minusSeconds(10).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(10).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.put("auth_time", NOW.minusSeconds(30).getEpochSecond());
        claims.put("acr", "urn:dwp:acr:mfa");
        claims.putArray("amr").add("pwd").add("mfa");
        claims.put("tenant_id", binding.tenantId());
        claims.put("owner_service_key", "people");
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
        claims.put("command_sha256", commandSha(binding, audience));
        claims.put("decision_revision", binding.decisionRevision());
        String input = encoded(header) + '.' + encoded(claims);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + '.' + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
    }

    private String commandSha(
            ProductSurfaceStepUpChallengeVerifier.CommandBinding binding,
            String audience) {
        return ProductSurfaceStepUpChallengeContract.commandSha256(
                new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                        binding.commandContractKey(), "people", audience,
                        binding.commandMethod(), binding.commandPath(), binding.contextKey(),
                        binding.scopeRef(), binding.targetType(), binding.targetId(),
                        binding.targetVersion(), binding.idempotencyKey(),
                        binding.payloadSha256(), binding.decisionRevision()));
    }

    private String encoded(ObjectNode value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(value));
    }
}
