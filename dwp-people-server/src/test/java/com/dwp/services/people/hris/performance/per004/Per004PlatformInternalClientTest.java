package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationProofIssuerV1;
import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Per004PlatformInternalClientTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
    private static final URI BASE = URI.create("https://platform.internal");
    private static final String SCOPE = "a".repeat(64), DIGEST = "b".repeat(64);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static KeyPair pair;
    private final UUID configuration = UUID.randomUUID(), artifact = UUID.randomUUID();

    @BeforeAll static void key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
    }

    @Test void exactProjectionAndCreateLookupPostWiresCarryDistinctSignedBindings() throws Exception {
        var projection = new OwnerProjectionResponse(NOW, List.of(row(configuration)), "CURRENT", null);
        var projectionRequest = request(configuration);
        var captured = new AtomicReference<HttpRequest>();
        var client = client(http(MAPPER.writeValueAsBytes(projection), 200,
                "application/json", captured));
        assertThat(client.query(42, projectionRequest)).isEqualTo(projection);
        assertProof(captured.get(), Endpoint.PROJECTION, 42);
        assertThat(MAPPER.readTree(body(captured.get())).path("targets").get(0)
                .path("configurationId").asText()).isEqualTo(configuration.toString());

        var identity = identity(configuration);
        var lookup = new CreateLookupResponse(identity, LookupState.ABSENT_FINAL,
                null, null, UUID.randomUUID(), "c".repeat(64), NOW, "signed-exact");
        client = client(http(MAPPER.writeValueAsBytes(lookup), 200, "application/json", captured));
        assertThat(client.query(new CreateLookupRequest(identity))).isEqualTo(lookup);
        assertProof(captured.get(), Endpoint.CREATE_LOOKUP, 42);
        assertThat(MAPPER.readTree(body(captured.get())).path("identity")
                .path("createRequestDigest").asText()).isEqualTo(DIGEST);
    }

    @Test void noSignerUnsafeEndpointAndNetworkFailuresNeverFallBackToBrowserAuthority() throws Exception {
        for (String base : List.of("http://platform.internal", "https://user:pass@platform.internal",
                "https://platform.internal/root", "https://platform.internal?x=1",
                "https://platform.internal#fragment")) {
            assertThatThrownBy(() -> Per004PlatformInternalClient.endpoint(URI.create(base),
                    Endpoint.PROJECTION)).hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
        }
        HttpClient mocked = mock(HttpClient.class);
        when(mocked.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var noSigner = new Per004PlatformInternalClient(MAPPER, null, BASE, mocked, CLOCK);
        assertThatThrownBy(() -> noSigner.query(42, request(configuration)))
                .hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
        verify(mocked, never()).sendAsync(any(), any());

        byte[] valid = MAPPER.writeValueAsBytes(new OwnerProjectionResponse(NOW,
                List.of(row(configuration)), "CURRENT", null));
        for (byte[] wire : List.of("not-json-secret".getBytes(StandardCharsets.UTF_8),
                new byte[262145],
                (new String(valid, StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8),
                new String(valid, StandardCharsets.UTF_8).replaceFirst("\\{", "{\"extra\":true,")
                        .getBytes(StandardCharsets.UTF_8),
                new String(valid, StandardCharsets.UTF_8).replaceFirst("\\{", "{\"asOf\":\"2026-10-19T00:00Z\",")
                        .getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> client(http(wire, 200, "application/json", new AtomicReference<>()))
                    .query(42, request(configuration)))
                    .hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE").hasNoCause();
        }
        assertThatThrownBy(() -> client(http(valid, 302, "application/json", new AtomicReference<>()))
                .query(42, request(configuration))).hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
        assertThatThrownBy(() -> client(http(valid, 200, "text/html", new AtomicReference<>()))
                .query(42, request(configuration))).hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
        when(mocked.sendAsync(any(), any())).thenReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> client(mocked).query(42, request(configuration)))
                .hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
    }

    @Test void mismatchedProjectionAndLookupResponsesFailClosed() throws Exception {
        var wrong = new OwnerProjectionResponse(NOW, List.of(row(UUID.randomUUID())), "CURRENT", null);
        assertThatThrownBy(() -> client(http(MAPPER.writeValueAsBytes(wrong), 200,
                "application/json", new AtomicReference<>())).query(42, request(configuration)))
                .hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
        var identity = identity(configuration);
        var other = identity(UUID.randomUUID());
        var wrongLookup = new CreateLookupResponse(other, LookupState.ABSENT_FINAL,
                null, null, UUID.randomUUID(), "c".repeat(64), NOW, "signed-exact");
        assertThatThrownBy(() -> client(http(MAPPER.writeValueAsBytes(wrongLookup), 200,
                "application/json", new AtomicReference<>())).query(new CreateLookupRequest(identity)))
                .hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
        var unfenced = new CreateLookupResponse(identity, LookupState.ABSENT_FINAL,
                null, null, null, "c".repeat(64), NOW, "signed-exact");
        assertThatThrownBy(() -> client(http(MAPPER.writeValueAsBytes(unfenced), 200,
                "application/json", new AtomicReference<>())).query(new CreateLookupRequest(identity)))
                .hasMessage("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
    }

    private Per004PlatformInternalClient client(HttpClient http) {
        return new Per004PlatformInternalClient(MAPPER, endpoint ->
                new HrisReferenceConfigurationProofIssuerV1.SigningKey("per004-test", pair.getPrivate(),
                        "dwp-people-server", WorkloadAuthentication.CLIENT_CREDENTIAL_JWT), BASE, http, CLOCK);
    }
    private OwnerProjectionRequest request(UUID config) {
        return new OwnerProjectionRequest(NOW, List.of(new ProjectionTarget(config, SCOPE)),
                null, 100, Set.of("dwp.hris.performance.policy.v1"),
                new OwnerProjectionFilter(Set.of(), null), List.of("configurationId:ASC"));
    }
    private OwnerProjection row(UUID config) {
        return new OwnerProjection(config, 7L, 11L, "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE",
                "dwp.hris.performance.policy.v1", SCOPE, NOW, null,
                true, true, "performance.policy", null, 1,
                "ref_per_artifact_" + artifact, DIGEST);
    }
    private CreateIdentity identity(UUID config) {
        return new CreateIdentity(42, config, UUID.randomUUID(), "configuration.version.create",
                "per004-key-123456789", DIGEST, UUID.randomUUID());
    }
    private void assertProof(HttpRequest request, Endpoint endpoint, long tenant) throws Exception {
        assertThat(request.uri()).isEqualTo(BASE.resolve(endpoint.path(null)));
        assertThat(request.timeout()).contains(java.time.Duration.ofSeconds(2));
        assertThat(request.headers().map()).doesNotContainKeys("Authorization", "Cookie",
                "X-DWP-Service-Token", "X-DWP-Tenant-ID", "X-DWP-User-ID");
        var admitted = HrisReferenceConfigurationWorkloadV1.verify(MAPPER, CLOCK, endpoint,
                endpoint.path(null), List.of(new Header("Content-Type", "application/json"),
                    new Header(SERVICE_IDENTITY_HEADER, "dwp-people-server"),
                    new Header(WORKLOAD_PROOF_HEADER,
                            request.headers().firstValue(WORKLOAD_PROOF_HEADER).orElseThrow())), body(request),
                kid -> new VerifiedSigningKey(pair.getPublic(),
                        WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, "dwp-people-server"),
                (e, path, caller, id) -> id == tenant, (id, nonce, expires) -> true);
        assertThat(admitted.claims().bindingId()).isEqualTo(endpoint.bindingId());
    }
    private static byte[] body(HttpRequest request) {
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer b) {
                byte[] value = new byte[b.remaining()]; b.get(value); bytes.writeBytes(value);
            }
            @Override public void onError(Throwable t) { throw new AssertionError(t); }
            @Override public void onComplete() { }
        });
        return bytes.toByteArray();
    }
    @SuppressWarnings("unchecked") private HttpClient http(byte[] responseBody, int status,
            String contentType, AtomicReference<HttpRequest> captured) {
        HttpClient http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap(responseBody)));
            subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture().thenApply(bytes -> {
                HttpResponse<byte[]> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(status);
                when(response.body()).thenReturn(bytes);
                when(response.headers()).thenReturn(HttpHeaders.of(
                        Map.of("Content-Type", List.of(contentType)), (a, b) -> true));
                return response;
            });
        });
        return http;
    }
}
