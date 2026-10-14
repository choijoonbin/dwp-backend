package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollStepUpVerifierContractTest {

    private static final Instant NOW = Instant.parse("2026-10-14T02:00:00Z");
    private static final String ISSUER = "https://auth.example.test";
    private static final String AUDIENCE = "dwp-payroll-server";
    private static final String KEY_ID = "pay-step-up-2026-10";
    private static final String DECISION = "decision-41";
    private static final UUID RUN_ID =
            UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-abcdef123456");
    private static final String PATH =
            "/api/payroll/v1/runs/" + RUN_ID + "/approvals";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private KeyPair keyPair;
    private PayrollStepUpVerifier verifier;
    private PayrollHighRiskCommandGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        String publicKey = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        verifier = new PayrollStepUpVerifier(
                mapper, clock, publicKey, ISSUER, AUDIENCE, KEY_ID,
                "urn:dwp:acr:mfa", 600, 900);
        guard = new PayrollHighRiskCommandGuard(
                verifier, new PayrollV41PepRegistry(mapper), clock);
    }

    @Test
    void verifiesSignedPathMethodActorTenantPayloadAndRevisionBinding() throws Exception {
        PayrollCalculationAuthority.Actor actor = actor(3, 17, DECISION);
        Map<String, Object> payload = Map.of(
                "reasonCode", "APPROVE", "comment", "four-eyes review");
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = binding(
                actor, payload, PATH, "POST", 4, DECISION, "approve-key");

        PayrollStepUpEvidence evidence = guard.require(
                actor, RUN_ID, 4, PATH, payload,
                new PayrollStepUpHeaders(
                        token(binding, AUDIENCE, NOW.plusSeconds(300), keyPair),
                        "approve-key", DECISION, 4));

        assertThat(evidence.challengeId()).isEqualTo("pay-challenge-1");
        assertThat(evidence.nonce()).isEqualTo("pay-nonce-1");
        assertThat(evidence.payloadSha256()).isEqualTo(binding.payloadSha256());
        assertThat(evidence.decisionRevision()).isEqualTo(DECISION);
    }

    @Test
    void preservesUppercaseRunIdPathAndTargetLexicalForm() throws Exception {
        PayrollCalculationAuthority.Actor actor = actor(3, 17, DECISION);
        Map<String, Object> payload = Map.of("reasonCode", "APPROVE");
        String targetId = RUN_ID.toString().toUpperCase(java.util.Locale.ROOT);
        String path = "/api/payroll/v1/runs/" + targetId + "/approvals";
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = binding(
                actor, payload, path, "POST", 4, DECISION, "approve-key",
                targetId);
        assertThat(targetId).contains("A", "B", "C", "D", "E", "F");
        assertThat(binding.commandPath()).isEqualTo(path);
        assertThat(binding.targetId()).isEqualTo(targetId);

        PayrollStepUpEvidence evidence = guard.require(
                actor, RUN_ID, 4, path, payload,
                new PayrollStepUpHeaders(
                        token(binding, AUDIENCE, NOW.plusSeconds(300), keyPair),
                        "approve-key", DECISION, 4));

        assertThat(evidence.challengeId()).isEqualTo("pay-challenge-1");
    }

    @Test
    void issuerJsonAndActualActionCommandParserHaveIdenticalSignedBytes()
            throws Exception {
        Class<?> actionCommand = Class.forName(
                "com.dwp.services.payroll.calculation."
                        + "PayrollCalculationModels$ActionCommand");
        String omittedJson = "{\"reasonCode\":\" APPROVE \"}";
        JsonNode omittedIssuerTree = mapper.readTree(omittedJson);
        Object omittedOwnerCommand = mapper.readValue(omittedJson, actionCommand);
        assertThat(verifier.payloadSha256(omittedOwnerCommand))
                .isEqualTo(verifier.payloadSha256(omittedIssuerTree));

        String approvalReceiptId =
                "A1B2C3D4-E5F6-4A7B-8C9D-ABCDEF123456";
        String presentJson = "{\"reasonCode\":\" APPROVE \","
                + "\"approvalReceiptId\":\"" + approvalReceiptId + "\","
                + "\"comment\":\" \\t \"}";
        JsonNode issuerTree = mapper.readTree(presentJson);
        Object ownerCommand = mapper.readValue(presentJson, actionCommand);
        assertThat(verifier.payloadSha256(ownerCommand))
                .isEqualTo(verifier.payloadSha256(issuerTree));

        PayrollCalculationAuthority.Actor normalActor =
                actor(3, 17, DECISION, "NORMAL");
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = binding(
                normalActor, issuerTree, PATH, "POST", 4, DECISION,
                "approve-key");
        PayrollStepUpEvidence evidence = guard.require(
                normalActor, RUN_ID, 4, PATH, ownerCommand,
                new PayrollStepUpHeaders(
                        token(binding, AUDIENCE, NOW.plusSeconds(300), keyPair),
                        "approve-key", DECISION, 4));
        assertThat(evidence.payloadSha256()).isEqualTo(binding.payloadSha256());

        PayrollCalculationAuthority.Actor invalidMode =
                actor(3, 17, DECISION, "BREAK_GLASS");
        assertCode(() -> guard.require(
                        invalidMode, RUN_ID, 4, PATH, ownerCommand,
                        new PayrollStepUpHeaders(
                                token(binding, AUDIENCE, NOW.plusSeconds(300), keyPair),
                                "approve-key", DECISION, 4)),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void wrongPathMethodActorTenantVersionDecisionOrPayloadFailsClosed()
            throws Exception {
        PayrollCalculationAuthority.Actor actor = actor(3, 17, DECISION);
        Map<String, Object> payload = Map.of("reasonCode", "APPROVE");
        ProductSurfaceStepUpChallengeVerifier.CommandBinding valid = binding(
                actor, payload, PATH, "POST", 4, DECISION, "approve-key");

        assertMismatch(actor, payload, valid,
                binding(actor, payload, PATH + "/wrong", "POST", 4,
                        DECISION, "approve-key"));
        assertMismatch(actor, payload, valid,
                binding(actor, payload, PATH, "GET", 4,
                        DECISION, "approve-key"));
        assertMismatch(actor(3, 18, DECISION), payload, valid, valid);
        assertMismatch(actor(4, 17, DECISION), payload, valid, valid);
        assertMismatch(actor, payload, valid,
                binding(actor, payload, PATH, "POST", 5,
                        DECISION, "approve-key"));
        assertMismatch(actor, payload, valid,
                binding(actor, Map.of("reasonCode", "TAMPERED"), PATH, "POST", 4,
                        DECISION, "approve-key"));

        String token = token(valid, AUDIENCE, NOW.plusSeconds(300), keyPair);
        assertCode(() -> guard.require(
                        actor(3, 17, "decision-42"), RUN_ID, 4, PATH, payload,
                        new PayrollStepUpHeaders(
                                token, "approve-key", "decision-42", 4)),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
    }

    @Test
    void expiryAudienceAndSignatureFailuresAreRejected() throws Exception {
        PayrollCalculationAuthority.Actor actor = actor(3, 17, DECISION);
        Map<String, Object> payload = Map.of("reasonCode", "APPROVE");
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = binding(
                actor, payload, PATH, "POST", 4, DECISION, "approve-key");

        assertTokenRejected(actor, payload,
                token(binding, AUDIENCE, NOW.minusSeconds(1), keyPair),
                ErrorCode.STEP_UP_REQUIRED);
        assertTokenRejected(actor, payload,
                token(binding, "dwp-people-server", NOW.plusSeconds(300), keyPair),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        assertTokenRejected(actor, payload,
                token(binding, AUDIENCE, NOW.plusSeconds(300),
                        generator.generateKeyPair()),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
    }

    private void assertMismatch(
            PayrollCalculationAuthority.Actor expectedActor,
            Object expectedPayload,
            ProductSurfaceStepUpChallengeVerifier.CommandBinding expected,
            ProductSurfaceStepUpChallengeVerifier.CommandBinding signed)
            throws Exception {
        String token = token(signed, AUDIENCE, NOW.plusSeconds(300), keyPair);
        assertCode(() -> guard.require(
                        expectedActor, RUN_ID, expected.targetVersion(), PATH,
                        expectedPayload, new PayrollStepUpHeaders(
                                token, expected.idempotencyKey(),
                                expected.decisionRevision(), expected.targetVersion())),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
    }

    private void assertTokenRejected(
            PayrollCalculationAuthority.Actor actor,
            Object payload,
            String challenge,
            ErrorCode expected) {
        assertCode(() -> guard.require(
                        actor, RUN_ID, 4, PATH, payload,
                        new PayrollStepUpHeaders(
                                challenge, "approve-key", DECISION, 4)),
                expected);
    }

    private ProductSurfaceStepUpChallengeVerifier.CommandBinding binding(
            PayrollCalculationAuthority.Actor actor,
            Object payload,
            String path,
            String method,
            long version,
            String decision,
            String idempotencyKey) {
        return binding(
                actor, payload, path, method, version, decision, idempotencyKey,
                RUN_ID.toString());
    }

    private ProductSurfaceStepUpChallengeVerifier.CommandBinding binding(
            PayrollCalculationAuthority.Actor actor,
            Object payload,
            String path,
            String method,
            long version,
            String decision,
            String idempotencyKey,
            String targetId) {
        return new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                actor.actorId(), actor.tenantId(),
                PayrollCalculationRoute.APPROVE.routeContractKey(),
                actor.subject().contextKey(), "STEPUP-MGMT-HIGH-V1",
                actor.subject().capabilityContractKey(),
                actor.subject().contextScopeKey(), "PAYROLL_RUN", targetId,
                version, method, path, idempotencyKey,
                verifier.payloadSha256(payload), decision);
    }

    private PayrollCalculationAuthority.Actor actor(
            long tenantId, long actorId, String decision) {
        return actor(tenantId, actorId, decision, "ELEVATED");
    }

    private PayrollCalculationAuthority.Actor actor(
            long tenantId, long actorId, String decision, String accessMode) {
        String scopeKey = "hcm-scope-" + "a".repeat(40);
        UUID legalEntity = UUID.fromString(
                "00000000-0000-0000-0000-000000001103");
        UUID payGroup = UUID.fromString(
                "00000000-0000-0000-0000-000000001104");
        PayrollCalculationAuthority.Subject subject =
                new PayrollCalculationAuthority.Subject(
                        tenantId, actorId,
                        UUID.fromString("00000000-0000-0000-0000-000000001105"),
                        PayrollCalculationAuthority.Action.APPROVE,
                        PayrollCalculationRoute.APPROVE.routeContractKey(),
                        "hcm.operations.payroll.approve", "PAYROLL_PROCESSING",
                        "hcm.operations.payroll.approve", scopeKey, "LEGAL_ENTITY",
                        7, 41, decision, NOW.plusSeconds(300), accessMode,
                        Set.of("APP.HCM:VIEW", "ACTION.HRIS_PAYROLL_APPROVE:APPROVE"));
        return new PayrollCalculationAuthority.Actor(
                subject, new PayrollCalculationAuthority.Scope(
                Set.of(new PayrollCalculationAuthority.PopulationTuple(
                        legalEntity, payGroup, "LEGAL_ENTITY", scopeKey)),
                "projection-41", "c".repeat(64)));
    }

    private String token(
            ProductSurfaceStepUpChallengeVerifier.CommandBinding binding,
            String audience,
            Instant expiresAt,
            KeyPair signerKeyPair) throws Exception {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KEY_ID);
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", ISSUER);
        claims.put("sub", Long.toString(binding.actorUserId()));
        claims.put("aud", audience);
        claims.put("jti", "pay-challenge-1");
        claims.put("nonce", "pay-nonce-1");
        claims.put("iat", NOW.minusSeconds(10).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(10).getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("auth_time", NOW.minusSeconds(30).getEpochSecond());
        claims.put("acr", "urn:dwp:acr:mfa");
        claims.putArray("amr").add("pwd").add("mfa");
        claims.put("tenant_id", binding.tenantId());
        claims.put("owner_service_key", "payroll");
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
        signer.initSign(signerKeyPair.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + '.' + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
    }

    private String commandSha(
            ProductSurfaceStepUpChallengeVerifier.CommandBinding binding,
            String audience) {
        return ProductSurfaceStepUpChallengeContract.commandSha256(
                new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                        binding.commandContractKey(), "payroll", audience,
                        binding.commandMethod(), binding.commandPath(),
                        binding.contextKey(), binding.scopeRef(), binding.targetType(),
                        binding.targetId(), binding.targetVersion(),
                        binding.idempotencyKey(), binding.payloadSha256(),
                        binding.decisionRevision()));
    }

    private String encoded(ObjectNode value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(value));
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ErrorCode expected) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(expected));
    }
}
