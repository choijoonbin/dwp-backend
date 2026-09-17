package com.dwp.services.platform.dwaion;

import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository.Delivery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformDwaionHandoffObserverClientTest {

    @ParameterizedTest
    @MethodSource("ownerDomains")
    void postsAnExactlyTypedCompletionReceipt(
            String actionKey,
            String domain,
            String operation,
            String status,
            String resourceField,
            String versionField) throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> delegatedIdentity = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        UUID handoffId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        server.createContext("/internal/v1/proposal-handoffs/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            delegatedIdentity.set(exchange.getRequestHeaders()
                    .getFirst("X-DWP-Delegated-Identity"));
            byte[] response = ("{\"data\":{"
                    + "\"handoffId\":\"" + handoffId + "\","
                    + "\"proposalId\":\"" + proposalId + "\","
                    + "\"actionKey\":\"" + actionKey + "\","
                    + "\"state\":\"COMPLETED\",\"version\":4,"
                    + "\"receiptId\":\"" + receiptId + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            UUID resourceId = UUID.randomUUID();
            Delivery delivery = delivery(
                    handoffId, proposalId, actionKey, domain, operation,
                    resourceId, status);
            var client = new PlatformDwaionHandoffObserverClient(
                    HttpClient.newHttpClient(), json,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "service-token", "worker-token",
                    "0123456789abcdef0123456789abcdef", "gateway-agent-v1",
                    Clock.fixed(Instant.parse("2026-09-17T01:00:00Z"), ZoneOffset.UTC),
                    () -> UUID.fromString("00000000-0000-0000-0000-000000000099"));

            var observed = client.observe(delivery);

            assertThat(observed.receiptId()).isEqualTo(receiptId);
            JsonNode request = json.readTree(body.get());
            JsonNode receipt = request.path("receipt");
            assertThat(request.path("expectedVersion").asLong()).isEqualTo(3);
            assertThat(request.path("state").asText()).isEqualTo("COMPLETED");
            assertThat(receipt.path("domain").asText()).isEqualTo(domain);
            assertThat(receipt.path("operation").asText()).isEqualTo(operation);
            assertThat(receipt.path("actionKey").asText()).isEqualTo(actionKey);
            assertThat(receipt.path(resourceField).asText()).isEqualTo(resourceId.toString());
            assertThat(receipt.path(versionField).asLong()).isZero();
            assertThat(receipt.path("handoffVersion").asLong()).isEqualTo(3);
            assertThat(receipt.path("status").asText()).isEqualTo(status);
            assertThat(delegatedIdentity.get()).hasSizeGreaterThan(100).contains(".");
        } finally {
            server.stop(0);
        }
    }

    private static Delivery delivery(
            UUID handoffId,
            UUID proposalId,
            String actionKey,
            String domain,
            String operation,
            UUID resourceId,
            String status) {
        return new Delivery(
                UUID.randomUUID(), 91, 17, UUID.randomUUID(),
                handoffId, proposalId, actionKey, 3,
                "session-17", "WORKSPACE_MEMBER", "APP.ASK:VIEW",
                "owner-completion-17", "COMPLETED", domain, operation,
                resourceId, status, 0, Instant.parse("2026-09-17T00:59:00Z"),
                1, "worker-1", Instant.parse("2026-09-17T01:01:00Z"));
    }

    private static Stream<Arguments> ownerDomains() {
        return Stream.of(
                Arguments.of(
                        "CALENDAR.EVENT.CREATE", "CALENDAR", "EVENT_CREATE", "CONFIRMED",
                        "eventId", "eventVersion"),
                Arguments.of(
                        "MAIL.DRAFT.CREATE", "MAIL", "DRAFT_CREATE", "DRAFT",
                        "threadId", "threadVersion"),
                Arguments.of(
                        "SERVICE.REQUEST.CREATE", "SERVICE", "REQUEST_CREATE", "SUBMITTED",
                        "requestId", "requestVersion"));
    }
}
