package com.dwp.services.time.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.Header;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.VerifiedSigningKey;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.WorkloadAuthentication;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.services.time.home.TimeHomeSecurityPorts.ReplayReservations;
import com.dwp.services.time.home.TimeHomeSecurityPorts.SigningKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TimeHomeAdmissionVerifierTest {

    private static KeyPair keyPair;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger sequence = new AtomicInteger(100);

    @BeforeAll
    static void keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void admitsExactPlatformProofOnlyAfterCurrentAuthorityReevaluation() throws Exception {
        String path = path();
        var verifier = verifier(new ReplayLedger(),
                (claims, checkedAt) -> java.util.Optional.of(
                        TimeHomeTestFixtures.selfAuthority()));

        var result = verifier.verify(path, proof(path, ignored -> { }));

        assertThat(result.admission().tenantId()).isEqualTo(TimeHomeTestFixtures.TENANT);
        assertThat(result.currentAuthority()).isEqualTo(
                TimeHomeTestFixtures.selfAuthority());
    }

    @Test
    void rejectsCurrentRevisionDriftBrowserHeadersAndWrongPath() throws Exception {
        String path = path();
        var drifted = new TimeHomeCurrentAuthorityPort.CurrentAuthority(
                TimeHomeTestFixtures.TENANT,
                TimeHomeTestFixtures.SUBJECT,
                "hcm.personal.time.view",
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                4,
                "a".repeat(64),
                7,
                8,
                "time-summary",
                HrisHomeContributionMaterializationV1.BrowserAudience.SELF,
                List.of(TimeHomeTestFixtures.WORKER),
                TimeHomeTestFixtures.all(
                        HrisHomeContributionMaterializationV1.Decision.VIEW));
        var verifier = verifier(new ReplayLedger(),
                (claims, checkedAt) -> java.util.Optional.of(drifted));

        assertThatThrownBy(() -> verifier.verify(path, proof(path, ignored -> { })))
                .isInstanceOfSatisfying(
                        HomeMaterializationWorkloadProofV1.AdmissionDenied.class,
                        denied -> assertThat(denied.reason())
                                .isEqualTo("CURRENT_HOME_AUTHORITY_REQUIRED"));

        List<Header> browser = new ArrayList<>(proof(path, ignored -> { }));
        browser.add(new Header("Authorization", "Bearer browser"));
        assertThatThrownBy(() -> verifier(new ReplayLedger(), current())
                .verify(path, browser))
                .isInstanceOf(HomeMaterializationWorkloadProofV1.AdmissionDenied.class);

        assertThatThrownBy(() -> verifier(new ReplayLedger(), current())
                .verify(path + "/extra", proof(path, ignored -> { })))
                .isInstanceOf(HomeMaterializationWorkloadProofV1.AdmissionDenied.class);
    }

    @Test
    void reservesJtiAndNonceAndRejectsReplayOrStaleProof() throws Exception {
        String path = path();
        ReplayLedger replay = new ReplayLedger();
        var verifier = verifier(replay, current());
        List<Header> headers = proof(path, ignored -> { });

        verifier.verify(path, headers);
        assertThatThrownBy(() -> verifier.verify(path, headers))
                .isInstanceOfSatisfying(
                        HomeMaterializationWorkloadProofV1.AdmissionDenied.class,
                        denied -> assertThat(denied.reason())
                                .isEqualTo("HOME_WORKLOAD_PROOF_REPLAYED"));

        List<Header> stale = proof(path, claims -> {
            claims.put("iat", TimeHomeTestFixtures.NOW.minusSeconds(60).getEpochSecond());
            claims.put("nbf", TimeHomeTestFixtures.NOW.minusSeconds(60).getEpochSecond());
            claims.put("exp", TimeHomeTestFixtures.NOW.minusSeconds(30).getEpochSecond());
        });
        assertThatThrownBy(() -> verifier(new ReplayLedger(), current()).verify(path, stale))
                .isInstanceOf(HomeMaterializationWorkloadProofV1.AdmissionDenied.class);
    }

    private TimeHomeAdmissionVerifier verifier(
            ReplayReservations replays, TimeHomeCurrentAuthorityPort authority) {
        SigningKeys keys = keyId -> new VerifiedSigningKey(
                keyPair.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT,
                HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        return new TimeHomeAdmissionVerifier(
                mapper,
                Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                keys,
                replays,
                authority);
    }

    private TimeHomeCurrentAuthorityPort current() {
        return (claims, checkedAt) -> java.util.Optional.of(
                TimeHomeTestFixtures.selfAuthority());
    }

    private List<Header> proof(String path, Consumer<ObjectNode> change) throws Exception {
        int value = sequence.incrementAndGet();
        ObjectNode jose = mapper.createObjectNode();
        jose.put("alg", "RS256");
        jose.put("typ", "JWT");
        jose.put("kid", "tim-home-rsa-1");

        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        claims.put("aud", "dwp-time-server");
        claims.put("sub", HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        claims.put("iat", TimeHomeTestFixtures.NOW.minusSeconds(1).getEpochSecond());
        claims.put("nbf", TimeHomeTestFixtures.NOW.minusSeconds(1).getEpochSecond());
        claims.put("exp", TimeHomeTestFixtures.NOW.plusSeconds(20).getEpochSecond());
        claims.put("jti", TimeHomeTestFixtures.uuid(1000 + value).toString());
        claims.put("requestNonce", TimeHomeTestFixtures.uuid(2000 + value).toString());
        claims.put("purpose", HrisHomeContributionMaterializationV1.PURPOSE);
        claims.put("method", "GET");
        claims.put("path", path);
        claims.put("tenantId", TimeHomeTestFixtures.TENANT);
        claims.put("subjectPrincipalPublicId", TimeHomeTestFixtures.SUBJECT.toString());
        claims.put("correlationId", TimeHomeTestFixtures.uuid(702).toString());
        claims.put("capability", "hcm.personal.time.view");
        claims.put("ownerPopulationScope", "SELF");
        claims.put("scopeRevision", 4);
        claims.put("populationScopeDigest", "a".repeat(64));
        claims.put("effectivePermissionRevision", 6);
        claims.put("fieldPolicyRevision", 8);
        claims.put("widgetKey", "time-summary");
        claims.put("browserAudience", "SELF");
        claims.put("authn", "CLIENT_CREDENTIAL_JWT");
        change.accept(claims);

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(mapper.writeValueAsBytes(jose));
        String payload = encoder.encodeToString(mapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return HomeMaterializationWorkloadProofV1.headers(
                signingInput + "." + encoder.encodeToString(signature.sign()));
    }

    private static String path() {
        return HomeMaterializationWorkloadProofV1.Endpoint.TIME_LEAVE
                .path("href_0123456789abcdef");
    }

    private static final class ReplayLedger implements ReplayReservations {
        private final Set<String> reservations = new HashSet<>();

        @Override
        public boolean reserve(UUID proofId, UUID nonce, long tenantId, Instant expiresAt) {
            return reservations.add(tenantId + ":" + proofId + ":" + nonce);
        }
    }
}
