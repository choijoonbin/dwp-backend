package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DwaionHomeWidgetProviderClientTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String BINDING_CATALOG_REVISION = "9".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void asyncCallsUseExplicitContextAndNeverLeakTokenOrPriorTraceHeaders() throws Exception {
        List<Captured> captured = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/home/v1/widget-data:batch", exchange ->
                respond(exchange, captured));
        server.start();
        try {
            DwaionHomeWidgetProviderClient client = client(server);
            HomeRuntimeContext first = context("corr-first", "00-" + "1".repeat(32)
                    + "-" + "2".repeat(16) + "-01", "vendor=first");
            HomeRuntimeContext second = context("corr-second", null, null);

            invokeWithAmbientHeaders(client, first, "ambient-first");
            invokeWithAmbientHeaders(client, second, "ambient-second");

            assertThat(captured).hasSize(2);
            assertCaptured(captured.get(0), first);
            assertCaptured(captured.get(1), second);
            assertThat(captured.get(1).traceparent()).isNull();
            assertThat(captured.get(1).tracestate()).isNull();
            assertThat(captured).allSatisfy(call -> {
                assertThat(call.serviceToken()).isNull();
                assertThat(call.serviceIdentity()).isNull();
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsupportedVersionManifestOrMalformedCatalogBinding() {
        DwaionHomeWorkloadAssertionSigner signer = new DwaionHomeWorkloadAssertionSigner(
                "platform-dwaion-home-v1", SECRET, mapper);
        DwaionHomeWidgetProviderClient client = new DwaionHomeWidgetProviderClient(
                "http://127.0.0.1:1", Duration.ofMillis(100), 262_144,
                RestClient.builder(), mapper,
                signer, CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
        HomeRuntimeContext context = context("corr-allowlist", null, null);

        for (WidgetProviderPort.Request request : List.of(
                request("1.0.0", DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH,
                        BINDING_CATALOG_REVISION),
                request(DwaionHomeWorkloadProtocol.DEFINITION_VERSION, "b".repeat(64),
                        BINDING_CATALOG_REVISION),
                request(DwaionHomeWorkloadProtocol.DEFINITION_VERSION,
                        DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH, "old-binding"))) {
            assertThatThrownBy(() -> client.readBatch(
                    context, List.of(request), deadline(context)))
                    .isInstanceOfSatisfying(WidgetProviderException.class, failure ->
                            assertThat(failure.reasonCode())
                                    .isEqualTo("DEFINITION_NOT_SUPPORTED"));
        }
    }

    @Test
    void rejectsMissingScopeAndOversizedSignedBodyBeforeTransport() {
        DwaionHomeWorkloadAssertionSigner signer = new DwaionHomeWorkloadAssertionSigner(
                "platform-dwaion-home-v1", SECRET, mapper);
        DwaionHomeWidgetProviderClient client = new DwaionHomeWidgetProviderClient(
                "http://127.0.0.1:1", Duration.ofMillis(100), 262_144,
                RestClient.builder(), mapper,
                signer, CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
        HomeRuntimeContext missingAsk = HomeRuntimeContext.create(
                71L, 82L, null, "APP.DWAION_ARTIFACTS:VIEW", "MEMBER", "team-a",
                "decision-17", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString(),
                "ko-KR", "Asia/Seoul", "corr-missing-scope", null, null);

        assertThatThrownBy(() -> client.readBatch(
                missingAsk, List.of(request()), deadline(missingAsk)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(WidgetProviderException.Kind.FORBIDDEN);
                    assertThat(failure.reasonCode())
                            .isEqualTo("AUTHORIZATION_DWAION_ARTIFACT_REQUIRED");
                });

        HomeRuntimeContext context = context("corr-oversized", null, null);
        WidgetProviderPort.Request base = request();
        WidgetProviderPort.Request oversized = new WidgetProviderPort.Request(
                base.instanceId(), base.definition(),
                Map.of("oversized", "x".repeat(262_144)), base.itemLimit());
        assertThatThrownBy(() -> client.readBatch(
                context, List.of(oversized), deadline(context)))
                .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(WidgetProviderException.Kind.MALFORMED);
                    assertThat(failure.reasonCode())
                            .isEqualTo("PROVIDER_REQUEST_OUT_OF_BOUNDS");
                });
    }

    @Test
    void boundsSuccessfulResponseBeforeDeserializationAndClassifiesForbiddenSafely()
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/home/v1/widget-data:batch", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                boolean forbidden = "corr-forbidden".equals(
                        exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
                exchange.sendResponseHeaders(forbidden ? 403 : 200, 0);
                exchange.getResponseBody().write("x".repeat(262_145)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException ignored) {
                // The bounded client may close the stream before the fixture finishes writing.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            DwaionHomeWidgetProviderClient client = client(server);
            HomeRuntimeContext oversized = context("corr-oversized-response", null, null);
            assertThatThrownBy(() -> client.readBatch(
                    oversized, List.of(request()), deadline(oversized)))
                    .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                        assertThat(failure.kind())
                                .isEqualTo(WidgetProviderException.Kind.MALFORMED);
                        assertThat(failure.reasonCode())
                                .isEqualTo("PROVIDER_RESPONSE_OUT_OF_BOUNDS");
                    });

            HomeRuntimeContext forbidden = context("corr-forbidden", null, null);
            assertThatThrownBy(() -> client.readBatch(
                    forbidden, List.of(request()), deadline(forbidden)))
                    .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                        assertThat(failure.kind())
                                .isEqualTo(WidgetProviderException.Kind.FORBIDDEN);
                        assertThat(failure.reasonCode())
                                .isEqualTo("AUTHORIZATION_PROVIDER_FORBIDDEN");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPayloadOutsideTitleProjectionAndUndeclaredCommandAction() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/home/v1/widget-data:batch",
                this::respondWithProjectionViolation);
        server.start();
        try {
            DwaionHomeWidgetProviderClient client = client(server);
            for (String correlation : List.of(
                    "corr-private-payload", "corr-command-action",
                    "corr-enum-drift", "corr-duplicate-artifact")) {
                HomeRuntimeContext context = context(correlation, null, null);
                assertThatThrownBy(() -> client.readBatch(
                        context, List.of(request()), deadline(context)))
                        .isInstanceOfSatisfying(WidgetProviderException.class, failure -> {
                            assertThat(failure.kind())
                                    .isEqualTo(WidgetProviderException.Kind.MALFORMED);
                            assertThat(failure.reasonCode())
                                    .isEqualTo("PROVIDER_PROJECTION_INVALID");
                        });
            }
        } finally {
            server.stop(0);
        }
    }

    private void invokeWithAmbientHeaders(
            DwaionHomeWidgetProviderClient client,
            HomeRuntimeContext context,
            String ambient) {
        CompletableFuture.supplyAsync(() -> {
            MockHttpServletRequest stale = new MockHttpServletRequest();
            stale.addHeader("X-Correlation-ID", ambient);
            stale.addHeader("traceparent", "00-" + "f".repeat(32)
                    + "-" + "e".repeat(16) + "-01");
            stale.addHeader("tracestate", "vendor=ambient");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(stale));
            try {
                return client.readBatch(context, List.of(request()), deadline(context));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }).join();
    }

    private void assertCaptured(Captured call, HomeRuntimeContext context) throws Exception {
        assertThat(call.correlationId()).isEqualTo(context.correlationId());
        assertThat(call.traceparent()).isEqualTo(context.traceparent());
        assertThat(call.tracestate()).isEqualTo(context.tracestate());
        String[] segments = call.assertion().split("\\.");
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(segments[1]));
        assertThat(claims.get("cid").asText()).isEqualTo(context.correlationId());
        if (context.traceparent() == null) {
            assertThat(claims.get("traceparent").isNull()).isTrue();
        } else {
            assertThat(claims.get("traceparent").asText()).isEqualTo(context.traceparent());
        }
        if (context.tracestate() == null) {
            assertThat(claims.get("tracestate").isNull()).isTrue();
        } else {
            assertThat(claims.get("tracestate").asText()).isEqualTo(context.tracestate());
        }
        assertThat(claims.get("bodySha256").asText()).isEqualTo(
                java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(call.body())));
    }

    private DwaionHomeWidgetProviderClient client(HttpServer server) {
        DwaionHomeWorkloadAssertionSigner signer = new DwaionHomeWorkloadAssertionSigner(
                "platform-dwaion-home-v1", SECRET, mapper);
        return new DwaionHomeWidgetProviderClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(1), 262_144, RestClient.builder(), mapper, signer,
                CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
    }

    private void respond(HttpExchange exchange, List<Captured> captured) {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode request = mapper.readTree(body);
            UUID instanceId = UUID.fromString(
                    request.get("widgets").get(0).get("instanceId").asText());
            String bindingCatalogRevision = request.get("widgets").get(0)
                    .get("rendererBindingRevision").asText();
            captured.add(new Captured(
                    body,
                    exchange.getRequestHeaders().getFirst("X-DWP-Home-Assertion"),
                    exchange.getRequestHeaders().getFirst("X-Correlation-ID"),
                    exchange.getRequestHeaders().getFirst("traceparent"),
                    exchange.getRequestHeaders().getFirst("tracestate"),
                    exchange.getRequestHeaders().getFirst("X-DWP-Service-Token"),
                    exchange.getRequestHeaders().getFirst("X-DWP-Service-Identity")));
            HomeRuntimeContext context = "corr-first".equals(
                    exchange.getRequestHeaders().getFirst("X-Correlation-ID"))
                    ? context("corr-first", "00-" + "1".repeat(32)
                            + "-" + "2".repeat(16) + "-01", "vendor=first")
                    : context("corr-second", null, null);
            OffsetDateTime generatedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
            OffsetDateTime expiresAt = OffsetDateTime.parse(
                    exchange.getRequestHeaders().getFirst("X-DWP-Home-Deadline-At"));
            String response = "{\"schemaVersion\":1,\"tenantId\":71,\"userId\":82,"
                    + "\"authorityDecisionRevision\":\"" + context.authorityDecisionRevision()
                    + "\",\"results\":[{\"instanceId\":\"" + instanceId
                    + "\",\"definitionKey\":\"dwaion.artifact\","
                    + "\"definitionManifestHash\":\""
                    + DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH + "\","
                    + "\"rendererBindingRevision\":\""
                    + bindingCatalogRevision + "\","
                    + "\"state\":\"AVAILABLE\",\"source\":{\"sourceKey\":\"DWAION_HOME\","
                    + "\"generatedAt\":\"" + generatedAt + "\",\"expiresAt\":\""
                    + expiresAt + "\",\"lastSuccessAt\":\"" + generatedAt
                    + "\",\"reasonCode\":null,\"retryable\":false,"
                    + "\"resultVersion\":\"v1:" + "a".repeat(32) + "\"},"
                    + "\"payload\":{\"visibleCount\":1,\"items\":[{\"artifactId\":\""
                    + UUID.randomUUID() + "\",\"title\":\"safe title\","
                    + "\"artifactType\":\"DOCUMENT\",\"state\":\"DRAFT\","
                    + "\"revision\":1,\"updatedAt\":\"" + generatedAt + "\"}]},"
                    + "\"actions\":[{\"actionId\":\"open-source\","
                    + "\"labelKey\":\"home.action.openSource\",\"kind\":\"SOURCE_ROUTE\","
                    + "\"sourceRoute\":\"/dwaion/artifacts\",\"commandKey\":null,"
                    + "\"expectedResultVersion\":null,\"requiresConfirmation\":false}],"
                    + "\"redactions\":[]}]}";
            byte[] encoded = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void respondWithProjectionViolation(HttpExchange exchange) {
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode widget = request.get("widgets").get(0);
            String correlation = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
            OffsetDateTime generatedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
            OffsetDateTime expiresAt = OffsetDateTime.parse(
                    exchange.getRequestHeaders().getFirst("X-DWP-Home-Deadline-At"));
            boolean privatePayload = "corr-private-payload".equals(correlation);
            boolean commandAction = "corr-command-action".equals(correlation);
            String artifactId = UUID.randomUUID().toString();
            String artifactType = "corr-enum-drift".equals(correlation)
                    ? "REPORT" : "DOCUMENT";
            String item = "{\"artifactId\":\"" + artifactId
                    + "\",\"title\":\"safe title\",\"artifactType\":\""
                    + artifactType + "\",\"state\":\"DRAFT\",\"revision\":1,"
                    + "\"updatedAt\":\"" + generatedAt + "\""
                    + (privatePayload ? ",\"body\":\"private\"" : "") + "}";
            String payload = commandAction ? "{}"
                    : "{\"visibleCount\":2,\"items\":[" + item
                    + ("corr-duplicate-artifact".equals(correlation) ? "," + item : "")
                    + "]}";
            String actions = !commandAction
                    ? "[{\"actionId\":\"open-source\",\"labelKey\":\"home.action.openSource\","
                    + "\"kind\":\"SOURCE_ROUTE\",\"sourceRoute\":\"/dwaion/artifacts\","
                    + "\"commandKey\":null,\"expectedResultVersion\":null,"
                    + "\"requiresConfirmation\":false}]"
                    : "[{\"actionId\":\"run\",\"labelKey\":\"home.action.run\","
                    + "\"kind\":\"COMMAND\",\"sourceRoute\":null,"
                    + "\"commandKey\":\"dwaion.run\",\"expectedResultVersion\":\"v1:"
                    + "a".repeat(32) + "\",\"requiresConfirmation\":true}]";
            String response = "{\"schemaVersion\":1,\"tenantId\":71,\"userId\":82,"
                    + "\"authorityDecisionRevision\":\"decision-17\",\"results\":[{"
                    + "\"instanceId\":\"" + widget.get("instanceId").asText() + "\","
                    + "\"definitionKey\":\"dwaion.artifact\","
                    + "\"definitionManifestHash\":\""
                    + DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH + "\","
                    + "\"rendererBindingRevision\":\""
                    + widget.get("rendererBindingRevision").asText() + "\","
                    + "\"state\":\"" + (commandAction ? "EMPTY" : "AVAILABLE") + "\","
                    + "\"source\":{\"sourceKey\":\"DWAION_HOME\",\"generatedAt\":\""
                    + generatedAt + "\",\"expiresAt\":\"" + expiresAt
                    + "\",\"lastSuccessAt\":\"" + generatedAt
                    + "\",\"reasonCode\":null,\"retryable\":false,"
                    + "\"resultVersion\":\"v1:" + "a".repeat(32) + "\"},"
                    + "\"payload\":" + payload + ",\"actions\":" + actions
                    + ",\"redactions\":[]}]}";
            byte[] encoded = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private HomeRuntimeContext context(
            String correlation, String traceparent, String tracestate) {
        return HomeRuntimeContext.create(
                71L, 82L, null, "APP.ASK:VIEW,APP.DWAION_ARTIFACTS:VIEW",
                "MEMBER", "team-a", "decision-17",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString(),
                "ko-KR", "Asia/Seoul", correlation, traceparent, tracestate);
    }

    private OffsetDateTime deadline(HomeRuntimeContext context) {
        OffsetDateTime value = OffsetDateTime.now(ZoneOffset.UTC).plus(Duration.ofMillis(500));
        return value.isBefore(context.authorityRevalidateAt())
                ? value : context.authorityRevalidateAt().minusNanos(1);
    }

    private WidgetProviderPort.Request request() {
        return request(
                DwaionHomeWorkloadProtocol.DEFINITION_VERSION,
                DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH,
                BINDING_CATALOG_REVISION);
    }

    private WidgetProviderPort.Request request(
            String version, String manifestHash, String bindingRevision) {
        WidgetCatalogService.RuntimeDefinition definition =
                new WidgetCatalogService.RuntimeDefinition(
                        UUID.randomUUID(), "dwaion.artifact", "dwaion-artifact",
                        UUID.randomUUID(), version, manifestHash, bindingRevision,
                        "home.dwaion.artifact", "ai.agent-runtime",
                        "APP.DWAION_ARTIFACTS",
                        List.of("APP.ASK:VIEW", "APP.DWAION_ARTIFACTS:VIEW"),
                        "CONFIDENTIAL", "NONE", 30,
                        WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE, List.of());
        return new WidgetProviderPort.Request(UUID.randomUUID(), definition, Map.of(), 3);
    }

    private record Captured(
            byte[] body,
            String assertion,
            String correlationId,
            String traceparent,
            String tracestate,
            String serviceToken,
            String serviceIdentity) {
    }
}
