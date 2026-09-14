package com.dwp.services.approval.systemslaauthority;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import static com.dwp.services.approval.systemslaauthority.SystemSlaNotificationProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Transport verification is pre-read admission only, never proof of current recipients or business lease. */
public final class SystemSlaNotificationVerifier {
    private final SystemSlaJson json;
    private final SystemSlaNotificationKeys keys;
    private final Clock clock;
    public SystemSlaNotificationVerifier(SystemSlaJson json, SystemSlaNotificationKeys keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public Verified verify(byte[] bytes, String token) {
        var request = json.parse(bytes); SystemSlaJson.keys(request, REQUEST_FIELDS);
        uuid(request, "eventId"); uuid(request, "requestId"); integer(request, "tenantId", true);
        if (!Set.of("Approval.Quorum.SlaWarning", "Approval.Quorum.SlaBreached").contains(text(request, "eventType", 40))) throw denied();
        for (String field : List.of("originalEnvelopeSha256", "canonicalEnvelopeSha256", "recipientSnapshotSha256", "sourcePinsSha256")) hash(request, field);
        var users = request.get("requestedRecipientUserIds"); if (!users.isArray() || users.isEmpty() || users.size() > 1000) throw denied();
        long previous = 0;
        for (var user : users) {
            if (!user.isIntegralNumber() || !user.canConvertToLong() || user.longValue() <= previous || user.longValue() > 9007199254740991L) throw denied(); previous = user.longValue();
        }
        if (token == null || token.length() > 2048 || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw denied();
        try {
            var parts = token.split("\\."); var header = json.parse(part(parts[0])); SystemSlaJson.keys(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(text(header, "alg", 5)) || !"JWT".equals(text(header, "typ", 3))) throw denied();
            part(parts[2]); var jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(keys.transport(text(header, "kid", 100))))) throw denied();
            var claims = json.parse(part(parts[1])); SystemSlaJson.keys(claims, TRANSPORT_FIELDS);
            if (!TRANSPORT_ISSUER.equals(text(claims, "iss", 100)) || !TRANSPORT_AUDIENCE.equals(text(claims, "aud", 100))
                    || !"dwp-notification-server".equals(text(claims, "sub", 50)) || !TRANSPORT_PURPOSE.equals(text(claims, "purpose", 100))
                    || !"POST".equals(text(claims, "method", 4)) || !PATH.equals(text(claims, "path", 100))
                    || !sha(bytes).equals(hash(claims, "requestBodySha256")) || !hash(request, "sourcePinsSha256").equals(hash(claims, "sourcePinsSha256"))) throw denied();
            UUID jti = uuid(claims, "jti"), nonce = uuid(claims, "requestNonce"); if (jti.equals(nonce)) throw denied();
            long issued = integer(claims, "iat", true), start = integer(claims, "nbf", true), expiry = integer(claims, "exp", true), now = clock.instant().getEpochSecond();
            if (issued != start || issued > now || expiry <= now || expiry <= issued || expiry - issued > 30) throw denied();
            return new Verified(request, jti, nonce, sha(bytes), Instant.ofEpochSecond(expiry));
        } catch (Exception invalid) { throw denied(); }
    }
    private static byte[] part(String raw) {
        var bytes = Base64.getUrlDecoder().decode(raw); if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw denied(); return bytes;
    }
    public static final class Verified {
        private final JsonNode request;
        private final UUID jti, nonce;
        private final String bodySha;
        private final Instant expiresAt;
        private Verified(JsonNode request, UUID jti, UUID nonce, String bodySha, Instant expiresAt) { this.request = request.deepCopy(); this.jti = jti; this.nonce = nonce; this.bodySha = bodySha; this.expiresAt = expiresAt; }
        public JsonNode request() { return request.deepCopy(); }
        public UUID jti() { return jti; }
        public UUID nonce() { return nonce; }
        public String bodySha256() { return bodySha; }
        public Instant expiresAt() { return expiresAt; }
    }
}
