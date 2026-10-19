package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationProofIssuerV1;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.Per004TestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthPer004FeatureDecisionClientTest {
    static KeyPair pair;
    static final URI ENDPOINT = URI.create("https://auth.internal" + Endpoint.FEATURE.path(null));
    @BeforeAll static void key() throws Exception { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair = generator.generateKeyPair(); }

    @Test void exactFlatAuthWireIsSignedAndResponseTupleIsRevalidated() throws Exception {
        var binding = binding();
        var captured = new AtomicReference<HttpRequest>();
        HttpClient http = http(MAPPER.writeValueAsBytes(active(binding)), 200, captured);
        var client = client(http);
        assertThat(client.decide(binding)).isEqualTo(active(binding));
        HttpRequest request = captured.get();
        assertThat(request.uri()).isEqualTo(ENDPOINT);
        assertThat(request.timeout()).contains(java.time.Duration.ofSeconds(2));
        byte[] bytes = body(request);
        var json = MAPPER.readTree(bytes);
        assertThat(json.has("binding")).isFalse();
        assertThat(json.get("tenantId").asLong()).isEqualTo(42);
        assertThat(json.get("commandAuthorizationRevision").asLong()).isEqualTo(binding.commandAuthorizationRevision());
        var responseJson = MAPPER.valueToTree(active(binding));
        assertThat(responseJson.has("binding")).isFalse();
        assertThat(responseJson.get("subjectPrincipalPublicId").asText()).isEqualTo(binding.subjectPrincipalPublicId().toString());
        var admitted = verify(MAPPER, CLOCK, Endpoint.FEATURE, Endpoint.FEATURE.path(null),
                headers("dwp-platform-server", request.headers().firstValue(WORKLOAD_PROOF_HEADER).orElseThrow()), bytes,
                kid -> new VerifiedSigningKey(pair.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, "dwp-platform-server"),
                (e,p,caller,tenant) -> tenant == 42, (id,nonce,exp) -> true);
        assertThat(admitted.claims().purpose()).isEqualTo("FEATURE_POLICY_LOOKUP_ONLY");
        assertThat(request.headers().map()).doesNotContainKeys("Authorization", "Cookie", "X-DWP-Service-Token", "X-DWP-Tenant-ID");
    }

    @Test void missingSignerOrTargetConfigurationMakesZeroNetworkCallsAndNoStaticTokenFallback() {
        var http = mock(HttpClient.class); when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var missingSigner = new AuthPer004FeatureDecisionClient(MAPPER, null, ENDPOINT, http, CLOCK);
        assertThatThrownBy(() -> missingSigner.decide(binding())).hasMessage("PER004_FEATURE_DECISION_UNAVAILABLE");
        var missingTarget = new AuthPer004FeatureDecisionClient(MAPPER, e -> signingKey(), null, http, CLOCK);
        assertThatThrownBy(() -> missingTarget.decide(binding())).hasMessage("PER004_FEATURE_DECISION_UNAVAILABLE");
        verify(http, never()).sendAsync(any(), any());
        for (String base : new String[]{"http://auth.internal", "https://user:password@auth.internal", "https://auth.internal/root",
                "https://auth.internal?tenant=42", "https://auth.internal#fragment"}) {
            assertThatThrownBy(() -> AuthPer004FeatureDecisionClient.endpoint(base)).hasMessage("PER004_FEATURE_DECISION_UNAVAILABLE");
        }
        assertThat(AuthPer004FeatureDecisionClient.endpoint("https://auth.internal/")).isEqualTo(ENDPOINT);
    }

    @Test void wrongOffUngatedStaleRedirectMalformedOversizedAndDuplicateResponsesFailClosed() throws Exception {
        FeatureRequest b = binding();
        var wrong = new FeatureRequest(43, b.subjectPrincipalPublicId(), b.exactSharedActionRouteContractKey(),
                b.configurationId(), "PER", b.scopeDigest(), b.commandAuthorizationRevision(), b.correlationId());
        for (FeatureDecision decision : List.of(active(wrong),
                new FeatureDecision(b, FEATURE, FEATURE_ROUTE, "OFF", "ALLOWED", "rev", NOW, NOW.plusSeconds(30)),
                new FeatureDecision(b, FEATURE, FEATURE_ROUTE, "ACTIVE", "UNGATED", "rev", NOW, NOW.plusSeconds(30)),
                new FeatureDecision(b, FEATURE, FEATURE_ROUTE, "ACTIVE", "ALLOWED", "rev", NOW.minusMinutes(1), NOW.minusSeconds(1)))) {
            assertThatThrownBy(() -> client(http(MAPPER.writeValueAsBytes(decision), 200, new AtomicReference<>())).decide(b))
                    .hasMessage("PER004_FEATURE_DECISION_UNAVAILABLE").hasNoCause();
        }
        for (byte[] bytes : List.of("not-json-secret".getBytes(StandardCharsets.UTF_8), new byte[65537],
                MAPPER.writeValueAsString(active(b)).replaceFirst("\\{", "{\"tenantId\":43,").getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> client(http(bytes, 200, new AtomicReference<>())).decide(b))
                    .hasMessage("PER004_FEATURE_DECISION_UNAVAILABLE").hasNoCause();
        }
        assertThatThrownBy(() -> client(http(MAPPER.writeValueAsBytes(active(b)), 302, new AtomicReference<>())).decide(b))
                .hasMessage("PER004_FEATURE_DECISION_UNAVAILABLE");
        var redirects = mock(HttpClient.class); when(redirects.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThatThrownBy(() -> client(redirects)).hasMessage("PER004_FEATURE_DECISION_UNAVAILABLE");
    }

    @SuppressWarnings("unchecked")
    private HttpClient http(byte[] responseBody, int status, AtomicReference<HttpRequest> captured) {
        var client = mock(HttpClient.class); when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap(responseBody))); subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture().thenApply(bytes -> {
                HttpResponse<byte[]> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(bytes);
                when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a,b) -> true));
                return response;
            });
        });
        return client;
    }
    private AuthPer004FeatureDecisionClient client(HttpClient http) { return new AuthPer004FeatureDecisionClient(MAPPER, e -> signingKey(), ENDPOINT, http, CLOCK); }
    private HrisReferenceConfigurationProofIssuerV1.SigningKey signingKey() {
        return new HrisReferenceConfigurationProofIssuerV1.SigningKey("per004-test", pair.getPrivate(), "dwp-platform-server", WorkloadAuthentication.CLIENT_CREDENTIAL_JWT);
    }
    private FeatureRequest binding() { return new FeatureRequest(42, UUID.randomUUID(), "route.hcm.configuration.configuration-version-create.action", UUID.randomUUID(), "PER", SCOPE, access(42, 101).authorizationRevision(), UUID.randomUUID()); }
    private byte[] body(HttpRequest request) {
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] value = new byte[b.remaining()]; b.get(value); bytes.writeBytes(value); }
            public void onError(Throwable t) { throw new AssertionError(t); }
            public void onComplete() { }
        }); return bytes.toByteArray();
    }
}
