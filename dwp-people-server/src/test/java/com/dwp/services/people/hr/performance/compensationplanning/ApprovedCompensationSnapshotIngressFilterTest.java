package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.services.people.security.PeopleSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovedCompensationSnapshotIngressFilterTest {

    private static final Instant NOW = Instant.parse("2027-01-02T00:00:00Z");
    private static final long TENANT = 71L;
    private static final UUID SNAPSHOT =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final String DIGEST = "a".repeat(64);
    private static final String PATH =
            ApprovedCompensationSnapshotIngressFilter.PATH_PREFIX + SNAPSHOT;
    private static final String ISSUER = "https://auth.dwp.internal";

    private ObjectMapper mapper;
    private RSAKey key;
    private ApprovedCompensationSnapshotIngressFilter filter;
    private PeopleSecurityFilter genericPeopleFilter;
    private X509Certificate certificate;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        key = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID("payroll-snapshot-key-1")
                .generate();
        var trust = new ApprovedCompensationSnapshotTrustProfile(
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                key.toRSAPublicKey(),
                key.getKeyID(),
                ISSUER,
                Map.of(TENANT,
                        new ApprovedCompensationSnapshotTrustProfile.OwnerGrant(3L, 9L)));
        filter = new ApprovedCompensationSnapshotIngressFilter(
                trust,
                new ApprovedCompensationSnapshotAdmissionControl(
                        Clock.fixed(NOW, ZoneOffset.UTC), 2, 60),
                mapper);
        genericPeopleFilter = new PeopleSecurityFilter("ordinary-people-token", mapper, false);
        certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(
                List.<List<?>>of(List.of(
                        6, ApprovedCompensationSnapshotTrustProfile.EXPECTED_SPIFFE_ID)));
    }

    @Test
    void exactDualProofEstablishesContextAndOnlyThenSkipsTheGenericUserFilter()
            throws Exception {
        MockHttpServletRequest request = validRequest(claims());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, (securedRequest, securedResponse) ->
                genericPeopleFilter.doFilter(securedRequest, securedResponse,
                        (ignoredRequest, ignoredResponse) -> {
                            var trusted = ApprovedCompensationSnapshotRequestContext.require(
                                    (MockHttpServletRequest) securedRequest);
                            assertThat(trusted.tenantId()).isEqualTo(TENANT);
                            assertThat(trusted.snapshotId()).isEqualTo(SNAPSHOT);
                            assertThat(trusted.snapshotRevision()).isEqualTo(4L);
                            assertThat(trusted.payloadDigest()).isEqualTo(DIGEST);
                            assertThat(trusted.tenantGrantRevision()).isEqualTo(3L);
                            assertThat(trusted.policyRevision()).isEqualTo(9L);
                            reached.set(true);
                        }));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(ApprovedCompensationSnapshotRequestContext.isVerifiedRequest(request))
                .isFalse();

        MockHttpServletResponse genericResponse = new MockHttpServletResponse();
        genericPeopleFilter.doFilter(baseRequest(), genericResponse,
                (ignoredRequest, ignoredResponse) -> {
                    throw new AssertionError("Unverified workload reached the controller.");
                });
        assertThat(genericResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsMissingMtlsWrongAudienceAndStaleOwnerGrantBeforeTheController()
            throws Exception {
        MockHttpServletRequest missingMtls = validRequest(claims());
        missingMtls.removeAttribute(
                ApprovedCompensationSnapshotTrustProfile.CERTIFICATE_ATTRIBUTE);
        assertRejected(missingMtls, 403, "MUTUAL_TLS_WORKLOAD_CERTIFICATE_REQUIRED");

        Map<String, Object> wrongAudience = claims();
        wrongAudience.put("aud", "dwp-people-server");
        assertRejected(validRequest(wrongAudience), 403, "EXACT_WORKLOAD_CLAIMS_REQUIRED");

        Map<String, Object> staleGrant = claims();
        staleGrant.put("tenantGrantRevision", 2L);
        assertRejected(validRequest(staleGrant), 403, "CURRENT_OWNER_TENANT_GRANT_REQUIRED");
    }

    @Test
    void rejectsWrongSpiffeInvalidSignatureAndExpiredWorkloadProof() throws Exception {
        X509Certificate wrongCertificate = mock(X509Certificate.class);
        when(wrongCertificate.getSubjectAlternativeNames()).thenReturn(
                List.<List<?>>of(List.of(6, "spiffe://dwp/service/other-service")));
        MockHttpServletRequest wrongSpiffe = validRequest(claims());
        wrongSpiffe.setAttribute(
                ApprovedCompensationSnapshotTrustProfile.CERTIFICATE_ATTRIBUTE,
                new X509Certificate[]{wrongCertificate});
        assertRejected(wrongSpiffe, 403, "PAYROLL_SPIFFE_IDENTITY_REQUIRED");

        RSAKey otherKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .generate();
        MockHttpServletRequest invalidSignature = baseRequest();
        invalidSignature.addHeader(
                "Authorization", "Bearer " + token(claims(), otherKey));
        assertRejected(invalidSignature, 401, "WORKLOAD_TOKEN_SIGNATURE_INVALID");

        Map<String, Object> expired = claims();
        expired.put("iat", NOW.minusSeconds(31).getEpochSecond());
        expired.put("nbf", NOW.minusSeconds(31).getEpochSecond());
        expired.put("exp", NOW.minusSeconds(1).getEpochSecond());
        assertRejected(validRequest(expired), 403, "FRESH_WORKLOAD_TOKEN_REQUIRED");
    }

    @Test
    void rejectsPurposeProjectionTupleAndBrowserAuthorityDrift() throws Exception {
        Map<String, Object> wrongPurpose = claims();
        wrongPurpose.put("purpose", "OTHER_PURPOSE");
        assertRejected(validRequest(wrongPurpose), 403, "EXACT_WORKLOAD_CLAIMS_REQUIRED");

        Map<String, Object> wrongProjection = claims();
        wrongProjection.put("projection", "XCON-999");
        assertRejected(validRequest(wrongProjection), 403, "EXACT_WORKLOAD_CLAIMS_REQUIRED");

        Map<String, Object> wrongTuple = claims();
        wrongTuple.put("snapshotRevision", 5L);
        assertRejected(validRequest(wrongTuple), 403, "EXACT_WORKLOAD_CLAIMS_REQUIRED");

        MockHttpServletRequest browser = validRequest(claims());
        browser.addHeader("Cookie", "DWP_SESSION=browser");
        assertRejected(browser, 403, "BROWSER_OR_CALLER_AUTHORITY_HEADERS_FORBIDDEN");

        MockHttpServletRequest tenantOverride = validRequest(claims());
        tenantOverride.addHeader("X-DWP-Tenant-ID", Long.toString(TENANT));
        assertRejected(tenantOverride, 403,
                "BROWSER_OR_CALLER_AUTHORITY_HEADERS_FORBIDDEN");
    }

    @Test
    void rejectsMalformedRouteQueryDuplicateBearerAndNonTls13() throws Exception {
        MockHttpServletRequest duplicateBearer = validRequest(claims());
        duplicateBearer.addHeader("Authorization", "Bearer " + token(claims()));
        assertRejected(duplicateBearer, 401, "BEARER_TOKEN_REQUIRED");

        MockHttpServletRequest unexpectedQuery = validRequest(claims());
        unexpectedQuery.addParameter("tenantId", Long.toString(TENANT));
        assertRejected(unexpectedQuery, 404, "SVC_PEP_PER_001_ROUTE_NOT_FOUND");

        MockHttpServletRequest tls12 = validRequest(claims());
        tls12.setAttribute(
                ApprovedCompensationSnapshotTrustProfile.TLS_PROTOCOL_ATTRIBUTE,
                "TLSv1.2");
        assertRejected(tls12, 403, "TLS_1_3_REQUIRED");

        MockHttpServletRequest method = validRequest(claims());
        method.setMethod("POST");
        assertRejected(method, 405, "SVC_PEP_PER_001_METHOD_NOT_ALLOWED");
    }

    @Test
    void rateLimitsOnlyAfterExactWorkloadAuthenticationAndReturnsRetryAfter()
            throws Exception {
        filter = new ApprovedCompensationSnapshotIngressFilter(
                new ApprovedCompensationSnapshotTrustProfile(
                        mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        key.toRSAPublicKey(),
                        key.getKeyID(),
                        ISSUER,
                        Map.of(TENANT,
                                new ApprovedCompensationSnapshotTrustProfile.OwnerGrant(3L, 9L))),
                new ApprovedCompensationSnapshotAdmissionControl(
                        Clock.fixed(NOW, ZoneOffset.UTC), 1, 1),
                mapper);

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(validRequest(claims()), first, (request, response) -> { });
        assertThat(first.getStatus()).isEqualTo(200);

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(validRequest(claims()), second, (request, response) -> {
            throw new AssertionError("Rate-limited request reached the controller.");
        });
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("60");
        assertThat(second.getContentAsString()).contains("SVC_PEP_PER_001_RATE_LIMITED");
    }

    @Test
    void holdsTheConcurrencyLeaseUntilAnAsyncStreamingResponseCompletes()
            throws Exception {
        filter = new ApprovedCompensationSnapshotIngressFilter(
                new ApprovedCompensationSnapshotTrustProfile(
                        mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        key.toRSAPublicKey(),
                        key.getKeyID(),
                        ISSUER,
                        Map.of(TENANT,
                                new ApprovedCompensationSnapshotTrustProfile.OwnerGrant(3L, 9L))),
                new ApprovedCompensationSnapshotAdmissionControl(
                        Clock.fixed(NOW, ZoneOffset.UTC), 1, 10),
                mapper);

        MockHttpServletRequest streaming = validRequest(claims());
        streaming.setAsyncSupported(true);
        MockHttpServletResponse streamingResponse = new MockHttpServletResponse();
        filter.doFilter(streaming, streamingResponse,
                (request, response) -> request.startAsync(request, response));

        MockHttpServletResponse concurrent = new MockHttpServletResponse();
        filter.doFilter(validRequest(claims()), concurrent, (request, response) -> {
            throw new AssertionError("Concurrent request reached the controller.");
        });
        assertThat(concurrent.getStatus()).isEqualTo(429);
        assertThat(concurrent.getContentAsString())
                .contains("SVC_PEP_PER_001_CONCURRENCY_LIMITED");

        streaming.getAsyncContext().complete();

        MockHttpServletResponse afterCompletion = new MockHttpServletResponse();
        filter.doFilter(validRequest(claims()), afterCompletion, (request, response) -> { });
        assertThat(afterCompletion.getStatus()).isEqualTo(200);
    }

    private void assertRejected(
            MockHttpServletRequest request,
            int expectedStatus,
            String expectedReason) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> reached.set(true));
        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getContentAsString()).contains(expectedReason);
    }

    private MockHttpServletRequest validRequest(Map<String, Object> claims)
            throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("Authorization", "Bearer " + token(claims));
        return request;
    }

    private MockHttpServletRequest baseRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.setSecure(true);
        request.setAttribute(
                ApprovedCompensationSnapshotTrustProfile.TLS_PROTOCOL_ATTRIBUTE,
                "TLSv1.3");
        request.setAttribute(
                ApprovedCompensationSnapshotTrustProfile.CERTIFICATE_ATTRIBUTE,
                new X509Certificate[]{certificate});
        request.addParameter("snapshotRevision", "4");
        request.addParameter("payloadDigest", DIGEST);
        return request;
    }

    private Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", ApprovedCompensationSnapshotTrustProfile.EXPECTED_AUDIENCE);
        claims.put("sub", ApprovedCompensationPlanSnapshotProvider.CALLER_WORKLOAD);
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
        claims.put("jti", "10000000-0000-4000-8000-000000000008");
        claims.put("scope", ApprovedCompensationSnapshotTrustProfile.EXPECTED_SCOPE);
        claims.put("tenantId", TENANT);
        claims.put("tenantGrantRevision", 3L);
        claims.put("policyRevision", 9L);
        claims.put("purpose", ApprovedCompensationPlanSnapshotProvider.PURPOSE);
        claims.put("projection", ApprovedCompensationPlanSnapshotProvider.PROJECTION);
        claims.put("consumerSession",
                ApprovedCompensationSnapshotTrustProfile.EXPECTED_CONSUMER_SESSION);
        claims.put("populationPolicy",
                ApprovedCompensationSnapshotTrustProfile.EXPECTED_POPULATION_POLICY);
        claims.put("fieldPolicy",
                ApprovedCompensationSnapshotTrustProfile.EXPECTED_FIELD_POLICY);
        claims.put("method", "GET");
        claims.put("path", PATH);
        claims.put("snapshotId", SNAPSHOT.toString());
        claims.put("snapshotRevision", 4L);
        claims.put("payloadDigest", DIGEST);
        return claims;
    }

    private String token(Map<String, Object> claims) throws Exception {
        return token(claims, key);
    }

    private String token(Map<String, Object> claims, RSAKey signingKey) throws Exception {
        JWSObject value = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(signingKey.getKeyID())
                        .build(),
                new Payload(mapper.writeValueAsString(claims)));
        value.sign(new RSASSASigner(signingKey));
        return value.serialize();
    }
}
