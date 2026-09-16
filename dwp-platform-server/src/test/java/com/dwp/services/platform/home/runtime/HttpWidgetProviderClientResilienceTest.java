package com.dwp.services.platform.home.runtime;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HttpWidgetProviderClientResilienceTest {

    @Test
    void repeatedOwnerFailureOpensCircuitAndSuppressesTheNextNetworkCall() throws Exception {
        try (Fixture fixture = fixture(500)) {
            WidgetProviderException first = failure(fixture.client());
            WidgetProviderException second = failure(fixture.client());

            assertThat(first.kind()).isEqualTo(WidgetProviderException.Kind.UNAVAILABLE);
            assertThat(first.reasonCode()).isEqualTo("PROVIDER_HTTP_5XX");
            assertThat(second.kind()).isEqualTo(WidgetProviderException.Kind.UNAVAILABLE);
            assertThat(second.reasonCode()).isEqualTo("PROVIDER_CIRCUIT_OPEN");
            assertThat(fixture.calls()).hasValue(1);
            assertThat(fixture.circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Test
    void recipientForbiddenIsNotCountedAsAnOwnerAvailabilityFailure() throws Exception {
        try (Fixture fixture = fixture(403)) {
            WidgetProviderException first = failure(fixture.client());
            WidgetProviderException second = failure(fixture.client());

            assertThat(first.kind()).isEqualTo(WidgetProviderException.Kind.FORBIDDEN);
            assertThat(second.kind()).isEqualTo(WidgetProviderException.Kind.FORBIDDEN);
            assertThat(fixture.calls()).hasValue(2);
            assertThat(fixture.circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Test
    void saturatedOwnerBulkheadRejectsWithoutStartingAnotherNetworkCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            requestStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        BulkheadRegistry bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
        HttpWidgetProviderClient client = new HttpWidgetProviderClient(
                "meeting",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-service-token",
                Duration.ofSeconds(2),
                RestClient.builder(),
                CircuitBreakerRegistry.ofDefaults(),
                bulkheads);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<WidgetProviderException> occupied = CompletableFuture.supplyAsync(
                    () -> failure(client), executor);
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();

            WidgetProviderException rejected = failure(client);

            assertThat(rejected.kind()).isEqualTo(WidgetProviderException.Kind.UNAVAILABLE);
            assertThat(rejected.reasonCode()).isEqualTo("PROVIDER_BULKHEAD_FULL");
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(occupied.get(2, TimeUnit.SECONDS).reasonCode())
                    .isEqualTo("PROVIDER_HTTP_5XX");
        } finally {
            release.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    private WidgetProviderException failure(HttpWidgetProviderClient client) {
        HomeRuntimeContext context = TestFixtures.context();
        return (WidgetProviderException) org.assertj.core.api.Assertions.catchThrowable(() ->
                client.readBatch(
                        context,
                        List.of(TestFixtures.request("meetings.next-prep")),
                        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1)));
    }

    private Fixture fixture(int status) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.of(config);
        HttpWidgetProviderClient client = new HttpWidgetProviderClient(
                "meeting",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-service-token",
                Duration.ofMillis(250),
                RestClient.builder(),
                circuits,
                BulkheadRegistry.ofDefaults());
        return new Fixture(server, client, calls,
                circuits.circuitBreaker("homeRuntime-meeting"));
    }

    private record Fixture(
            HttpServer server,
            HttpWidgetProviderClient client,
            AtomicInteger calls,
            CircuitBreaker circuit) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
