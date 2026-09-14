package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Event-bound current authority. Neither a Kafka snapshot nor a producer proof can construct Verified. */
public final class ApprovalSlaRecipientAuthority {
    public static final String ISSUER = "dwp-approval-system-sla-notification";
    public static final String AUDIENCE = "dwp-notification-system-sla-source";
    public static final String PURPOSE = "APPROVAL_SYSTEM_SLA_NOTIFICATION_ATTESTATION_V1";
    private static final Set<String> CLAIMS = Set.of("iss","aud","purpose","iat","nbf","exp","jti",
            "requestNonce","requestBodySha256","profileSha256","profile");
    private static final Set<String> PROFILE = Set.of("eventId","eventType","tenantId","requestId",
            "originalEnvelopeSha256","canonicalEnvelopeSha256","recipientSnapshotSha256","sourcePinsSha256",
            "generation","leaseEpoch","authorityRevision","validUntil","recipients");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, RSAKey> keys;
    private final Clock clock;

    public ApprovalSlaRecipientAuthority(Map<String, RSAKey> trusted, List<RSAKey> prohibited, Clock clock) {
        if (trusted == null || trusted.isEmpty() || trusted.size() > 8 || prohibited == null || prohibited.isEmpty()
                || clock == null) throw invalid();
        Set<String> ids = new HashSet<>(), prints = new HashSet<>();
        try {
            for (RSAKey key : prohibited) {
                if (key == null) throw invalid();
                if (key.getKeyID() != null) ids.add(key.getKeyID());
                prints.add(key.computeThumbprint().toString());
            }
            for (var entry : trusted.entrySet()) {
                RSAKey key = entry.getValue();
                if (key == null || key.isPrivate() || key.size() < 2048 || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                        || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || !entry.getKey().equals(key.getKeyID())
                        || !entry.getKey().matches("approval-sla-recipient-authority:[A-Za-z0-9._-]{1,48}")
                        || !ids.add(entry.getKey()) || !prints.add(key.computeThumbprint().toString())) throw invalid();
            }
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw invalid(); }
        this.keys = Map.copyOf(trusted); this.clock = clock;
    }

