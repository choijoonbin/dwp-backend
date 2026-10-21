package com.dwp.core.security;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm012OwnerReceiptAuthorityVerifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void verifiesExactSingleKeyReceiptBoundaryWithoutMakingAnAllowDecision() throws Exception {
        KeyPair keys = rsa();
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        String token = token(keys, now, "correlation-1", "a".repeat(64), null);
        var verifier = new Hrm012OwnerReceiptAuthorityVerifier(
                mapper, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC), "key-1",
                keys.getPublic());

        var evidence = verifier.verify(token,
                new Hrm012OwnerReceiptAuthorityVerifier.Expected(
                        7L, "11", "correlation-1", "a".repeat(64),
                        Set.of("APP.HCM:VIEW")));

        assertThat(evidence.jti()).isEqualTo("marker-1");
        assertThat(evidence.acceptedAppEntitlement()).isEqualTo("APP.HCM:VIEW");
        assertThat(evidence.opaqueStepUpChallengeRef()).isNull();
        assertThat(evidence.markerSha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void rejectsCrossCorrelationAndUnknownClaimsBeforeOwnerReceiptAccess() throws Exception {
        KeyPair keys = rsa();
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        var verifier = new Hrm012OwnerReceiptAuthorityVerifier(
                mapper, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC), "key-1",
                keys.getPublic());
        String token = token(keys, now, "correlation-1", "a".repeat(64), "challenge");

        assertThatThrownBy(() -> verifier.verify(token,
                new Hrm012OwnerReceiptAuthorityVerifier.Expected(
                        7L, "11", "correlation-2", "a".repeat(64),
                        Set.of("APP.HCM:VIEW"))))
                .isInstanceOf(BaseException.class);
    }

    private String token(
            KeyPair keys, Instant now, String correlation, String digest,
            String challenge) throws Exception {
        ObjectNode header = mapper.createObjectNode();
        header.put("typ", Hrm012OwnerReceiptAuthorityContract.TYPE);
        header.put("alg", "RS256");
        header.put("kid", "key-1");
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", Hrm012OwnerReceiptAuthorityContract.ISSUER);
        claims.put("aud", Hrm012OwnerReceiptAuthorityContract.AUDIENCE);
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(30).getEpochSecond());
        claims.put("jti", "marker-1");
        claims.put("state", Hrm012OwnerReceiptAuthorityContract.STATE);
        claims.put("tenant_id", 7L);
        claims.put("on_behalf_of_subject", "11");
        claims.put("route_contract_key", Hrm012OwnerReceiptAuthorityContract.ROUTE);
        claims.put("command_method", "POST");
        claims.put("command_path", Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH);
        claims.put("accepted_app_entitlement", "APP.HCM:VIEW");
        claims.put("feature_key", Hrm012OwnerReceiptAuthorityContract.FEATURE);
        claims.put("feature_decision_revision", "tenant-feature-57");
        claims.put("correlation_id", correlation);
        claims.put("idempotency_key_sha256", digest);
        if (challenge == null) claims.putNull("opaque_step_up_challenge_ref");
        else claims.put("opaque_step_up_challenge_ref", challenge);
        String input = encode(mapper.writeValueAsBytes(header)) + "."
                + encode(mapper.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encode(signer.sign());
    }

    private KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
