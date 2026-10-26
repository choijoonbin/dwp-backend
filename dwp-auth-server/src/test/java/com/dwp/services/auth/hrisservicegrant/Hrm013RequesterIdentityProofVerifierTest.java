package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedSigningKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class Hrm013RequesterIdentityProofVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final UUID APPROVAL = new UUID(0, 71);
    private static final UUID ACTOR = new UUID(0, 72);
    private static final UUID CORRELATION = new UUID(0, 73);
    private static final String KEY_ID = "approval-grant-key-1";
    private static KeyPair signingKey;
    private static String leafThumbprint;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        leafThumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest("approval-leaf".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifiesGrantProofThenReservesReplayThenRechecksAuthority() throws Exception {
        var request = new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                17, APPROVAL, ACTOR, CORRELATION);
        byte[] body = Hrm013RequesterIdentityAuthorityV1.encodeRequest(mapper, request);
        var profile = Hrm013WorkloadProofV1.requesterIdentity();
        String compact = compact(profile, body);
        var context = new Hrm013RequesterIdentityIngressFilter.RequestContext(
                request, body, Hrm013WorkloadProofV1.headers(profile, compact, CORRELATION),
                new TransportIdentity("dwp-approval-server", leafThumbprint));
        List<String> order = new ArrayList<>();

        var verified = new Hrm013RequesterIdentityProofVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC)).verify(context,
                (selected, selector, keyId) -> {
                    order.add("resolve");
                    assertThat(selector.tenantId()).isEqualTo(17);
                    return new VerifiedSigningKey(signingKey.getPublic(), keyId,
                            "dwp-approval-server");
                }, reservation -> {
                    order.add("reserve");
                    assertThat(reservation.proofIdSha256())
                            .isNotEqualTo(reservation.requestNonceSha256());
                    return true;
                }, (selected, key, claims) -> {
                    order.add("recheck");
                    return true;
                });

        assertThat(order).containsExactly("resolve", "reserve", "recheck");
        assertThat(verified.signingKey().publicKey()).isEqualTo(signingKey.getPublic());
        assertThat(verified.proof().claims().profileClaims())
                .containsEntry("approvalRequestId", APPROVAL)
                .containsEntry("requestedByActorPublicId", ACTOR);
    }

    @Test
    void databaseInfrastructureFailureRemainsUnavailableInsteadOfDenial() throws Exception {
        var verifier = new Hrm013RequesterIdentityProofVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.verify(context(),
                (selected, selector, keyId) -> {
                    throw new DataAccessResourceFailureException("database offline");
                }, reservation -> true, (selected, key, claims) -> true))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void duplicateReplayRemainsAuthorityDenied() throws Exception {
        var verifier = new Hrm013RequesterIdentityProofVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.verify(context(),
                (selected, selector, keyId) -> new VerifiedSigningKey(
                        signingKey.getPublic(), keyId, "dwp-approval-server"),
                reservation -> false, (selected, key, claims) -> true))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
    }

    private Hrm013RequesterIdentityIngressFilter.RequestContext context() throws Exception {
        var request = new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                17, APPROVAL, ACTOR, CORRELATION);
        byte[] body = Hrm013RequesterIdentityAuthorityV1.encodeRequest(mapper, request);
        var profile = Hrm013WorkloadProofV1.requesterIdentity();
        return new Hrm013RequesterIdentityIngressFilter.RequestContext(
                request, body,
                Hrm013WorkloadProofV1.headers(profile, compact(profile, body), CORRELATION),
                new TransportIdentity("dwp-approval-server", leafThumbprint));
    }

    private String compact(Hrm013WorkloadProofV1.Profile profile, byte[] body)
            throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", profile.callerWorkloadId());
        claims.put("aud", profile.ownerWorkloadId());
        claims.put("sub", profile.callerWorkloadId());
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
        claims.put("jti", new UUID(0, 801).toString());
        claims.put("requestNonce", new UUID(0, 802).toString());
        claims.put("bindingId", profile.bindingId());
        claims.put("purpose", profile.purpose());
        claims.put("method", profile.method());
        claims.put("path", profile.exactPath());
        claims.put("tenantId", 17);
        claims.put("correlationId", CORRELATION.toString());
        claims.put("requestBodySha256", Hrm013WorkloadProofV1.sha256(body));
        claims.put("authn", profile.authentication());
        claims.put("cnf", Map.of("x5t#S256", leafThumbprint));
        claims.put("tenantGrantRevision", 31);
        claims.put("authorizationRevision", 47);
        claims.put("grantUseMode", "NEW_WORK");
        claims.put("approvalRequestId", APPROVAL.toString());
        claims.put("requestedByActorPublicId", ACTOR.toString());
        Map<String, Object> jose = new LinkedHashMap<>();
        jose.put("typ", "JWT");
        jose.put("kid", KEY_ID);
        jose.put("alg", "RS256");
        String header = encode(Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                mapper, mapper.writeValueAsBytes(jose)));
        String payload = encode(Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                mapper, mapper.writeValueAsBytes(claims)));
        String input = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encode(signature.sign());
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