    public Verified verify(byte[] response, ApprovalSlaNotificationPlan plan, UUID nonce,
                           String requestBodySha256, Instant requestExpiresAt) {
        if (plan == null || nonce == null || requestExpiresAt == null) throw invalid();
        hash(requestBodySha256);
        JsonNode envelope = parse(response, 524288); exact(envelope, Set.of("attestation"));
        String token = text(envelope, "attestation", 524000);
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw invalid();
        JsonNode header = parse(part(parts[0]), 2048); exact(header, Set.of("alg","typ","kid"));
        if (!"RS256".equals(text(header,"alg",5)) || !"JWT".equals(text(header,"typ",3))) throw invalid();
        RSAKey key = keys.get(text(header,"kid",80)); part(parts[2]);
        try {
            if (key == null || !SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw invalid();
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw invalid(); }
        JsonNode claims = parse(part(parts[1]), 390000); exact(claims, CLAIMS);
        if (!ISSUER.equals(text(claims,"iss",100)) || !AUDIENCE.equals(text(claims,"aud",100))
                || !PURPOSE.equals(text(claims,"purpose",100)) || !nonce.equals(uuid(claims,"requestNonce"))
                || !requestBodySha256.equals(hash(text(claims,"requestBodySha256",64)))) throw invalid();
        uuid(claims,"jti");
        long issued = number(claims,"iat",1), starts = number(claims,"nbf",1), expires = number(claims,"exp",1);
        long now = clock.instant().getEpochSecond();
        if (issued > now || starts != issued || expires <= now || expires <= issued || expires-issued > 30
                || expires > requestExpiresAt.getEpochSecond()) throw invalid();
        JsonNode profile = claims.get("profile"); exact(profile, PROFILE);
        if (!text(profile,"eventId",36).equals(plan.eventId().toString())
                || !text(profile,"eventType",200).equals(plan.eventType())
                || number(profile,"tenantId",1) != plan.actor().tenantId()
                || !text(profile,"requestId",36).equals(plan.requestId().toString())
                || !hash(text(profile,"originalEnvelopeSha256",64)).equals(plan.originalEnvelopeSha256())
                || !hash(text(profile,"canonicalEnvelopeSha256",64)).equals(plan.envelopeSha256())
                || !hash(text(profile,"recipientSnapshotSha256",64)).equals(plan.recipientSnapshotSha256())
                || !hash(text(profile,"sourcePinsSha256",64)).equals(plan.sourcePinsSha256())
                || number(profile,"generation",1) != (Long) plan.pins().get("generation")
                || number(profile,"leaseEpoch",1) != (Long) plan.pins().get("leaseEpoch")
                || number(profile,"validUntil",1) != expires
                || !text(profile,"authorityRevision",69).matches("asla-[a-f0-9]{64}")) throw invalid();
        if (!hash(text(claims,"profileSha256",64)).equals(
                ApprovalSlaNotificationContract.sha256(canonical(profile)))) throw invalid();
        JsonNode seats = profile.get("recipients");
        if (seats == null || !seats.isArray() || seats.size() != plan.recipients().size() || seats.size() > 1000)
            throw invalid();
        Set<Long> eligible = new HashSet<>();
        for (int index = 0; index < seats.size(); index++) {
            var seat = seats.get(index); var original = plan.recipients().get(index);
            exact(seat, Set.of("userId","personPublicId","taskId","taskVersion","eligible","reason"));
            if (number(seat,"userId",1) != original.userId()
                    || !uuid(seat,"personPublicId").equals(original.personPublicId())
                    || !uuid(seat,"taskId").equals(original.taskId())
                    || number(seat,"taskVersion",0) != original.taskVersion()
                    || !seat.get("eligible").isBoolean()) throw invalid();
            String reason = text(seat,"reason",100);
            if (!reason.matches("[A-Z][A-Z0-9_]{0,99}")
                    || seat.get("eligible").booleanValue() != "ELIGIBLE".equals(reason)) throw invalid();
            if (seat.get("eligible").booleanValue()) eligible.add(original.userId());
        }
        var stable = profile.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) stable).remove("validUntil");
        return new Verified(plan, eligible, Instant.ofEpochSecond(expires),
                ApprovalSlaNotificationContract.sha256(canonical(stable)), clock);
    }

    public static final class Verified {
        private final ApprovalSlaNotificationPlan plan;
        private final Set<Long> eligible;
        private final Instant expires;
        private final String stableDigest;
        private final Clock clock;
        private Verified(ApprovalSlaNotificationPlan plan, Set<Long> eligible, Instant expires,
                         String stableDigest, Clock clock) {
            this.plan=plan; this.eligible=Set.copyOf(eligible); this.expires=expires;
            this.stableDigest=stableDigest; this.clock=clock;
        }
        public Set<Long> eligibleUserIds() { requireCurrent(plan); return eligible; }
        public String stableDigest() { requireCurrent(plan); return stableDigest; }
        public Instant expiresAt() { return expires; }
        public void requireCurrent(ApprovalSlaNotificationPlan expected) {
            if (!plan.equals(expected) || !expires.isAfter(clock.instant())) throw invalid();
        }
        public void requireSameCurrent(Verified other) {
            requireCurrent(plan);
            if (other == null) throw invalid();
            other.requireCurrent(plan);
            if (!stableDigest.equals(other.stableDigest) || !eligible.equals(other.eligible)) throw invalid();
        }
        public void requireBatch(ApprovalSlaNotificationPlan expected, List<DirectMaterializationRequest> requests) {
            requireCurrent(expected);
            if (requests == null || requests.isEmpty() || requests.size() > 100) throw invalid();
            Set<UUID> seen = new HashSet<>();
            for (var request : requests) {
                if (request == null || request.recipientUserIds().size() != 1) throw invalid();
                long user = request.recipientUserIds().getFirst();
                var seat = plan.recipients().stream().filter(value -> value.userId() == user).findFirst().orElseThrow(ApprovalSlaRecipientAuthority::invalid);
                if (!eligible.contains(user) || !plan.request(seat).equals(request) || !seen.add(request.sourceEventId())) throw invalid();
            }
        }
    }

    private static JsonNode parse(byte[] bytes, int maximum) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximum) throw invalid();
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return JSON.readTree(utf8);
        } catch (Exception error) { throw invalid(); }
    }
    private static byte[] part(String encoded) {
        if (!encoded.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) throw invalid();
            return bytes;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    private static void exact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!expected.equals(actual)) throw invalid();
    }
    private static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        String text = value.textValue();
        if (text.isBlank() || text.length() > maximum || !text.equals(text.trim())
                || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        return text;
    }
    private static long number(JsonNode node, String field, long minimum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < minimum || value.longValue() > 9007199254740991L) throw invalid();
        return value.longValue();
    }
    private static UUID uuid(JsonNode node, String field) {
        String raw = text(node,field,36);
        try { UUID id = UUID.fromString(raw); if (id.toString().equals(raw)) return id; }
        catch (IllegalArgumentException error) { throw invalid(); }
        throw invalid();
    }
    private static String hash(String raw) { if (!raw.matches("[a-f0-9]{64}")) throw invalid(); return raw; }
    private static String canonical(JsonNode node) {
        return ApprovalSlaNotificationContract.canonical(value(node));
    }
    private static Object value(JsonNode node) {
        if (node.isObject()) {
            Map<String,Object> result = new TreeMap<>(); node.properties().forEach(entry -> result.put(entry.getKey(),value(entry.getValue()))); return result;
        }
        if (node.isArray()) { List<Object> result = new ArrayList<>(); node.forEach(item -> result.add(value(item))); return result; }
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber() && node.canConvertToLong()) return node.longValue();
        if (node.isNull()) return null;
        throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Current Approval SLA authority could not be verified."); }
}
