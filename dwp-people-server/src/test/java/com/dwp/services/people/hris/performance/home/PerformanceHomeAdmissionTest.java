package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.dwp.services.people.hris.performance.home.PerformanceHomeTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceHomeAdmissionTest {

    private static final String REF = "href_0123456789abcdef";
    private static KeyPair keyPair;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger sequence = new AtomicInteger(100);

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void admitsExactSignedContextAfterCurrentOwnerReevaluationWithoutContentType()
            throws Exception {
        AtomicInteger reevaluations = new AtomicInteger();
        Replay replay = new Replay();
        PerformanceHomeAdmission admission = admission(claims -> {
            reevaluations.incrementAndGet();
            return PerformanceHomeTestFixtures.authority();
        }, replay);
        String path = path();
        List<HomeMaterializationWorkloadProofV1.Header> headers = proof(ignored -> { });

        assertThat(headers).noneMatch(header -> header.name().equalsIgnoreCase("Content-Type"));
        PerformanceHomeAdmission.Admission result = admission.admit(path, headers);

        assertThat(result.workload().tenantId()).isEqualTo(7);
        assertThat(result.authority()).isEqualTo(PerformanceHomeTestFixtures.authority());
        assertThat(reevaluations).hasValue(1);
        assertThat(replay.reservations).hasSize(1);
    }

    @Test
    void rejectsCurrentPermissionOrFieldPolicyDriftAndForgedTenant() throws Exception {
        var drifted = PerformanceHomeTestFixtures.authority(
                7, 4, 6, 9, PerformanceHomeTestFixtures.authority().fieldDecisions());
        PerformanceHomeAdmission driftAdmission = admission(claims -> drifted, new Replay());
        assertDenied("CURRENT_HOME_AUTHORITY_REQUIRED", () ->
                driftAdmission.admit(path(), proof(ignored -> { })));

        PerformanceHomeAdmission tenantAdmission = admission(
                claims -> PerformanceHomeTestFixtures.authority(), new Replay());
        assertDenied("CURRENT_HOME_AUTHORITY_REQUIRED", () ->
                tenantAdmission.admit(path(), proof(claims -> claims.put("tenantId", 8))));
    }

    @Test
    void rejectsReplayQueryPathAndUnknownOrTenantHeaders() throws Exception {
        Replay replay = new Replay();
        PerformanceHomeAdmission admission = admission(
                claims -> PerformanceHomeTestFixtures.authority(), replay);
        List<HomeMaterializationWorkloadProofV1.Header> headers = proof(ignored -> { });
        admission.admit(path(), headers);
        assertDenied("HOME_WORKLOAD_PROOF_REPLAYED", () -> admission.admit(path(), headers));

        assertDenied("EXACT_HOME_MATERIALIZATION_GET_PATH_REQUIRED", () ->
                admission(claims -> PerformanceHomeTestFixtures.authority(), new Replay())
                        .admit(path() + "?tenantId=7", proof(ignored -> { })));

        List<HomeMaterializationWorkloadProofV1.Header> unknown =
                new ArrayList<>(proof(ignored -> { }));
        unknown.add(new HomeMaterializationWorkloadProofV1.Header(
                "X-DWP-Field-Policy", "VIEW"));
        assertDenied("UNRECOGNIZED_DWP_HEADER_FORBIDDEN", () ->
                admission(claims -> PerformanceHomeTestFixtures.authority(), new Replay())
                        .admit(path(), unknown));

        List<HomeMaterializationWorkloadProofV1.Header> tenant =
                new ArrayList<>(proof(ignored -> { }));
        tenant.add(new HomeMaterializationWorkloadProofV1.Header("X-DWP-Tenant-ID", "7"));
        assertDenied("BROWSER_END_USER_OR_TENANT_HEADER_FORBIDDEN", () ->
                admission(claims -> PerformanceHomeTestFixtures.authority(), new Replay())
                        .admit(path(), tenant));
    }

    private PerformanceHomeAdmission admission(
            PerformanceHomeAuthorityPort authority,
            HomeMaterializationWorkloadProofV1.ReplayStore replay) {
        return new PerformanceHomeAdmission(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                keyId -> new HomeMaterializationWorkloadProofV1.VerifiedSigningKey(
                        keyPair.getPublic(),
                        HomeMaterializationWorkloadProofV1.WorkloadAuthentication
                                .CLIENT_CREDENTIAL_JWT,
                        HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID),
                replay, authority);
    }

    private List<HomeMaterializationWorkloadProofV1.Header> proof(
            Consumer<ObjectNode> change) throws Exception {
        int value = sequence.incrementAndGet();
        ObjectNode jose = mapper.createObjectNode();
        jose.put("alg", "RS256");
        jose.put("typ", "JWT");
        jose.put("kid", "per-home-rsa-1");
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        claims.put("aud", "dwp-people-server");
        claims.put("sub", HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        claims.put("iat", NOW.minusSeconds(1).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(1).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(20).getEpochSecond());
        claims.put("jti", uuid(1000 + value).toString());
        claims.put("requestNonce", uuid(2000 + value).toString());
        claims.put("purpose", HomeMaterializationWorkloadProofV1.PURPOSE);
        claims.put("method", "GET");
        claims.put("path", path());
        claims.put("tenantId", 7);
        claims.put("subjectPrincipalPublicId", PerformanceHomeTestFixtures.SUBJECT.toString());
        claims.put("correlationId", uuid(701).toString());
        claims.put("capability", PerformanceHomeAuthorityPort.CAPABILITY);
        claims.put("ownerPopulationScope", "SELF");
        claims.put("scopeRevision", 4);
        claims.put("populationScopeDigest", "a".repeat(64));
        claims.put("effectivePermissionRevision", 6);
        claims.put("fieldPolicyRevision", 8);
        claims.put("widgetKey", "performance-summary");
        claims.put("browserAudience", "SELF");
        claims.put("authn", "CLIENT_CREDENTIAL_JWT");
        change.accept(claims);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(mapper.writeValueAsBytes(jose));
        String payload = encoder.encodeToString(mapper.writeValueAsBytes(claims));
        String input = header + '.' + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return HomeMaterializationWorkloadProofV1.headers(
                input + '.' + encoder.encodeToString(signature.sign()));
    }

    private String path() {
        return HomeMaterializationWorkloadProofV1.Endpoint.PERFORMANCE.path(REF);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static void assertDenied(String reason, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        HomeMaterializationWorkloadProofV1.AdmissionDenied.class,
                        denied -> assertThat(denied.reason()).isEqualTo(reason));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class Replay
            implements HomeMaterializationWorkloadProofV1.ReplayStore {
        private final Set<String> reservations = new HashSet<>();

        @Override
        public boolean reserve(UUID proofId, UUID nonce, long tenantId, java.time.Instant expiry) {
            return reservations.add(tenantId + ":" + proofId + ":" + nonce);
        }
    }
}
