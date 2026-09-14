package com.dwp.services.approval.systemslaauthority;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import static com.dwp.services.approval.systemslaauthority.SystemSlaSourceProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.*;
import java.util.*;

/** Only this crypto verifier can construct a current Auth response; caller-selected seats are never authority. */
public final class SystemSlaSourceAttestationVerifier {
    private final SystemSlaJson json;
    private final SystemSlaSourceKeys keys;
    private final Clock clock;
    public SystemSlaSourceAttestationVerifier(SystemSlaJson json, SystemSlaSourceKeys keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public Verified verify(byte[] raw, SystemSlaSourceProofIssuer.Exchange exchange) {
        if (exchange == null) throw denied(); var response = json.parse(raw); SystemSlaJson.keys(response, Set.of("sourceAttestation"));
        String token = text(response, "sourceAttestation", BODY_LIMIT);
        if (!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw denied();
        try {
            var parts = token.split("\\."); var header = json.parse(part(parts[0])); SystemSlaJson.keys(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(text(header, "alg", 5)) || !"JWT".equals(text(header, "typ", 3))) throw denied();
            part(parts[2]); var jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(keys.attestation(text(header, "kid", 80))))) throw denied();
            var claims = json.parse(part(parts[1])); SystemSlaJson.keys(claims, ATTESTATION_FIELDS);
            if (!ATTESTATION_ISSUER.equals(text(claims, "iss", 100)) || !ATTESTATION_AUDIENCE.equals(text(claims, "aud", 100))
                    || !ATTESTATION_PURPOSE.equals(text(claims, "purpose", 100)) || !"dwp-approval-server".equals(text(claims, "sub", 50))
                    || !exchange.ownerId().equals(uuid(claims, "sourceProofJti").toString()) || !exchange.transportId().equals(uuid(claims, "transportProofJti").toString())
                    || !sha(exchange.body()).equals(hash(claims, "bodySha256")) || !exchange.bindingHash().equals(hash(claims, "bindingsSha256"))
                    || !hash(exchange.seal().bindings(), "sourceDigest").equals(hash(claims, "sourceDigest"))) throw denied();
            UUID id = uuid(claims, "jti"); if (id.toString().equals(exchange.ownerId()) || id.toString().equals(exchange.transportId())) throw denied();
            long issued = integer(claims, "iat", true), start = integer(claims, "nbf", true), expiry = integer(claims, "exp", true), now = clock.instant().getEpochSecond();
            if (issued != start || issued > now || expiry <= now || expiry <= issued || expiry - issued > 30 || expiry > exchange.expiresAt().getEpochSecond()) throw denied();
            var authority = claims.get("authority"); SystemSlaJson.keys(authority, Set.of("authorityRevision", "sourceVectorSha256", "evaluatedAt", "expiresAt"));
            String vector = hash(authority, "sourceVectorSha256"), revision = text(authority, "authorityRevision", 69);
            Instant evaluated = Instant.parse(text(authority, "evaluatedAt", 40)), expires = Instant.parse(text(authority, "expiresAt", 40));
            if (!revision.equals("asla-" + vector) || !expires.equals(Instant.ofEpochSecond(expiry)) || evaluated.isAfter(clock.instant())
                    || evaluated.isBefore(exchange.ownerIssuedAt()) || !evaluated.isBefore(expires)) throw denied();
            var actual = claims.get("recipients"); var expected = exchange.seal().bindings().get("audience");
            if (!actual.isArray() || actual.size() != expected.size() || actual.isEmpty() || actual.size() > 1000) throw denied();
            var requester = exchange.seal().bindings().at("/source/request"); long previous = 0;
            for (int index = 0; index < actual.size(); index++) {
                var seat = actual.get(index); var original = expected.get(index);
                SystemSlaJson.keys(seat, Set.of("userId", "personPublicId", "taskId", "taskVersion", "eligible", "reason", "expiresAt"));
                long user = integer(seat, "userId", true);
                if (user <= previous || user != integer(original, "userId", true) || !uuid(seat, "personPublicId").equals(uuid(original, "personPublicId"))
                        || !uuid(seat, "taskId").equals(uuid(original, "taskId")) || integer(seat, "taskVersion", false) != integer(original, "taskVersion", false)
                        || !seat.get("eligible").isBoolean()) throw denied(); previous = user;
                String reason = text(seat, "reason", 40); Instant seatExpiry = Instant.parse(text(seat, "expiresAt", 40));
                if (!REASONS.contains(reason) || seat.get("eligible").booleanValue() != "ELIGIBLE".equals(reason) || seatExpiry.isBefore(expires)
                        || seatExpiry.isAfter(exchange.expiresAt()) || seat.get("eligible").booleanValue() && (user == integer(requester, "requesterUserId", true)
                            || uuid(seat, "personPublicId").equals(uuid(requester, "requesterPersonPublicId")))) throw denied();
            }
            return new Verified(exchange, actual, revision, expires, token, id);
        } catch (Exception invalid) { throw denied(); }
    }
    private static byte[] part(String raw) {
        byte[] bytes = Base64.getUrlDecoder().decode(raw); if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw denied(); return bytes;
    }
    public static final class Verified {
        private final SystemSlaSourceProofIssuer.Exchange exchange;
        private final JsonNode recipients;
        private final String revision;
        private final Instant expiresAt;
        private final String originalAttestation;
        private final UUID attestationId;
        private final java.util.concurrent.atomic.AtomicReference<UUID> originalEvent = new java.util.concurrent.atomic.AtomicReference<>();
        private Verified(SystemSlaSourceProofIssuer.Exchange exchange, JsonNode recipients, String revision, Instant expiry, String token, UUID id) {
            this.exchange = exchange; this.recipients = recipients.deepCopy(); this.revision = revision; expiresAt = expiry;
            originalAttestation = token; attestationId = id;
        }
        void consumeOrigin(UUID event, Instant now) {
            if (event == null || exchange.seal().delivery() || !expiresAt.isAfter(now) || !originalEvent.compareAndSet(null, event)) throw denied();
        }
        String originalAttestation() { return originalAttestation; }
        UUID attestationId() { return attestationId; }
        public SystemSlaSourceProofIssuer.Exchange exchange() { return exchange; }
        public JsonNode recipients() { return recipients.deepCopy(); }
        public String authorityRevision() { return revision; }
        public Instant expiresAt() { return expiresAt; }
    }
}
