package com.dwp.services.notification.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.HASH;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.JSON;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.digest;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.encode;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dedicated test keys prove the native verifier/client protocol, not a deployed SYSTEM issuer. */
class ApprovalSlaRecipientAuthorityTest {
    static final RSAKey SIGNING = key("approval-sla-recipient-authority:sidecar");
    static final RSAKey TRANSPORT = key("notification-sla-recipient-transport:sidecar");
    static final RSAKey BORROWED = key("different-service:sidecar");
    static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");

    @Test
    void verifiesAll1000FrozenSeatsAndAll10BatchesWithoutTruncation() throws Exception {
        var frame = new Frame(plan(1000), new MutableClock(NOW));
        var verified = frame.verify(frame.claims());
        assertThat(verified.eligibleUserIds()).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, 1000).mapToObj(Long::valueOf).toList());
        for (int chunk = 0; chunk < 10; chunk++)
            verified.requireBatch(frame.plan, frame.plan.chunk(chunk).stream().map(frame.plan::request).toList());
        assertThat(java.util.Arrays.stream(ApprovalSlaRecipientAuthority.Verified.class.getDeclaredConstructors()))
                .allSatisfy(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThatThrownBy(() -> verified.eligibleUserIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void closesAll11AttestationClaimsAgainstOmissionAndExtras() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        assertThat(fields(frame.claims())).hasSize(11);
        for (String name : fields(frame.claims())) {
            var claims = frame.claims(); claims.remove(name); frame.rejects(claims);
        }
        var extra = frame.claims(); extra.put("oldActionNonce", "not-authority"); frame.rejects(extra);
    }

    @Test
    void closesAll13ProfileFieldsAndAll6PerSeatFieldsEvenWithAValidRecomputedHash() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        assertThat(fields(profile(frame.claims()))).hasSize(13);
        for (String name : fields(profile(frame.claims()))) {
            var claims = frame.claims(); profile(claims).remove(name); rehash(claims); frame.rejects(claims);
        }
        var extra = frame.claims(); profile(extra).put("actorId", "borrowed"); rehash(extra); frame.rejects(extra);
        ObjectNode originalSeat = seat(frame.claims(), 0);
        assertThat(fields(originalSeat)).hasSize(6);
        for (String name : fields(originalSeat)) {
            var claims = frame.claims(); seat(claims, 0).remove(name); rehash(claims); frame.rejects(claims);
        }
        extra = frame.claims(); seat(extra, 0).put("tenantId", 42); rehash(extra); frame.rejects(extra);
    }

    @Test
    void rejectsTruncatedReorderedDuplicateReboundAndReasonContradictingSignedSeats() throws Exception {
        var frame = new Frame(plan(3), new MutableClock(NOW));
        for (Consumer<ObjectNode> mutate : List.<Consumer<ObjectNode>>of(
                c -> profile(c).withArray("recipients").remove(2),
                c -> profile(c).withArray("recipients").set(0, seat(c, 1).deepCopy()),
                c -> seat(c, 1).put("userId", 1), c -> seat(c, 0).put("taskId", UUID.randomUUID().toString()),
                c -> seat(c, 0).put("personPublicId", UUID.randomUUID().toString()),
                c -> seat(c, 0).put("taskVersion", 1), c -> seat(c, 0).put("eligible", "true"),
                c -> seat(c, 0).put("reason", "REVOKED"))) {
            var claims = frame.claims(); mutate.accept(claims); rehash(claims); frame.rejects(claims);
        }
    }

    @Test
    void bindsOriginalAndCanonicalBytesAllSourcePinsTenantGenerationLeaseAndRequest() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        for (String name : List.of("originalEnvelopeSha256", "canonicalEnvelopeSha256",
                "recipientSnapshotSha256", "sourcePinsSha256")) {
            var claims = frame.claims(); profile(claims).put(name, "b".repeat(64)); rehash(claims); frame.rejects(claims);
        }
        for (String name : List.of("eventId", "requestId")) {
            var claims = frame.claims(); profile(claims).put(name, UUID.randomUUID().toString()); rehash(claims); frame.rejects(claims);
        }
        for (String name : List.of("generation", "leaseEpoch", "tenantId")) {
            var claims = frame.claims(); profile(claims).put(name, 99); rehash(claims); frame.rejects(claims);
        }
        var claims = frame.claims(); profile(claims).put("eventType", "Approval.Quorum.SlaBreached"); rehash(claims); frame.rejects(claims);
        claims = frame.claims(); claims.put("profileSha256", "b".repeat(64)); frame.rejects(claims);
    }

    @Test
    void rejectsBorrowedKidsThumbprintsPrivateEncryptionWeakAndWrongAlgorithmKeys() throws Exception {
        for (RSAKey bad : List.of(SIGNING, new RSAKey.Builder(SIGNING.toPublicJWK()).keyUse(KeyUse.ENCRYPTION).build(),
                new RSAKey.Builder(SIGNING.toPublicJWK()).algorithm(JWSAlgorithm.RS512).build(),
                new RSAKey.Builder(BORROWED.toPublicJWK()).keyID(SIGNING.getKeyID()).build())) {
            assertThatThrownBy(() -> new ApprovalSlaRecipientAuthority(Map.of(SIGNING.getKeyID(), bad),
                    List.of(BORROWED.toPublicJWK()), Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(IllegalArgumentException.class);
        }
        RSAKey renamedSamePrint = new RSAKey.Builder(SIGNING.toPublicJWK()).keyID("different-service:renamed").build();
        assertThatThrownBy(() -> new ApprovalSlaRecipientAuthority(Map.of(SIGNING.getKeyID(), SIGNING.toPublicJWK()),
                List.of(renamedSamePrint), Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(IllegalArgumentException.class);
        var weakGenerator = java.security.KeyPairGenerator.getInstance("RSA"); weakGenerator.initialize(1024);
        RSAKey weak = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) weakGenerator.generateKeyPair().getPublic())
                .keyID(SIGNING.getKeyID()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        assertThatThrownBy(() -> new ApprovalSlaRecipientAuthority(Map.of(SIGNING.getKeyID(), weak),
                List.of(BORROWED.toPublicJWK()), Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongSignatureExtraHeaderAndNonCanonicalJwtEncoding() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        byte[] wrongSigner = response(frame.claims(), BORROWED);
        assertThatThrownBy(() -> frame.read(wrongSigner)).isInstanceOf(IllegalArgumentException.class);
        var extraHeader = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(SIGNING.getKeyID()).customParam("oldActionProof", "borrowed").build();
        assertThatThrownBy(() -> frame.read(response(frame.claims(), SIGNING, extraHeader))).isInstanceOf(IllegalArgumentException.class);
        String validToken = JSON.readTree(response(frame.claims(), SIGNING)).get("attestation").textValue();
        String[] parts = validToken.split("\\.");
        byte[] padded = JSON.writeValueAsBytes(Map.of("attestation", parts[0] + "=." + parts[1] + "." + parts[2]));
        assertThatThrownBy(() -> frame.read(padded)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsForeignPurposeNonceBodyFutureExpiredAndOverlongLifetime() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        for (Consumer<ObjectNode> mutate : List.<Consumer<ObjectNode>>of(
                c -> c.put("iss", "other-service"), c -> c.put("aud", "old-action"), c -> c.put("purpose", "old-action"),
                c -> c.put("requestNonce", UUID.randomUUID().toString()), c -> c.put("requestBodySha256", "b".repeat(64)),
                c -> c.put("iat", NOW.getEpochSecond() + 1), c -> c.put("nbf", NOW.getEpochSecond() - 1),
                c -> c.put("exp", NOW.getEpochSecond()), c -> c.put("exp", NOW.getEpochSecond() + 31))) {
            var claims = frame.claims(); mutate.accept(claims); frame.rejects(claims);
        }
        byte[] valid = response(frame.claims(), SIGNING);
        assertThatThrownBy(() -> frame.verifier.verify(valid, frame.plan, frame.nonce, frame.bodyHash, NOW.plusSeconds(19)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesStrictUtf8DuplicateFieldsTrailingTokensAndByteBounds() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        String valid = new String(response(frame.claims(), SIGNING), StandardCharsets.UTF_8);
        for (byte[] bytes : List.of((valid + " {}").getBytes(StandardCharsets.UTF_8),
                valid.replaceFirst("\\{", "{\"attestation\":\"duplicate\",").getBytes(StandardCharsets.UTF_8),
                new byte[] {'{', '"', (byte) 0xc3, 0x28, '"', ':', '1', '}'}, new byte[524289], new byte[0])) {
            assertThatThrownBy(() -> frame.read(bytes)).isInstanceOf(IllegalArgumentException.class);
        }
        String duplicateClaims = encode(frame.claims()).replace("\"iss\":", "\"iss\":\"duplicate\",\"iss\":");
        byte[] duplicate = responseRaw(duplicateClaims, SIGNING, standardHeader());
        assertThatThrownBy(() -> frame.read(duplicate)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUtf16LeBomWrapperEvenWhenTheCompleteRs256AttestationIsValid() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        String valid = new String(response(frame.claims(), SIGNING), StandardCharsets.UTF_8);
        byte[] body = valid.getBytes(StandardCharsets.UTF_16LE);
        byte[] bom = new byte[body.length + 2]; bom[0]=(byte) 0xff; bom[1]=(byte) 0xfe;
        System.arraycopy(body, 0, bom, 2, body.length);
        assertThatThrownBy(() -> frame.read(bom)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidUtf8TrailingByteWithoutChangingTheValidRs256Attestation() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        byte[] valid = response(frame.claims(), SIGNING);
        byte[] malformed = java.util.Arrays.copyOf(valid, valid.length + 1);
        malformed[valid.length]=(byte) 0x80;
        assertThatThrownBy(() -> frame.read(malformed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonUtf8JwtHeaderAndClaimsDespiteAnIndependentlyValidRs256Signature() throws Exception {
        var frame = new Frame(plan(1), new MutableClock(NOW));
        byte[] header = standardHeader().toString().getBytes(StandardCharsets.UTF_8);
        byte[] claims = encode(frame.claims()).getBytes(StandardCharsets.UTF_8);
        byte[] malformedClaims = java.util.Arrays.copyOf(claims, claims.length + 1);
        malformedClaims[claims.length] = (byte) 0x80;
        for (byte[][] parts : List.of(new byte[][] {utf16Bom(header), claims},
                new byte[][] {header, utf16Bom(claims)}, new byte[][] {header, malformedClaims})) {
            String input = Base64.getUrlEncoder().withoutPadding().encodeToString(parts[0]) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(parts[1]);
            byte[] signingInput = input.getBytes(StandardCharsets.US_ASCII);
            var signature = new RSASSASigner(SIGNING).sign(standardHeader(), signingInput);
            assertThat(new RSASSAVerifier(SIGNING.toPublicJWK()).verify(standardHeader(), signingInput, signature)).isTrue();
            byte[] wrapper = JSON.writeValueAsBytes(Map.of("attestation", input + "." + signature));
            assertThatThrownBy(() -> frame.read(wrapper)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static byte[] utf16Bom(byte[] utf8) {
        byte[] encoded = new String(utf8, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_16LE);
        byte[] result = new byte[encoded.length + 2]; result[0] = (byte) 0xff; result[1] = (byte) 0xfe;
        System.arraycopy(encoded, 0, result, 2, encoded.length);
        return result;
    }

    @Test
    void verifiedIsPrivateImmutableExpiringAndRequiresSameCurrentProfileNotJustEligibleIds() throws Exception {
        var clock = new MutableClock(NOW);
        var frame = new Frame(plan(2), clock);
        var original = frame.verify(frame.claims());
        clock.advance(1);
        var refreshed = frame.verify(frame.claims());
        assertThat(refreshed.stableDigest()).isEqualTo(original.stableDigest());
        original.requireSameCurrent(refreshed);
        var changed = frame.claims(); profile(changed).put("authorityRevision", "asla-" + "b".repeat(64)); rehash(changed);
        var changedVerified = frame.verify(changed);
        assertThatThrownBy(() -> original.requireSameCurrent(changedVerified)).isInstanceOf(IllegalArgumentException.class);
        var otherPlan = plan(2);
        assertThatThrownBy(() -> original.requireCurrent(otherPlan)).isInstanceOf(IllegalArgumentException.class);
        clock.advance(30);
        assertThatThrownBy(original::eligibleUserIds).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(original::stableDigest).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateBorrowedAndOver100ChildRequestsAtVerifiedBatchBoundary() throws Exception {
        var frame = new Frame(plan(101), new MutableClock(NOW));
        var verified = frame.verify(frame.claims());
        var first = frame.plan.request(frame.plan.recipients().getFirst());
        assertThatThrownBy(() -> verified.requireBatch(frame.plan, List.of(first, first))).isInstanceOf(IllegalArgumentException.class);
        var borrowed = plan(1).request(plan(1).recipients().getFirst());
        assertThatThrownBy(() -> verified.requireBatch(frame.plan, List.of(borrowed))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verified.requireBatch(frame.plan, frame.plan.recipients().stream().map(frame.plan::request).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transportUsesExactly13JwtClaims9InputFieldsFreshNonceAndIndependentRs256Key() throws Exception {
        var plan = plan(1000);
        var issuer = transport(new MutableClock(NOW));
        var first = issuer.issue(plan);
        var second = issuer.issue(plan);
        var jwt = SignedJWT.parse(first.token());
        assertThat(jwt.verify(new RSASSAVerifier(TRANSPORT.toPublicJWK()))).isTrue();
        assertThat(fields(JSON.readTree(Base64.getUrlDecoder().decode(first.token().split("\\.")[1])))).hasSize(13);
        JsonNode body = JSON.readTree(first.body());
        assertThat(fields(body)).containsExactlyInAnyOrder("eventId", "eventType", "tenantId", "requestId",
                "originalEnvelopeSha256", "canonicalEnvelopeSha256", "recipientSnapshotSha256", "sourcePinsSha256", "requestedRecipientUserIds");
        assertThat(body.get("requestedRecipientUserIds")).hasSize(1000);
        assertThat(first.bodySha256()).isEqualTo(digest(new String(first.body(), StandardCharsets.UTF_8)));
        assertThat(jwt.getJWTClaimsSet().getStringClaim("requestNonce")).isEqualTo(first.nonce().toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("requestBodySha256")).isEqualTo(first.bodySha256());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("method")).isEqualTo("POST");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("path")).isEqualTo(ApprovalSlaTransportProof.PATH);
        assertThat(second.nonce()).isNotEqualTo(first.nonce());
        assertThat(second.token()).isNotEqualTo(first.token());
        byte[] copy = first.body(); copy[0] = 0; assertThat(first.body()[0]).isEqualTo((byte) '{');
        assertThatThrownBy(() -> new ApprovalSlaTransportProof(TRANSPORT,
                List.of(new RSAKey.Builder(TRANSPORT.toPublicJWK()).keyID("renamed-service").build()), new MutableClock(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualHttpClientBindsFreshTransportNonceBodyAndRejectsReboundReplies() throws Exception {
        var plan = plan(1000);
        for (String mode : List.of("GOOD", "GOOD_UTF8", "GOOD_UTF8_ALIAS", "WRONG_NONCE", "WRONG_BODY")) {
            try (var server = new SourceServer(plan, new MutableClock(NOW), mode)) {
                if (mode.startsWith("GOOD")) {
                    assertThat(server.client.evaluate(plan).eligibleUserIds()).hasSize(1000);
                    server.client.evaluate(plan);
                    assertThat(server.nonces).hasSize(2).doesNotHaveDuplicates();
                } else assertThatThrownBy(() -> server.client.evaluate(plan)).isInstanceOf(IllegalArgumentException.class);
                server.assertHealthy();
            }
        }
    }

    @Test
    void actualHttpClientNeverFollowsRedirectsAndRejectsNonJsonOrDuplicateContentType() throws Exception {
        var plan = plan(1);
        for (String mode : List.of("REDIRECT", "NON_JSON", "DUPLICATE_CONTENT_TYPE", "NON_UTF8_CHARSET",
                "DUPLICATE_CHARSET", "EXTRA_MEDIA_PARAMETER", "MISSING_CONTENT_TYPE")) {
            try (var server = new SourceServer(plan, new MutableClock(NOW), mode)) {
                assertThatThrownBy(() -> server.client.evaluate(plan)).isInstanceOf(IllegalStateException.class);
                assertThat(server.calls.get()).isEqualTo(1);
                server.assertHealthy();
            }
        }
        assertThatThrownBy(() -> new ApprovalSlaRecipientAuthorityClient(URI.create("http://example.com" + ApprovalSlaTransportProof.PATH),
                transport(new MutableClock(NOW)), verifier(new MutableClock(NOW)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void actualHttpStreamingBodyDeadlineAndByteLimitAbortWithoutAcceptingPartialSource() throws Exception {
        var plan = plan(1);
        for (String mode : List.of("SLOW_STREAM", "OVERSIZED")) {
            try (var server = new SourceServer(plan, new MutableClock(NOW), mode)) {
                long started = System.nanoTime();
                assertThatThrownBy(() -> server.client.evaluate(plan)).isInstanceOf(IllegalStateException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(9));
                assertThat(server.calls.get()).isEqualTo(1);
            }
        }
    }

    static ApprovalSlaRecipientAuthority verifier(Clock clock) {
        return new ApprovalSlaRecipientAuthority(Map.of(SIGNING.getKeyID(), SIGNING.toPublicJWK()),
                List.of(BORROWED.toPublicJWK(), TRANSPORT.toPublicJWK()), clock);
    }
    static ApprovalSlaTransportProof transport(Clock clock) {
        return new ApprovalSlaTransportProof(TRANSPORT, List.of(SIGNING.toPublicJWK(), BORROWED.toPublicJWK()), clock);
    }
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant value) { now = new AtomicReference<>(value); }
        void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this; }
        @Override public Instant instant() { return now.get(); }
    }
    static final class Frame {
        final ApprovalSlaNotificationPlan plan;
        final MutableClock clock;
        final UUID nonce;
        final String bodyHash;
        final ApprovalSlaRecipientAuthority verifier;
        Frame(ApprovalSlaNotificationPlan plan, MutableClock clock) throws Exception {
            this(plan, clock, UUID.randomUUID(), digest("actual-test-request-body"));
        }
        Frame(ApprovalSlaNotificationPlan plan, MutableClock clock, UUID nonce, String bodyHash) {
            this.plan=plan; this.clock=clock; this.nonce=nonce; this.bodyHash=bodyHash; verifier=verifier(clock);
        }
        ObjectNode claims() throws Exception {
            long issued = clock.instant().getEpochSecond(), expires = issued + 20;
            var recipients = plan.recipients().stream().map(seat -> Map.<String,Object>of("userId", seat.userId(),
                    "personPublicId", seat.personPublicId().toString(), "taskId", seat.taskId().toString(),
                    "taskVersion", seat.taskVersion(), "eligible", true, "reason", "ELIGIBLE")).toList();
            Map<String,Object> profile = new TreeMap<>();
            profile.put("eventId", plan.eventId().toString()); profile.put("eventType", plan.eventType());
            profile.put("tenantId", plan.actor().tenantId()); profile.put("requestId", plan.requestId().toString());
            profile.put("originalEnvelopeSha256", plan.originalEnvelopeSha256()); profile.put("canonicalEnvelopeSha256", plan.envelopeSha256());
            profile.put("recipientSnapshotSha256", plan.recipientSnapshotSha256()); profile.put("sourcePinsSha256", plan.sourcePinsSha256());
            profile.put("generation", plan.pins().get("generation")); profile.put("leaseEpoch", plan.pins().get("leaseEpoch"));
            profile.put("authorityRevision", "asla-" + HASH); profile.put("validUntil", expires); profile.put("recipients", recipients);
            Map<String,Object> claims = new TreeMap<>();
            claims.put("iss", ApprovalSlaRecipientAuthority.ISSUER); claims.put("aud", ApprovalSlaRecipientAuthority.AUDIENCE);
            claims.put("purpose", ApprovalSlaRecipientAuthority.PURPOSE); claims.put("iat", issued); claims.put("nbf", issued); claims.put("exp", expires);
            claims.put("jti", UUID.randomUUID().toString()); claims.put("requestNonce", nonce.toString()); claims.put("requestBodySha256", bodyHash);
            claims.put("profile", profile); claims.put("profileSha256", digest(encode(profile))); return JSON.valueToTree(claims);
        }
        ApprovalSlaRecipientAuthority.Verified read(byte[] bytes) { return verifier.verify(bytes, plan, nonce, bodyHash, clock.instant().plusSeconds(30)); }
        ApprovalSlaRecipientAuthority.Verified verify(ObjectNode claims) throws Exception { return read(response(claims, SIGNING)); }
        void rejects(ObjectNode claims) throws Exception {
            byte[] bytes = response(claims, SIGNING); assertThatThrownBy(() -> read(bytes)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    static ObjectNode profile(ObjectNode claims) { return (ObjectNode) claims.get("profile"); }
    static ObjectNode seat(ObjectNode claims, int index) { return (ObjectNode) profile(claims).get("recipients").get(index); }
    static void rehash(ObjectNode claims) throws Exception { claims.put("profileSha256", digest(encode(JSON.convertValue(profile(claims), Object.class)))); }
    static List<String> fields(JsonNode object) { var names = new ArrayList<String>(); object.fieldNames().forEachRemaining(names::add); return names; }
    static byte[] response(ObjectNode claims, RSAKey signer) throws Exception { return response(claims, signer, standardHeader()); }
    private static JWSHeader standardHeader() { return new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(SIGNING.getKeyID()).build(); }
    private static byte[] response(ObjectNode claims, RSAKey signer, JWSHeader header) throws Exception { return responseRaw(encode(claims), signer, header); }
    private static byte[] responseRaw(String claims, RSAKey signer, JWSHeader header) throws Exception {
        var signed = new JWSObject(header, new Payload(claims)); signed.sign(new RSASSASigner(signer));
        return JSON.writeValueAsBytes(Map.of("attestation", signed.serialize()));
    }
    private static RSAKey key(String kid) {
        try { return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    static final class SourceServer implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicInteger calls = new AtomicInteger();
        final List<UUID> nonces = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ApprovalSlaRecipientAuthorityClient client;
        SourceServer(ApprovalSlaNotificationPlan plan, MutableClock clock, String mode) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext(ApprovalSlaTransportProof.PATH, exchange -> {
                int call = calls.incrementAndGet();
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    assertThat(exchange.getRequestHeaders().getFirst("X-DWP-Service-Identity")).isEqualTo("dwp-notification-server");
                    var jwt = SignedJWT.parse(exchange.getRequestHeaders().getFirst(ApprovalSlaTransportProof.HEADER));
                    assertThat(jwt.verify(new RSASSAVerifier(TRANSPORT.toPublicJWK()))).isTrue();
                    var transportClaims = jwt.getJWTClaimsSet();
                    assertThat(transportClaims.getStringClaim("requestBodySha256")).isEqualTo(digest(new String(body, StandardCharsets.UTF_8)));
                    JsonNode input = JSON.readTree(body); assertThat(fields(input)).hasSize(9);
                    assertThat(input.get("requestedRecipientUserIds")).hasSize(plan.recipients().size());
                    UUID nonce = UUID.fromString(transportClaims.getStringClaim("requestNonce")); nonces.add(nonce);
                    if (mode.equals("REDIRECT")) { exchange.getResponseHeaders().set("Location", "/must-not-follow"); exchange.sendResponseHeaders(302, -1); return; }
                    if (mode.equals("DENIED") || mode.equals("POST_503") && call == 2) { exchange.sendResponseHeaders(mode.equals("DENIED") ? 403 : 503, -1); return; }
                    if (mode.equals("SLOW_STREAM")) {
                        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); Thread.sleep(6500); return;
                    }
                    var frame = new Frame(plan, clock, mode.equals("WRONG_NONCE") ? UUID.randomUUID() : nonce,
                            mode.equals("WRONG_BODY") ? digest("different body") : transportClaims.getStringClaim("requestBodySha256"));
                    var claims = frame.claims();
                    if (mode.equals("POST_CHANGED") && call == 2) { seat(claims, 0).put("eligible", false).put("reason", "REVOKED"); rehash(claims); }
                    if (mode.equals("POST_EXPIRED") && call == 2) clock.advance(31);
                    byte[] result = mode.equals("OVERSIZED") ? new byte[524289] : response(claims, SIGNING);
                    if (!mode.equals("MISSING_CONTENT_TYPE")) exchange.getResponseHeaders().add("Content-Type",
                            switch (mode) {
                                case "NON_JSON" -> "text/plain";
                                case "NON_UTF8_CHARSET" -> "application/json;charset=UTF-16";
                                case "GOOD_UTF8" -> "application/json;charset=UTF-8";
                                case "GOOD_UTF8_ALIAS" -> "application/json;charset=utf8";
                                case "DUPLICATE_CHARSET" -> "application/json;charset=UTF-8;charset=UTF-8";
                                case "EXTRA_MEDIA_PARAMETER" -> "application/json;version=1";
                                default -> "application/json";
                            });
                    if (mode.equals("DUPLICATE_CONTENT_TYPE")) exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, result.length); exchange.getResponseBody().write(result);
                } catch (Throwable error) {
                    if (!(error instanceof InterruptedException) && !(error instanceof java.io.IOException)) failure.compareAndSet(null, error);
                    try { exchange.sendResponseHeaders(500, -1); } catch (java.io.IOException ignored) { }
                } finally { exchange.close(); }
            });
            server.createContext("/must-not-follow", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
            server.start();
            client = new ApprovalSlaRecipientAuthorityClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + ApprovalSlaTransportProof.PATH), transport(clock), verifier(clock));
        }
        void assertHealthy() { assertThat(failure.get()).as("Real HTTP server protocol assertions").isNull(); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
