package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ResolverCaller;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class HrisServiceGrantAuthorityProofVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final UUID CORRELATION = new UUID(0, 41);
    private static KeyPair leafKey;
    private static String thumbprint;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        leafKey = generator.generateKeyPair();
        thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest("resolver-leaf".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void leafProofProducesUnreservedReplayEvidenceWithoutAuthorityRead() throws Exception {
        var request = request();
        byte[] body = HrisServiceGrantAuthorityV1.encodeRequest(mapper, request);
        String compact = compact(body, thumbprint, "dwp-people-server");
        var profile = Hrm013WorkloadProofV1.resolverBootstrap(ResolverCaller.PEOPLE);
        var verifier = new HrisServiceGrantAuthorityProofVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));

        var context = verifier.verify(request, body, ResolverCaller.PEOPLE,
                Hrm013WorkloadProofV1.headers(profile, compact, CORRELATION),
                new TransportIdentity("dwp-people-server", thumbprint),
                leafKey.getPublic());

        assertThat(context.request()).isEqualTo(request);
        assertThat(context.jtiDigest()).hasSize(64)
                .isNotEqualTo(context.requestNonceDigest());
        assertThat(context.proofExpiresAt()).isEqualTo(NOW.plusSeconds(35));
        assertThat(context.resolverProofSha256()).hasSize(64);
    }

    @Test
    void rejectsCertificateConfirmationOrRequestTupleDrift() throws Exception {
        var request = request();
        byte[] body = HrisServiceGrantAuthorityV1.encodeRequest(mapper, request);
        var profile = Hrm013WorkloadProofV1.resolverBootstrap(ResolverCaller.PEOPLE);
        var verifier = new HrisServiceGrantAuthorityProofVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        String wrongKid = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(new byte[] {9}));

        assertThatThrownBy(() -> verifier.verify(request, body, ResolverCaller.PEOPLE,
                Hrm013WorkloadProofV1.headers(profile,
                        compact(body, wrongKid, "dwp-people-server"), CORRELATION),
                new TransportIdentity("dwp-people-server", thumbprint),
                leafKey.getPublic()))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void repositoryInfrastructureFailureBecomesAuthorityUnavailable() throws Exception {
        var request = request();
        byte[] body = HrisServiceGrantAuthorityV1.encodeRequest(mapper, request);
        var profile = Hrm013WorkloadProofV1.resolverBootstrap(ResolverCaller.PEOPLE);
        var context = new HrisServiceGrantAuthorityProofVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC)).verify(
                request, body, ResolverCaller.PEOPLE,
                Hrm013WorkloadProofV1.headers(
                        profile, compact(body, thumbprint, "dwp-people-server"), CORRELATION),
                new TransportIdentity("dwp-people-server", thumbprint), leafKey.getPublic());
        var repository = mock(HrisServiceGrantAuthorityRepository.class);
        when(repository.resolve(any(), any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database offline"));

        assertThatThrownBy(() -> new HrisServiceGrantAuthorityService(repository)
                .resolve(context))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE);
    }

    private HrisServiceGrantAuthorityV1.ResolveRequest request() {
        return new HrisServiceGrantAuthorityV1.ResolveRequest(
                17, "dwp-people-server", "dwp-people-server", "dwp-time-server",
                "SVC-PEP-TIM-004", "HRIS_SEPARATION_TIME_TASK_DISPATCH",
                HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING,
                0, 0, CORRELATION, null);
    }

    private String compact(byte[] body, String keyId, String requester) throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", requester);
        claims.put("aud", "dwp-auth-server");
        claims.put("sub", requester);
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
        claims.put("jti", new UUID(0, 901).toString());
        claims.put("requestNonce", new UUID(0, 902).toString());
        claims.put("bindingId", "SVC-AUT-HRM-011");
        claims.put("purpose", HrisServiceGrantAuthorityV1.PURPOSE);
        claims.put("method", "POST");
        claims.put("path", HrisServiceGrantAuthorityV1.PATH);
        claims.put("tenantId", 17);
        claims.put("correlationId", CORRELATION.toString());
        claims.put("requestBodySha256", Hrm013WorkloadProofV1.sha256(body));
        claims.put("authn", "MTLS_LEAF_RS256");
        claims.put("cnf", Map.of("x5t#S256", thumbprint));
        claims.put("resolutionMode", "PROBE_MISSING");
        claims.put("signedTenantGrantRevision", 0);
        claims.put("signedAuthorizationRevision", 0);
        claims.put("requestingWorkloadId", requester);
        claims.put("targetCallerWorkloadId", "dwp-people-server");
        claims.put("targetOwnerWorkloadId", "dwp-time-server");
        claims.put("targetBindingId", "SVC-PEP-TIM-004");
        claims.put("targetPurpose", "HRIS_SEPARATION_TIME_TASK_DISPATCH");
        return sign(claims, keyId);
    }

    private String sign(Map<String, Object> claims, String keyId) throws Exception {
        Map<String, Object> jose = new LinkedHashMap<>();
        jose.put("typ", "JWT");
        jose.put("kid", keyId);
        jose.put("alg", "RS256");
        String head = encode(Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                mapper, mapper.writeValueAsBytes(jose)));
        String payload = encode(Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                mapper, mapper.writeValueAsBytes(claims)));
        String input = head + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(leafKey.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encode(signature.sign());
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
