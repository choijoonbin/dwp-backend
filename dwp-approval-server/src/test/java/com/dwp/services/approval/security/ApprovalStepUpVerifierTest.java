package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalStepUpVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private KeyPair keyPair;
    private ApprovalStepUpVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        verifier = new ApprovalStepUpVerifier(
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                keyPair.getPublic(),
                "https://auth.example.test",
                "dwp-approval-server",
                "auth-step-up-2026-08",
                "urn:dwp:acr:mfa",
                600,
                900);
    }

    @Test
    void verifiesEveryCommandBoundClaimAndFreshMfaEvidence() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();

        ApprovalStepUpVerifier.VerifiedChallenge verified = verifier.verify(
                sign(claims(binding)), binding);

        assertThat(verified.challengeId()).isEqualTo("challenge-1");
        assertThat(verified.nonce()).isEqualTo("nonce-1");
        assertThat(verified.binding()).isEqualTo(binding);
    }

    @Test
    void rejectsPayloadTargetRevisionAndSignatureMismatch() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();
        Map<String, Object> claims = claims(binding);
        claims.put("payload_sha256", "0".repeat(64));

        assertConflict(() -> verifier.verify(sign(claims), binding));

        Map<String, Object> wrongTarget = claims(binding);
        wrongTarget.put("target_version", 8L);
        assertConflict(() -> verifier.verify(sign(wrongTarget), binding));

        Map<String, Object> wrongRevision = claims(binding);
        wrongRevision.put("decision_revision", "stale-revision");
        assertConflict(() -> verifier.verify(sign(wrongRevision), binding));

        KeyPairGenerator other = KeyPairGenerator.getInstance("RSA");
        other.initialize(2048);
        KeyPair saved = keyPair;
        keyPair = other.generateKeyPair();
        String wrongSignature = sign(claims(binding));
        keyPair = saved;
        assertConflict(() -> verifier.verify(wrongSignature, binding));
    }

    @Test
    void rejectsAnExpectedVersionPayloadChallengeForTheHeaderBoundRetryCommand()
            throws Exception {
        ApprovalStepUpVerifier.CommandBinding canonical = retryBinding(Map.of());
        ApprovalStepUpVerifier.CommandBinding legacyBody = retryBinding(
                Map.of("expectedVersion", 2L));

        assertThat(canonical.payloadSha256()).isNotEqualTo(legacyBody.payloadSha256());
        assertConflict(() -> verifier.verify(sign(claims(legacyBody)), canonical));
    }

    @Test
    void rejectsOwnerRouteContextAndCanonicalCommandDigestMismatch() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();

        for (Map.Entry<String, Object> mismatch : Map.<String, Object>of(
                "owner_service_key", "people",
                "command_contract_key", "route.approvals.admin.form-publish.action",
                "context_key", "stale-context",
                "command_sha256", "0".repeat(64)).entrySet()) {
            Map<String, Object> claims = claims(binding);
            claims.put(mismatch.getKey(), mismatch.getValue());
            assertConflict(() -> verifier.verify(sign(claims), binding));
        }
    }

    @Test
    void doesNotAcceptAmrWithoutAuthTimeOrStaleAuthentication() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();
        Map<String, Object> missing = claims(binding);
        missing.remove("auth_time");
        assertStepUpRequired(() -> verifier.verify(sign(missing), binding));

        Map<String, Object> stale = claims(binding);
        stale.put("auth_time", NOW.minusSeconds(601).getEpochSecond());
        assertStepUpRequired(() -> verifier.verify(sign(stale), binding));
    }

    @Test
    void requiresAnExactJwtHeaderAndRejectsRemoteKeyOrCriticalMetadata() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();
        String claims = objectMapper.writeValueAsString(claims(binding));

        assertConflict(() -> verifier.verify(signRaw(
                "{\"alg\":\"RS256\",\"kid\":\"auth-step-up-2026-08\"}", claims), binding));
        assertConflict(() -> verifier.verify(signRaw(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\","
                        + "\"kid\":\"auth-step-up-2026-08\","
                        + "\"jku\":\"https://attacker.example/jwks\"}", claims), binding));
        assertConflict(() -> verifier.verify(signRaw(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\","
                        + "\"kid\":\"auth-step-up-2026-08\",\"crit\":[\"exp\"]}",
                claims), binding));
    }

    @Test
    void rejectsDuplicateJsonMembersOversizedTokensAndNonIntegralClaims() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();
        String validClaims = objectMapper.writeValueAsString(claims(binding));
        String duplicateSubject = validClaims.replace(
                "\"sub\":\"17\"", "\"sub\":\"17\",\"sub\":\"17\"");
        assertThat(duplicateSubject).isNotEqualTo(validClaims);
        assertConflict(() -> verifier.verify(signRaw(validHeader(), duplicateSubject), binding));

        Map<String, Object> decimalVersion = claims(binding);
        decimalVersion.put("target_version", 7.0d);
        assertConflict(() -> verifier.verify(sign(decimalVersion), binding));

        assertConflict(() -> verifier.verify("a".repeat(16_385), binding));
        assertConflict(() -> verifier.verify("a=.b.c", binding));
    }

    @Test
    void acceptsOnlyTheExactAudienceAndStrictAmrArray() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();
        Map<String, Object> textualAudience = claims(binding);
        textualAudience.put("aud", "dwp-approval-server");
        assertThat(verifier.verify(sign(textualAudience), binding).binding()).isEqualTo(binding);

        Map<String, Object> multipleAudiences = claims(binding);
        multipleAudiences.put("aud", List.of("dwp-approval-server", "another-service"));
        assertConflict(() -> verifier.verify(sign(multipleAudiences), binding));

        Map<String, Object> malformedAmr = claims(binding);
        malformedAmr.put("amr", List.of("mfa", "mfa"));
        assertConflict(() -> verifier.verify(sign(malformedAmr), binding));
    }

    @Test
    void bindsAuthenticationTimeToTheSignedChallengeCeremonyWindow() throws Exception {
        ApprovalStepUpVerifier.CommandBinding binding = binding();
        Map<String, Object> futureOfIssue = claims(binding);
        futureOfIssue.put("auth_time", NOW.plusSeconds(1).getEpochSecond());

        assertConflict(() -> verifier.verify(sign(futureOfIssue), binding));
    }

    @Test
    void failsUnavailableWhenTheVerifierKeyOrIssuerIsNotConfigured() {
        ApprovalStepUpVerifier unavailable = new ApprovalStepUpVerifier(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), null,
                "", "dwp-approval-server", "kid", "urn:dwp:acr:mfa", 600, 900);

        assertThatThrownBy(() -> unavailable.verify("a.b.c", binding()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void canonicalPayloadDigestIsIndependentOfMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("reviewComment", "approved");
        first.put("expectedVersion", 4L);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("expectedVersion", 4L);
        second.put("reviewComment", "approved");

        assertThat(verifier.payloadSha256(first)).isEqualTo(verifier.payloadSha256(second));
    }

    private ApprovalStepUpVerifier.CommandBinding binding() {
        return new ApprovalStepUpVerifier.CommandBinding(
                17L,
                42L,
                "route.approvals.admin.workflow-publish.action",
                "approval-management",
                "STEPUP-MGMT-HIGH-V1",
                "approvals.design.publish",
                "S_APPROVALS",
                "WORKFLOW",
                "14d7b229-4752-4a50-8ac1-ecc129620649",
                7L,
                "POST",
                "/api/approvals/v1/admin/workflows/14d7b229-4752-4a50-8ac1-ecc129620649/publish",
                "idempotency-1",
                verifier.payloadSha256(Map.of("expectedVersion", 7L)),
                "D-A-WF-PUB-R1");
    }

    private ApprovalStepUpVerifier.CommandBinding retryBinding(Object payload) {
        return new ApprovalStepUpVerifier.CommandBinding(
                17L,
                42L,
                "route.approvals.admin.operations.retry.action",
                "approval-management",
                "STEPUP-MGMT-HIGH-V1",
                "approvals.operations.execute",
                "S_APPROVALS",
                "OUTBOX_EVENT",
                "00000000-0000-0000-0000-000000000004",
                2L,
                "POST",
                "/api/approvals/v1/admin/operations/events/"
                        + "00000000-0000-0000-0000-000000000004/retry",
                "idem-high-recovery-1",
                verifier.payloadSha256(payload),
                "D-A-OPS-R1");
    }

    private Map<String, Object> claims(ApprovalStepUpVerifier.CommandBinding binding) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://auth.example.test");
        claims.put("aud", List.of("dwp-approval-server"));
        claims.put("sub", Long.toString(binding.actorUserId()));
        claims.put("tenant_id", binding.tenantId());
        claims.put("owner_service_key", "approval");
        claims.put("command_contract_key", binding.commandContractKey());
        claims.put("iat", NOW.minusSeconds(30).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(30).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(600).getEpochSecond());
        claims.put("auth_time", NOW.minusSeconds(120).getEpochSecond());
        claims.put("acr", "urn:dwp:acr:mfa");
        claims.put("amr", List.of("pwd", "mfa"));
        claims.put("jti", "challenge-1");
        claims.put("nonce", "nonce-1");
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
        claims.put("command_sha256", commandSha256(binding));
        claims.put("decision_revision", binding.decisionRevision());
        return claims;
    }

    private String commandSha256(ApprovalStepUpVerifier.CommandBinding binding) {
        return ProductSurfaceStepUpChallengeContract.commandSha256(
                new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                        binding.commandContractKey(), "approval", "dwp-approval-server",
                        binding.commandMethod(), binding.commandPath(), binding.contextKey(),
                        binding.scopeRef(), binding.targetType(), binding.targetId(),
                        binding.targetVersion(), binding.idempotencyKey(),
                        binding.payloadSha256(), binding.decisionRevision()));
    }

    private String sign(Map<String, Object> claims) throws Exception {
        return signRaw(validHeader(), objectMapper.writeValueAsString(claims));
    }

    private String validHeader() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "alg", "RS256",
                "typ", "JWT",
                "kid", "auth-step-up-2026-08"));
    }

    private String signRaw(String headerJson, String claimsJson) throws Exception {
        String header = encoded(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload = encoded(claimsJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encoded(signature.sign());
    }

    private String encoded(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void assertConflict(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    private void assertStepUpRequired(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
