package com.dwp.services.approval.systemslaauthority;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exact joint NF wire profile. This admission proof deliberately makes no owner DB/recipient authority claim. */
class SystemSlaNotificationVerifierTest {
    final Instant now = Instant.parse("2026-09-14T08:00:00Z");
    final SystemSlaJson json = new SystemSlaJson(new ObjectMapper());
    final RSAKey transport = key("notification-sla-recipient-transport:test"), signer = key("approval-sla-recipient-authority:test"), other = key("foreign:runtime");
    SystemSlaNotificationKeys keys() { return new SystemSlaNotificationKeys(json, jwks(transport), signer.toJSONString(), jwks(signer), List.of(other.toPublicJWK())); }
    SystemSlaNotificationVerifier verifier() { return new SystemSlaNotificationVerifier(json, keys(), Clock.fixed(now, ZoneOffset.UTC)); }
    Map<String, Object> request() {
        return new LinkedHashMap<>(Map.of("eventId", UUID.randomUUID().toString(), "eventType", "Approval.Quorum.SlaWarning", "tenantId", 42L,
                "requestId", UUID.randomUUID().toString(), "originalEnvelopeSha256", "a".repeat(64), "canonicalEnvelopeSha256", "b".repeat(64),
                "recipientSnapshotSha256", "c".repeat(64), "sourcePinsSha256", "d".repeat(64), "requestedRecipientUserIds", List.of(100L)));
    }
    Map<String, Object> claims(byte[] bytes, Object sourcePins) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", SystemSlaNotificationProtocol.TRANSPORT_ISSUER); claims.put("aud", SystemSlaNotificationProtocol.TRANSPORT_AUDIENCE); claims.put("sub", "dwp-notification-server");
        claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond()); claims.put("exp", now.plusSeconds(30).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString()); claims.put("purpose", SystemSlaNotificationProtocol.TRANSPORT_PURPOSE); claims.put("method", "POST");
        claims.put("path", SystemSlaNotificationProtocol.PATH); claims.put("requestNonce", UUID.randomUUID().toString()); claims.put("requestBodySha256", SystemSlaJson.sha(bytes)); claims.put("sourcePinsSha256", sourcePins);
        return claims;
    }
    @Test void jointNineFieldRequestAndThirteenClaimsVerifyWithRawBytesAndImmutableAdmissionPins() {
        var input = request(); var bytes = json.bytes(input); var claims = claims(bytes, input.get("sourcePinsSha256")); var token = token(transport, claims);
        assertThat(input).hasSize(9); assertThat(claims).hasSize(13); assertThat(token.length()).isLessThanOrEqualTo(2048);
        var verified = verifier().verify(bytes, token); assertThat(verified.bodySha256()).isEqualTo(SystemSlaJson.sha(bytes));
        assertThat(verified.expiresAt()).isEqualTo(now.plusSeconds(30)); assertThat(verified.nonce().toString()).isEqualTo(claims.get("requestNonce"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) verified.request()).put("tenantId", 99);
        assertThat(verified.request().get("tenantId").longValue()).isEqualTo(42);
    }
    @Test void signedWrongPurposeMethodPathNonceTtlAndExtraClaimsCannotBorrowAuthority() {
        var input = request(); var bytes = json.bytes(input);
        for (var mutation : List.<java.util.function.Consumer<Map<String, Object>>>of(
                value -> value.put("purpose", "APPROVAL_SYSTEM_SLA_SOURCE_V1"), value -> value.put("method", "GET"),
                value -> value.put("path", SystemSlaNotificationProtocol.PATH + "/alias"), value -> value.put("jti", value.get("requestNonce")),
                value -> value.put("exp", now.plusSeconds(31).getEpochSecond()), value -> value.put("exp", now.getEpochSecond()), value -> value.put("actorId", 99))) {
            var claims = claims(bytes, input.get("sourcePinsSha256")); mutation.accept(claims); denied(bytes, token(transport, claims));
        }
    }
    @Test void duplicateFractionExponentUnsafeNullAndInvalidUtf8OrUtf16RequestAreDeniedEvenWithMatchingSignedByteDigest() {
        var input = request(); String raw = new String(json.bytes(input), StandardCharsets.UTF_8);
        for (String numeric : List.of("42.0", "42e0", "9007199254740992", "null")) {
            byte[] bytes = raw.replace("\"tenantId\":42", "\"tenantId\":" + numeric).getBytes(StandardCharsets.UTF_8);
            denied(bytes, token(transport, claims(bytes, input.get("sourcePinsSha256"))));
        }
        for (byte[] bytes : List.of(raw.replace("\"tenantId\":42", "\"tenantId\":42,\"tenantId\":42").getBytes(StandardCharsets.UTF_8), raw.getBytes(StandardCharsets.UTF_16), new byte[]{(byte) 0xc3, 0x28}))
            denied(bytes, token(transport, claims(bytes, input.get("sourcePinsSha256"))));
        byte[] original = json.bytes(input); denied((new String(original, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), token(transport, claims(original, input.get("sourcePinsSha256"))));
    }
    @Test void fullBoundedSortedAudienceIsAcceptedButDuplicatesReorderingEmptyAndOverflowAreNot() {
        var input = request(); input.put("requestedRecipientUserIds", java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toList());
        var bytes = json.bytes(input); assertThat(verifier().verify(bytes, token(transport, claims(bytes, input.get("sourcePinsSha256")))).request().get("requestedRecipientUserIds").size()).isEqualTo(1000);
        for (var users : List.of(List.of(), List.of(100, 100), List.of(101, 100), java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toList())) {
            input.put("requestedRecipientUserIds", users); byte[] invalid = json.bytes(input); denied(invalid, token(transport, claims(invalid, input.get("sourcePinsSha256"))));
        }
    }
    @Test void externalOrTransportResponseKeyMaterialReuseFailsClosedWithoutChangingAnyFamily() {
        var alias = new RSAKey.Builder(other.toPublicJWK()).keyID(transport.getKeyID()).build();
        assertThatThrownBy(() -> new SystemSlaNotificationKeys(json, jwks(alias), signer.toJSONString(), jwks(signer), List.of(other.toPublicJWK()))).isInstanceOf(BaseException.class);
        var reused = new RSAKey.Builder(transport).keyID(signer.getKeyID()).build();
        assertThatThrownBy(() -> new SystemSlaNotificationKeys(json, jwks(transport), reused.toJSONString(), jwks(reused), List.of(other.toPublicJWK()))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new SystemSlaNotificationKeys(json, jwks(transport), signer.toJSONString(), jwks(signer), List.of())).isInstanceOf(BaseException.class);
    }
    void denied(byte[] bytes, String token) { assertThatThrownBy(() -> verifier().verify(bytes, token)).isInstanceOf(BaseException.class); }
    String token(RSAKey key, Map<String, Object> claims) {
        try { var jwt = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(json.bytes(claims))); jwt.sign(new RSASSASigner(key)); return jwt.serialize(); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    static String jwks(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
    static RSAKey key(String id) { try { return new RSAKeyGenerator(2048).keyID(id).algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).generate(); } catch (Exception error) { throw new AssertionError(error); } }
}
