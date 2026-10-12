package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthSignedClockStepUpVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");
    private static final String ISSUER = "https://auth.example.test";
    private static final String KEY_ID = "tim-step-up-2026-10";
    private static final String DECISION = "psr-" + "a".repeat(64);
    private static final String CONTEXT = "psc-" + "b".repeat(64);
    private static final UUID EVENT_ID =
            UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("72000000-0000-4000-8000-000000000002");
    private static final long EVENT_VERSION = 7L;
    private static final VoidClockCommand COMMAND =
            new VoidClockCommand("DEVICE_DUPLICATE");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private KeyPair keyPair;
    private AuthSignedClockStepUpVerifier verifier;
    private ProductSurfaceStepUpChallengeVerifier payloads;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        String publicKey = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        verifier = new AuthSignedClockStepUpVerifier(
                mapper,
                clock,
                publicKey,
                ISSUER,
                AuthSignedClockStepUpVerifier.AUDIENCE,
                KEY_ID,
                "urn:dwp:acr:mfa",
                600,
                900);
        payloads = new ProductSurfaceStepUpChallengeVerifier(
                mapper,
                clock,
                ProductSurfaceStepUpChallengeVerifier.parsePublicKey(publicKey),
                new ProductSurfaceStepUpChallengeVerifier.Policy(
                        AuthSignedClockStepUpVerifier.OWNER_SERVICE_KEY,
                        ISSUER,
                        AuthSignedClockStepUpVerifier.AUDIENCE,
                        KEY_ID,
                        "urn:dwp:acr:mfa",
                        600,
                        900));
    }

    @Test
    void verifiesExactV36ClockEventOwnerAudienceAndCommandBinding() throws Exception {
        var binding = binding(EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND);

        var verified = verifier.verifyVoid(
                authority(true),
                EVENT_ID,
                EVENT_VERSION,
                IDEMPOTENCY_KEY,
                COMMAND,
                new ClockStepUpHeaders(token(binding, AuthSignedClockStepUpVerifier.AUDIENCE),
                        DECISION, EVENT_VERSION));

        assertThat(verified.challengeId()).isEqualTo("tim-challenge-1");
        assertThat(verified.nonce()).isEqualTo("tim-nonce-1");
        assertThat(verified.binding()).isEqualTo(binding);
        assertThat(verified.binding())
                .satisfies(actual -> {
                    assertThat(actual.commandContractKey())
                            .isEqualTo(AuthSignedClockStepUpVerifier.ROUTE_CONTRACT_KEY);
                    assertThat(actual.targetType()).isEqualTo("CLOCK_EVENT");
                    assertThat(actual.targetId()).isEqualTo(EVENT_ID.toString());
                    assertThat(actual.targetVersion()).isEqualTo(EVENT_VERSION);
                    assertThat(actual.commandPath()).isEqualTo(
                            "/api/time/v1/clock-events/" + EVENT_ID + "/voids");
                    assertThat(actual.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY.toString());
                    assertThat(actual.payloadSha256()).matches("[0-9a-f]{64}");
                    assertThat(actual.decisionRevision()).isEqualTo(DECISION);
                });
    }

    @Test
    void rejectsStaleVersionChangedDecisionAndWrongSignedTargetOrAudience() throws Exception {
        var binding = binding(EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND);
        String valid = token(binding, AuthSignedClockStepUpVerifier.AUDIENCE);

        assertCode(
                () -> verifier.verifyVoid(
                        authority(true), EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND,
                        new ClockStepUpHeaders(valid, DECISION, EVENT_VERSION - 1)),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertCode(
                () -> verifier.verifyVoid(
                        authority(true), EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND,
                        new ClockStepUpHeaders(valid, "psr-" + "f".repeat(64), EVENT_VERSION)),
                ErrorCode.DECISION_REVISION_CONFLICT);

        UUID anotherTarget = UUID.fromString("72000000-0000-4000-8000-000000000099");
        String wrongTarget = token(
                binding(anotherTarget, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND),
                AuthSignedClockStepUpVerifier.AUDIENCE);
        assertCode(
                () -> verifier.verifyVoid(
                        authority(true), EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND,
                        new ClockStepUpHeaders(wrongTarget, DECISION, EVENT_VERSION)),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);

        String wrongAudience = token(binding, "dwp-people-server");
        assertCode(
                () -> verifier.verifyVoid(
                        authority(true), EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND,
                        new ClockStepUpHeaders(wrongAudience, DECISION, EVENT_VERSION)),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
    }

    @Test
    void missingChallengeAndNonElevatedAuthorityFailClosed() {
        assertCode(
                () -> verifier.verifyVoid(
                        authority(true), EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND,
                        new ClockStepUpHeaders("", DECISION, EVENT_VERSION)),
                ErrorCode.STEP_UP_REQUIRED);
        assertCode(
                () -> verifier.verifyVoid(
                        authority(false), EVENT_ID, EVENT_VERSION, IDEMPOTENCY_KEY, COMMAND,
                        new ClockStepUpHeaders("placeholder", DECISION, EVENT_VERSION)),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    private ProductSurfaceStepUpChallengeVerifier.CommandBinding binding(
            UUID eventId,
            long eventVersion,
            UUID idempotencyKey,
            VoidClockCommand command) {
        VerifiedClockRequest authority = authority(true);
        return new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                authority.actorId(),
                authority.tenantId(),
                AuthSignedClockStepUpVerifier.ROUTE_CONTRACT_KEY,
                authority.contextKey(),
                AuthSignedClockStepUpVerifier.ACTIVATION_POLICY,
                AuthSignedClockStepUpVerifier.CAPABILITY_CONTRACT_KEY,
                authority.gatewayScopeKey(),
                AuthSignedClockStepUpVerifier.TARGET_TYPE,
                eventId.toString(),
                eventVersion,
                AuthSignedClockStepUpVerifier.COMMAND_METHOD,
                AuthSignedClockStepUpVerifier.PUBLIC_PATH_PREFIX + eventId + "/voids",
                idempotencyKey.toString(),
                payloads.payloadSha256(command),
                authority.decisionRevision());
    }

    private static VerifiedClockRequest authority(boolean elevated) {
        UUID subject = UUID.fromString("72000000-0000-4000-8000-000000000003");
        UUID population = UUID.fromString("72000000-0000-4000-8000-000000000004");
        UUID worker = UUID.fromString("72000000-0000-4000-8000-000000000005");
        UUID assignment = UUID.fromString("72000000-0000-4000-8000-000000000006");
        return new VerifiedClockRequest(
                41L,
                72L,
                subject,
                Set.of(ClockAction.CORRECT),
                population,
                9,
                11,
                13,
                worker,
                assignment,
                7,
                AuthSignedClockStepUpVerifier.ROUTE_CONTRACT_KEY,
                CONTEXT,
                HcmEligibilityScopeKey.derived(
                        41L, 72L, "hcm.operations.clock-ingestion", "void-fixture",
                        "relationship-9", "population-9"),
                "c".repeat(64),
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64),
                5,
                ClockIngestionService.CAPTURE_PURPOSE,
                17,
                DECISION,
                NOW.plusSeconds(300),
                UUID.fromString("72000000-0000-4000-8000-000000000007"),
                Set.of(worker),
                "scope-revision-13",
                Set.of(),
                Set.of(),
                Set.of(),
                elevated);
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
        claims.put("jti", "tim-challenge-1");
        claims.put("nonce", "tim-nonce-1");
        claims.put("iat", NOW.minusSeconds(10).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(10).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.put("auth_time", NOW.minusSeconds(30).getEpochSecond());
        claims.put("acr", "urn:dwp:acr:mfa");
        claims.putArray("amr").add("pwd").add("mfa");
        claims.put("tenant_id", binding.tenantId());
        claims.put("owner_service_key", AuthSignedClockStepUpVerifier.OWNER_SERVICE_KEY);
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
                        binding.commandContractKey(),
                        AuthSignedClockStepUpVerifier.OWNER_SERVICE_KEY,
                        audience,
                        binding.commandMethod(),
                        binding.commandPath(),
                        binding.contextKey(),
                        binding.scopeRef(),
                        binding.targetType(),
                        binding.targetId(),
                        binding.targetVersion(),
                        binding.idempotencyKey(),
                        binding.payloadSha256(),
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
