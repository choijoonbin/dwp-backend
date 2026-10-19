package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractRolloutGate;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Hrm011WorkloadIngressFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-19T02:00:00Z");
    private static final long TENANT = 71L;
    private static final String ISSUER = "https://auth.dwp.internal";
    private static final UUID ATTEMPT =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CORRELATION =
            UUID.fromString("10000000-0000-4000-8000-000000000002");

    private ObjectMapper mapper;
    private RSAKey key;
    private X509Certificate certificate;
    private Hrm011WorkloadIngressFilter filter;
    private PeopleSecurityFilter genericPeopleFilter;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        key = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID("hrm011-payroll-key-1")
                .generate();
        certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(
                List.<List<?>>of(List.of(
                        6, Hrm011WorkloadTrustProfile.EXPECTED_SPIFFE_ID)));
        filter = filter(new EmploymentContractRolloutGate(Set.of(TENANT)), 2, 60);
        genericPeopleFilter = new PeopleSecurityFilter("ordinary-token", mapper, false);
    }

    @Test
    void exactBindingsCreateTypedContextAndOnlyThenSkipTheGenericFilter()
            throws Exception {
        assertThat(Hrm011WorkloadIngressFilter.class.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 15);
        List<RequestCase> cases = List.of(
                new RequestCase(
                        Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
                        Hrm011WorkloadIngressFilter.COMPENSATION_BINDING,
                        Hrm011WorkloadIngress.SVC003_PURPOSE,
                        Hrm011WorkloadIngress.COMPENSATION,
                        null),
                new RequestCase(
                        Hrm011WorkloadIngressFilter.PAYROLL_FACTS_PATH,
                        Hrm011WorkloadIngressFilter.PAYROLL_FACTS_BINDING,
                        Hrm011WorkloadIngress.SVC005_PURPOSE,
                        "TAX_IDENTITY_V1",
                        "cursor-token"));

        for (RequestCase value : cases) {
            MockHttpServletRequest request = validRequest(value, claims(value));
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();

            filter.doFilter(request, response, (securedRequest, securedResponse) ->
                    genericPeopleFilter.doFilter(securedRequest, securedResponse,
                            (ignoredRequest, ignoredResponse) -> {
                                var verified = Hrm011WorkloadRequestContext.require(
                                        (MockHttpServletRequest) securedRequest);
                                assertThat(verified.bindingId()).isEqualTo(value.bindingId());
                                assertThat(verified.attemptId()).isEqualTo(ATTEMPT);
                                assertThat(verified.correlationId()).isEqualTo(CORRELATION);
                                assertThat(verified.attemptId())
                                        .isNotEqualTo(verified.correlationId());
                                assertThat(verified.tenantGrantRevision()).isEqualTo(3L);
                                assertThat(verified.trusted().tenantId()).isEqualTo(TENANT);
                                assertThat(verified.trusted().policyRevision()).isEqualTo(9L);
                                assertThat(verified.snapshotRequest().cursor())
                                        .isEqualTo(value.cursor());
                                reached.set(true);
                            }));

            assertThat(reached).as(value.path()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(Hrm011WorkloadRequestContext.isVerifiedRequest(request)).isFalse();
        }
    }

    @Test
    void rejectsTlsCertificateAndSpiffeFailuresBeforeTheController() throws Exception {
        RequestCase value = compensation();
        MockHttpServletRequest insecure = validRequest(value, claims(value));
        insecure.setSecure(false);
        assertRejected(insecure, 403, "TLS_1_3_REQUIRED");

        MockHttpServletRequest missingCertificate = validRequest(value, claims(value));
        missingCertificate.removeAttribute(Hrm011WorkloadTrustProfile.CERTIFICATE_ATTRIBUTE);
        assertRejected(missingCertificate, 403,
                "MUTUAL_TLS_WORKLOAD_CERTIFICATE_REQUIRED");

        X509Certificate wrong = mock(X509Certificate.class);
        when(wrong.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(6, Hrm011WorkloadTrustProfile.EXPECTED_SPIFFE_ID),
                List.of(6, "spiffe://dwp/service/other")));
        MockHttpServletRequest additionalSan = validRequest(value, claims(value));
        additionalSan.setAttribute(Hrm011WorkloadTrustProfile.CERTIFICATE_ATTRIBUTE,
                new X509Certificate[]{wrong});
        assertRejected(additionalSan, 403, "PAYROLL_SPIFFE_IDENTITY_REQUIRED");
    }

    @Test
    void rejectsClosedClaimCorrelationGrantAndEnrollmentDrift() throws Exception {
        RequestCase value = compensation();
        Map<String, Object> extra = claims(value);
        extra.put("extra", "forbidden");
        assertRejected(validRequest(value, extra), 401, "WORKLOAD_CLAIMS_INVALID");

        Map<String, Object> correlation = claims(value);
        correlation.put("correlationId", ATTEMPT.toString());
        assertRejected(validRequest(value, correlation), 403,
                "SEPARATE_ATTEMPT_AND_CORRELATION_IDS_REQUIRED");

        Map<String, Object> staleGrant = claims(value);
        staleGrant.put("tenantGrantRevision", 2L);
        assertRejected(validRequest(value, staleGrant), 403,
                "CURRENT_HRM011_TENANT_GRANT_REQUIRED");

        filter = filter(new EmploymentContractRolloutGate(Set.of()), 2, 60);
        assertRejected(validRequest(value, claims(value)), 403,
                "CURRENT_HRM011_TENANT_GRANT_REQUIRED");
    }

    @Test
    void rejectsAWorkloadLifetimeThatOverflowsSignedLongArithmetic() throws Exception {
        RequestCase value = compensation();
        Map<String, Object> overflow = claims(value);
        overflow.put("iat", Long.MIN_VALUE);
        overflow.put("nbf", Long.MIN_VALUE);
        overflow.put("exp", Long.MAX_VALUE);

        assertRejected(validRequest(value, overflow), 403,
                "FRESH_WORKLOAD_TOKEN_REQUIRED");
    }

    @Test
    void rejectsRouteQueryBodyAndCallerAuthorityDrift() throws Exception {
        RequestCase value = compensation();
        MockHttpServletRequest duplicateLimit = validRequest(value, claims(value));
        duplicateLimit.addParameter("limit", "100");
        assertRejected(duplicateLimit, 404, "HRM011_WORKLOAD_ROUTE_OR_QUERY_INVALID");

        MockHttpServletRequest nonCanonicalLimit = validRequest(value, claims(value));
        nonCanonicalLimit.setParameter("limit", "0100");
        assertRejected(nonCanonicalLimit, 404, "HRM011_WORKLOAD_ROUTE_OR_QUERY_INVALID");

        MockHttpServletRequest unknown = validRequest(value, claims(value));
        unknown.addParameter("tenantId", Long.toString(TENANT));
        assertRejected(unknown, 404, "HRM011_WORKLOAD_ROUTE_OR_QUERY_INVALID");

        MockHttpServletRequest body = validRequest(value, claims(value));
        body.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertRejected(body, 403, "BROWSER_OR_CALLER_AUTHORITY_HEADERS_FORBIDDEN");

        MockHttpServletRequest callerTls = validRequest(value, claims(value));
        callerTls.addHeader("X-DWP-TLS-Protocol", "TLSv1.3");
        assertRejected(callerTls, 403, "BROWSER_OR_CALLER_AUTHORITY_HEADERS_FORBIDDEN");

        MockHttpServletRequest method = validRequest(value, claims(value));
        method.setMethod("POST");
        assertRejected(method, 405, "HRM011_WORKLOAD_METHOD_NOT_ALLOWED");
    }

    @Test
    void signedQueryAndBindingMustMatchTheExactRequest() throws Exception {
        RequestCase value = compensation();
        Map<String, Object> wrongBinding = claims(value);
        wrongBinding.put("bindingId", Hrm011WorkloadIngressFilter.PAYROLL_FACTS_BINDING);
        assertRejected(validRequest(value, wrongBinding), 403,
                "EXACT_HRM011_WORKLOAD_CLAIMS_REQUIRED");

        Map<String, Object> wrongAsOf = claims(value);
        wrongAsOf.put("asOf", NOW.toString());
        assertRejected(validRequest(value, wrongAsOf), 403,
                "EXACT_HRM011_WORKLOAD_CLAIMS_REQUIRED");

        Map<String, Object> cursorNotNull = claims(value);
        cursorNotNull.put("cursor", "unexpected");
        assertRejected(validRequest(value, cursorNotNull), 403,
                "EXACT_HRM011_WORKLOAD_CLAIMS_REQUIRED");
    }

    @Test
    void disabledTrustReturns503AndEnabledIncompleteTrustFailsStartup() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<EmploymentContractRolloutGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        Hrm011WorkloadTrustProfile disabled = new Hrm011WorkloadTrustProfile(
                new MockEnvironment(), mapper, provider);
        filter = new Hrm011WorkloadIngressFilter(
                disabled,
                new Hrm011WorkloadAdmissionControl(
                        Clock.fixed(NOW, ZoneOffset.UTC), 2, 60),
                mapper);
        assertRejected(validRequest(compensation(), claims(compensation())), 503,
                "HRM011_WORKLOAD_SERVICE_UNAVAILABLE");

        MockEnvironment enabled = new MockEnvironment().withProperty(
                "dwp.hris.contract-compensation-workbench.workload-service.enabled",
                "true");
        assertThatThrownBy(() -> new Hrm011WorkloadTrustProfile(
                enabled, mapper, provider))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void authenticationPrecedesRateAdmissionAndReturnsRetryAfter() throws Exception {
        filter = filter(new EmploymentContractRolloutGate(Set.of(TENANT)), 1, 1);
        RequestCase value = compensation();
        Map<String, Object> invalid = claims(value);
        invalid.put("aud", "wrong-audience");
        assertRejected(validRequest(value, invalid), 403,
                "EXACT_HRM011_WORKLOAD_CLAIMS_REQUIRED");

        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(validRequest(value, claims(value)), accepted,
                (request, response) -> { });
        assertThat(accepted.getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(validRequest(value, claims(value)), limited,
                (request, response) -> {
                    throw new AssertionError("Rate-limited request reached the controller.");
                });
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("HRM011_WORKLOAD_RATE_LIMITED");
    }

    @Test
    void concurrencyLeaseSurvivesAsyncButTypedContextDoesNotLeak() throws Exception {
        filter = filter(new EmploymentContractRolloutGate(Set.of(TENANT)), 1, 10);
        RequestCase value = compensation();
        MockHttpServletRequest streaming = validRequest(value, claims(value));
        streaming.setAsyncSupported(true);
        MockHttpServletResponse streamingResponse = new MockHttpServletResponse();
        filter.doFilter(streaming, streamingResponse,
                (request, response) -> request.startAsync(request, response));

        assertThat(Hrm011WorkloadRequestContext.isVerifiedRequest(streaming)).isFalse();
        MockHttpServletResponse concurrent = new MockHttpServletResponse();
        filter.doFilter(validRequest(value, claims(value)), concurrent,
                (request, response) -> {
                    throw new AssertionError("Concurrent request reached the controller.");
                });
        assertThat(concurrent.getStatus()).isEqualTo(429);
        assertThat(concurrent.getContentAsString())
                .contains("HRM011_WORKLOAD_CONCURRENCY_LIMITED");

        streaming.getAsyncContext().complete();
        assertThat(Hrm011WorkloadRequestContext.isVerifiedRequest(streaming)).isFalse();
        MockHttpServletResponse afterCompletion = new MockHttpServletResponse();
        filter.doFilter(validRequest(value, claims(value)), afterCompletion,
                (request, response) -> { });
        assertThat(afterCompletion.getStatus()).isEqualTo(200);
    }

    private Hrm011WorkloadIngressFilter filter(
            EmploymentContractRolloutGate rollout,
            int concurrency,
            int requestsPerMinute) throws Exception {
        var trust = new Hrm011WorkloadTrustProfile(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), key.toRSAPublicKey(),
                key.getKeyID(), ISSUER,
                Map.of(TENANT, new Hrm011WorkloadTrustProfile.TenantGrant(3L, 9L)),
                rollout);
        return new Hrm011WorkloadIngressFilter(
                trust,
                new Hrm011WorkloadAdmissionControl(
                        Clock.fixed(NOW, ZoneOffset.UTC), concurrency, requestsPerMinute),
                mapper);
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
        assertThat(Hrm011WorkloadRequestContext.isVerifiedRequest(request)).isFalse();
    }

    private MockHttpServletRequest validRequest(
            RequestCase value, Map<String, Object> claims) throws Exception {
        MockHttpServletRequest request = baseRequest(value);
        request.addHeader("Authorization", "Bearer " + token(claims));
        return request;
    }

    private MockHttpServletRequest baseRequest(RequestCase value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", value.path());
        request.setSecure(true);
        request.setAttribute(Hrm011WorkloadTrustProfile.TLS_PROTOCOL_ATTRIBUTE, "TLSv1.3");
        request.setAttribute(Hrm011WorkloadTrustProfile.CERTIFICATE_ATTRIBUTE,
                new X509Certificate[]{certificate});
        request.addHeader("X-Correlation-ID", CORRELATION.toString());
        request.addParameter("purposeCode", value.purpose());
        request.addParameter("asOf", AS_OF.toString());
        request.addParameter("projection", value.projection());
        request.addParameter("limit", "100");
        if (value.cursor() != null) request.addParameter("cursor", value.cursor());
        return request;
    }

    private Map<String, Object> claims(RequestCase value) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", Hrm011WorkloadTrustProfile.EXPECTED_AUDIENCE);
        claims.put("sub", Hrm011WorkloadTrustProfile.EXPECTED_SUBJECT);
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
        claims.put("jti", ATTEMPT.toString());
        claims.put("correlationId", CORRELATION.toString());
        claims.put("scope", Hrm011WorkloadTrustProfile.EXPECTED_SCOPE);
        claims.put("bindingId", value.bindingId());
        claims.put("tenantId", TENANT);
        claims.put("tenantGrantRevision", 3L);
        claims.put("policyRevision", 9L);
        claims.put("purpose", value.purpose());
        claims.put("projection", value.projection());
        claims.put("consumerSession", Hrm011WorkloadTrustProfile.EXPECTED_CONSUMER_SESSION);
        claims.put("method", "GET");
        claims.put("path", value.path());
        claims.put("asOf", AS_OF.toString());
        claims.put("limit", 100);
        claims.put("cursor", value.cursor());
        return claims;
    }

    private String token(Map<String, Object> claims) throws Exception {
        JWSObject value = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(key.getKeyID())
                        .build(),
                new Payload(mapper.writeValueAsString(claims)));
        value.sign(new RSASSASigner(key));
        return value.serialize();
    }

    private RequestCase compensation() {
        return new RequestCase(
                Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
                Hrm011WorkloadIngressFilter.COMPENSATION_BINDING,
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Hrm011WorkloadIngress.COMPENSATION,
                null);
    }

    private record RequestCase(
            String path,
            String bindingId,
            String purpose,
            String projection,
            String cursor) {
    }
}
