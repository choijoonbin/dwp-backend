package com.dwp.platform.contracts.hris.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeMaterializationWorkloadProofV1Test {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private static final String REF = "href_0123456789abcdef";
    private static KeyPair keyPair;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger sequence = new AtomicInteger(100);

    @BeforeAll
    static void generateRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void admitsBodylessGetWithTrustDerivedIdentityAndCompleteDelegatedContext()
            throws Exception {
        AtomicReference<HomeMaterializationWorkloadProofV1.ProofClaims> observed =
                new AtomicReference<>();
        String path = HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE.path(REF);
        List<HomeMaterializationWorkloadProofV1.Header> headers = proof(
                HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE, path, ignored -> { });

        assertThat(headers).noneMatch(header -> header.name().equalsIgnoreCase("Content-Type"));
        var admission = HomeMaterializationWorkloadProofV1.verify(
                mapper, clock(), HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE,
                path, headers, trustedKey(), (endpoint, claims) -> {
                    observed.set(claims);
                    return claims.tenantId() == 7
                            && claims.subjectPrincipalPublicId().equals(uuid(701))
                            && claims.capability().equals("hcm.personal.profile.view")
                            && claims.scopeRevision() == 4
                            && claims.effectivePermissionRevision() == 6
                            && claims.fieldPolicyRevision() == 8;
                }, new ReplayLedger());

        assertThat(admission.tenantId()).isEqualTo(7);
        assertThat(admission.endpoint())
                .isEqualTo(HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE);
        assertThat(observed.get().purpose())
                .isEqualTo(HrisHomeContributionMaterializationV1.PURPOSE);
        assertThat(observed.get().populationScopeDigest()).isEqualTo("a".repeat(64));
    }

    @Test
    void sealsExactGetPathOwnerAudiencePurposeAndModuleScope() throws Exception {
        String peoplePath = HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE.path(REF);

        assertDenied("EXACT_HOME_MATERIALIZATION_GET_PATH_REQUIRED", () ->
                HomeMaterializationWorkloadProofV1.verify(
                        mapper, clock(), HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE,
                        peoplePath + "?tenantId=7",
                        proof(HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE,
                                peoplePath, ignored -> { }),
                        trustedKey(), allowCurrentAuthority(), new ReplayLedger()));

        assertDenied("EXACT_HOME_MATERIALIZATION_CLAIMS_REQUIRED", () ->
                verify(HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE,
                        claims -> claims.put("aud", "dwp-time-server")));
        assertDenied("EXACT_HOME_MATERIALIZATION_CLAIMS_REQUIRED", () ->
                verify(HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE,
                        claims -> claims.put("purpose", "HRIS_HOME_VIEW")));
        assertDenied("EXACT_HOME_MATERIALIZATION_CLAIMS_REQUIRED", () ->
                verify(HomeMaterializationWorkloadProofV1.Endpoint.PAYROLL, claims -> {
                    claims.put("browserAudience", "TEAM");
                    claims.put("ownerPopulationScope", "TEAM");
                }));
    }

    @Test
    void requiresRs256TrustDerivedCallerAndThirtySecondFreshness() throws Exception {
        assertDenied("TRUST_DERIVED_WORKLOAD_IDENTITY_MISMATCH", () -> {
            var endpoint = HomeMaterializationWorkloadProofV1.Endpoint.TIME_LEAVE;
            String path = endpoint.path(REF);
            HomeMaterializationWorkloadProofV1.verify(
                    mapper, clock(), endpoint, path, proof(endpoint, path, ignored -> { }),
                    keyId -> new HomeMaterializationWorkloadProofV1.VerifiedSigningKey(
                            keyPair.getPublic(),
                            HomeMaterializationWorkloadProofV1.WorkloadAuthentication
                                    .CLIENT_CREDENTIAL_JWT,
                            "forged-platform-server"),
                    allowCurrentAuthority(), new ReplayLedger());
        });

        assertDenied("FRESH_NON_REPLAYABLE_HOME_PROOF_REQUIRED", () ->
                verify(HomeMaterializationWorkloadProofV1.Endpoint.PERFORMANCE, claims -> {
                    claims.put("iat", NOW.minusSeconds(1).getEpochSecond());
                    claims.put("nbf", NOW.minusSeconds(1).getEpochSecond());
                    claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
                }));

        assertDenied("JOSE_PROFILE_INVALID", () -> {
            var endpoint = HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE;
            String path = endpoint.path(REF);
            List<HomeMaterializationWorkloadProofV1.Header> headers = proof(
                    endpoint, path, ignored -> { }, jose -> jose.put("alg", "RS512"));
            HomeMaterializationWorkloadProofV1.verify(
                    mapper, clock(), endpoint, path, headers, trustedKey(),
                    allowCurrentAuthority(), new ReplayLedger());
        });
    }

    @Test
    void reservesJtiAndNonceAndRejectsReplay() throws Exception {
        var endpoint = HomeMaterializationWorkloadProofV1.Endpoint.PAYROLL;
        String path = endpoint.path(REF);
        List<HomeMaterializationWorkloadProofV1.Header> headers =
                proof(endpoint, path, ignored -> { });
        ReplayLedger replay = new ReplayLedger();

        HomeMaterializationWorkloadProofV1.verify(
                mapper, clock(), endpoint, path, headers, trustedKey(),
                allowCurrentAuthority(), replay);
        assertDenied("HOME_WORKLOAD_PROOF_REPLAYED", () ->
                HomeMaterializationWorkloadProofV1.verify(
                        mapper, clock(), endpoint, path, headers, trustedKey(),
                        allowCurrentAuthority(), replay));
    }

    @Test
    void rejectsBrowserTenantDuplicateAndUnknownDwpHeaders() throws Exception {
        var endpoint = HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE;
        String path = endpoint.path(REF);
        List<HomeMaterializationWorkloadProofV1.Header> base =
                proof(endpoint, path, ignored -> { });

        List<HomeMaterializationWorkloadProofV1.Header> tenant = new ArrayList<>(base);
        tenant.add(new HomeMaterializationWorkloadProofV1.Header("X-DWP-Tenant-ID", "7"));
        assertDenied("BROWSER_END_USER_OR_TENANT_HEADER_FORBIDDEN", () ->
                admit(endpoint, path, tenant));

        List<HomeMaterializationWorkloadProofV1.Header> duplicate = new ArrayList<>(base);
        duplicate.add(new HomeMaterializationWorkloadProofV1.Header(
                HomeMaterializationWorkloadProofV1.SERVICE_IDENTITY_HEADER,
                HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID));
        assertDenied("DUPLICATE_OR_INVALID_HEADER_EVIDENCE", () ->
                admit(endpoint, path, duplicate));

        List<HomeMaterializationWorkloadProofV1.Header> unknown = new ArrayList<>(base);
        unknown.add(new HomeMaterializationWorkloadProofV1.Header("X-DWP-Scope", "SELF"));
        assertDenied("UNRECOGNIZED_DWP_HEADER_FORBIDDEN", () ->
                admit(endpoint, path, unknown));

        List<HomeMaterializationWorkloadProofV1.Header> browser = new ArrayList<>(base);
        browser.add(new HomeMaterializationWorkloadProofV1.Header(
                "Authorization", "Bearer browser-token"));
        assertDenied("BROWSER_END_USER_OR_TENANT_HEADER_FORBIDDEN", () ->
                admit(endpoint, path, browser));
    }

    @Test
    void rejectsUnknownSignedClaimsAndCurrentAuthorityDrift() throws Exception {
        assertDenied("WORKLOAD_CLAIMS_INVALID", () ->
                verify(HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE,
                        claims -> claims.put("tenantOverride", 99)));

        var endpoint = HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE;
        String path = endpoint.path(REF);
        assertDenied("CURRENT_HOME_AUTHORITY_REQUIRED", () ->
                HomeMaterializationWorkloadProofV1.verify(
                        mapper, clock(), endpoint, path,
                        proof(endpoint, path, ignored -> { }), trustedKey(),
                        (ignoredEndpoint, ignoredClaims) -> false, new ReplayLedger()));
    }

    private void verify(
            HomeMaterializationWorkloadProofV1.Endpoint endpoint,
            Consumer<ObjectNode> claimChange) throws Exception {
        String path = endpoint.path(REF);
        HomeMaterializationWorkloadProofV1.verify(
                mapper, clock(), endpoint, path, proof(endpoint, path, claimChange),
                trustedKey(), allowCurrentAuthority(), new ReplayLedger());
    }

    private void admit(
            HomeMaterializationWorkloadProofV1.Endpoint endpoint,
            String path,
            List<HomeMaterializationWorkloadProofV1.Header> headers) {
        HomeMaterializationWorkloadProofV1.verify(
                mapper, clock(), endpoint, path, headers, trustedKey(),
                allowCurrentAuthority(), new ReplayLedger());
    }

    private List<HomeMaterializationWorkloadProofV1.Header> proof(
            HomeMaterializationWorkloadProofV1.Endpoint endpoint,
            String path,
            Consumer<ObjectNode> claimChange) throws Exception {
        return proof(endpoint, path, claimChange, ignored -> { });
    }

    private List<HomeMaterializationWorkloadProofV1.Header> proof(
            HomeMaterializationWorkloadProofV1.Endpoint endpoint,
            String path,
            Consumer<ObjectNode> claimChange,
            Consumer<ObjectNode> joseChange) throws Exception {
        int value = sequence.incrementAndGet();
        ObjectNode jose = mapper.createObjectNode();
        jose.put("alg", "RS256");
        jose.put("typ", "JWT");
        jose.put("kid", "home-materialization-rsa-1");
        joseChange.accept(jose);

        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        claims.put("aud", endpoint.ownerAudience());
        claims.put("sub", HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        claims.put("iat", NOW.minusSeconds(1).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(1).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(20).getEpochSecond());
        claims.put("jti", uuid(1000 + value).toString());
        claims.put("requestNonce", uuid(2000 + value).toString());
        claims.put("purpose", HrisHomeContributionMaterializationV1.PURPOSE);
        claims.put("method", "GET");
        claims.put("path", path);
        claims.put("tenantId", 7);
        claims.put("subjectPrincipalPublicId", uuid(701).toString());
        claims.put("correlationId", uuid(702).toString());
        claims.put("capability", "hcm.personal.profile.view");
        claims.put("ownerPopulationScope", scope(endpoint).name());
        claims.put("scopeRevision", 4);
        claims.put("populationScopeDigest", "a".repeat(64));
        claims.put("effectivePermissionRevision", 6);
        claims.put("fieldPolicyRevision", 8);
        claims.put("widgetKey", widgetKey(endpoint));
        claims.put("browserAudience", "SELF");
        claims.put("authn", "CLIENT_CREDENTIAL_JWT");
        claimChange.accept(claims);

        String compact = sign(jose, claims);
        return HomeMaterializationWorkloadProofV1.headers(compact);
    }

    private String sign(ObjectNode jose, ObjectNode claims) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(mapper.writeValueAsBytes(jose));
        String payload = encoder.encodeToString(mapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encoder.encodeToString(signature.sign());
    }

    private HomeMaterializationWorkloadProofV1.SigningKeyResolver trustedKey() {
        return keyId -> new HomeMaterializationWorkloadProofV1.VerifiedSigningKey(
                keyPair.getPublic(),
                HomeMaterializationWorkloadProofV1.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT,
                HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
    }

    private HomeMaterializationWorkloadProofV1.CurrentAuthorityVerifier allowCurrentAuthority() {
        return (endpoint, claims) -> true;
    }

    private Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static HrisHomeContributionMaterializationV1.OwnerPopulationScope scope(
            HomeMaterializationWorkloadProofV1.Endpoint endpoint) {
        return HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF;
    }

    private static String widgetKey(HomeMaterializationWorkloadProofV1.Endpoint endpoint) {
        return switch (endpoint) {
            case PEOPLE -> "people-summary";
            case PERFORMANCE -> "performance-summary";
            case TIME_LEAVE -> "time-summary";
            case PAYROLL -> "payroll-summary";
        };
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

    private static final class ReplayLedger
            implements HomeMaterializationWorkloadProofV1.ReplayStore {
        private final Set<String> reservations = new HashSet<>();

        @Override
        public boolean reserve(UUID proofId, UUID nonce, long tenantId, Instant expiresAt) {
            return reservations.add(tenantId + ":" + proofId + ":" + nonce);
        }
    }
}
