package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailProviderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailProviderConnectorHttpTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void graph202AndGmailMessageIdAreProviderAcceptanceNotDelivery() throws Exception {
        try (FakeServer graphServer = new FakeServer(exchange -> respond(exchange, 202, null));
             FakeServer gmailServer = new FakeServer(exchange -> respond(exchange, 200,
                     "{\"id\":\"gmail-message\",\"threadId\":\"gmail-thread\"}"))) {
            MailConnectorPort.DeliveryReceipt graph = graph(graphServer).send(sendRequest());
            MailConnectorPort.DeliveryReceipt gmail = gmail(gmailServer).send(sendRequest());

            assertThat(graph.outcome())
                    .isEqualTo(MailConnectorPort.DeliveryOutcome.PROVIDER_ACCEPTED);
            assertThat(graph.providerMessageReference()).isNull();
            assertThat(gmail.outcome())
                    .isEqualTo(MailConnectorPort.DeliveryOutcome.PROVIDER_ACCEPTED);
            assertThat(gmail.providerMessageReference()).isEqualTo("gmail-message");
        }
    }

    @Test
    void explicitProviderFiveHundredResponseIsRetryableNotResultUnknown() throws Exception {
        try (FakeServer graphServer = new FakeServer(exchange -> respond(exchange, 503, "{}"));
             FakeServer gmailServer = new FakeServer(exchange -> respond(exchange, 503, "{}"))) {
            assertThatThrownBy(() -> graph(graphServer).send(sendRequest()))
                    .isInstanceOfSatisfying(MailProviderException.class, failure -> {
                        assertThat(failure.category())
                                .isEqualTo(MailProviderException.Category.TRANSIENT);
                        assertThat(failure.errorCode()).isEqualTo("GRAPH_SEND_UNAVAILABLE");
                    });
            assertThatThrownBy(() -> gmail(gmailServer).send(sendRequest()))
                    .isInstanceOfSatisfying(MailProviderException.class, failure -> {
                        assertThat(failure.category())
                                .isEqualTo(MailProviderException.Category.TRANSIENT);
                        assertThat(failure.errorCode()).isEqualTo("GMAIL_SEND_UNAVAILABLE");
                    });
        }
    }

    @Test
    void responseLossAfterPostBodySubmissionRequiresReconciliation() throws Exception {
        try (FakeServer graphServer = new FakeServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        })) {
            assertThatThrownBy(() -> graph(graphServer).send(sendRequest()))
                    .isInstanceOfSatisfying(MailProviderException.class, failure -> {
                        assertThat(failure.category())
                                .isEqualTo(MailProviderException.Category.RESULT_UNKNOWN);
                        assertThat(failure.errorCode()).isEqualTo("GRAPH_SEND_RESULT_UNKNOWN");
                    });
        }
    }

    @Test
    void graphFetchesSmallAttachmentAndDoesNotFetchOversizeAttachment() throws Exception {
        AtomicInteger attachmentFetches = new AtomicInteger();
        String clean = Base64.getEncoder().encodeToString(
                "clean".getBytes(StandardCharsets.UTF_8));
        try (FakeServer server = new FakeServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/attachments/att-small")) {
                attachmentFetches.incrementAndGet();
                respond(exchange, 200, "{\"contentBytes\":\"" + clean + "\"}");
                return;
            }
            String delta = "http://localhost:" + exchange.getLocalAddress().getPort()
                    + path + "?delta=1";
            respond(exchange, 200, "{\"value\":[{"
                    + "\"id\":\"m1\",\"conversationId\":\"t1\","
                    + "\"receivedDateTime\":\"2026-10-28T00:00:00Z\","
                    + "\"from\":{\"emailAddress\":{\"address\":\"a@example.com\"}},"
                    + "\"subject\":\"subject\",\"body\":{\"contentType\":\"text\","
                    + "\"content\":\"body\"},\"attachments\":["
                    + "{\"id\":\"att-small\",\"name\":\"clean.txt\","
                    + "\"contentType\":\"text/plain\",\"size\":5},"
                    + "{\"id\":\"att-large\",\"name\":\"large.bin\","
                    + "\"contentType\":\"application/octet-stream\",\"size\":6000000}]}],"
                    + "\"@odata.deltaLink\":\"" + delta + "\"}");
        })) {
            MailConnectorPort.SyncBatch batch = graph(server).synchronize(
                    new MailConnectorPort.SyncRequest(context(), "user@example.com", null, 50));

            assertThat(batch.messages().get(0).attachments()).hasSize(2);
            assertThat(batch.messages().get(0).attachments().get(0).content())
                    .containsExactly("clean".getBytes(StandardCharsets.UTF_8));
            assertThat(batch.messages().get(0).attachments().get(1).content()).isNull();
            assertThat(attachmentFetches).hasValue(1);
        }
    }

    @Test
    void gmailHistoryDoesNotTruncateMessagesWithinOneHistoryRecord() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/history")) {
                respond(exchange, 200, "{\"historyId\":\"101\",\"history\":[{"
                        + "\"messagesAdded\":[{\"message\":{\"id\":\"m1\"}},"
                        + "{\"message\":{\"id\":\"m2\"}},{\"message\":{\"id\":\"m3\"}}]}]}");
                return;
            }
            String id = path.substring(path.lastIndexOf('/') + 1);
            respond(exchange, 200, gmailMessage(id));
        })) {
            MailConnectorPort.SyncBatch batch = gmail(server).synchronize(
                    new MailConnectorPort.SyncRequest(
                            context(), "user@example.com", "history:100", 1));

            assertThat(batch.messages()).extracting(
                    MailConnectorPort.ProviderMessage::providerMessageReference)
                    .containsExactly("m1", "m2", "m3");
            assertThat(batch.nextCursor()).isEqualTo("history:101");
        }
    }

    @Test
    void subscriptionsBindTheExactProviderAccountAndReturnHashOnlyVerifierEvidence()
            throws Exception {
        AtomicReference<String> graphBody = new AtomicReference<>();
        AtomicReference<String> gmailPath = new AtomicReference<>();
        try (FakeServer graphServer = new FakeServer(exchange -> {
            graphBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"id\":\"graph-subscription\","
                    + "\"expirationDateTime\":\"2026-10-29T00:00:00Z\"}");
        }); FakeServer gmailServer = new FakeServer(exchange -> {
            gmailPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"historyId\":\"101\","
                    + "\"expiration\":\"1793232000000\"}");
        })) {
            MailConnectorPort.SubscriptionRequest request =
                    new MailConnectorPort.SubscriptionRequest(
                            context(), "bound-account@example.com",
                            URI.create("https://callbacks.example.test/mail"),
                            NOW.plusSeconds(86_400));
            MailConnectorPort.SubscriptionReceipt graph =
                    graph(graphServer).renewSubscription(request);
            MailConnectorPort.SubscriptionReceipt gmail =
                    gmail(gmailServer).renewSubscription(request);

            assertThat(graphBody.get()).contains(
                    "\"resource\":\"/users/bound-account@example.com/"
                            + "mailFolders('Inbox')/messages\"");
            assertThat(gmailPath.get()).isEqualTo(
                    "/gmail/v1/users/bound-account@example.com/watch");
            assertThat(graph.providerSubscriptionReference()).isEqualTo("graph-subscription");
            assertThat(graph.clientStateSha256()).matches("[0-9a-f]{64}");
            assertThat(gmail.providerSubscriptionReference())
                    .isEqualTo("projects/project/subscriptions/mail");
            assertThat(gmail.clientStateSha256()).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void inboundGraphAndGmailMessagesNeverFlattenBccIntoVisibleRecipients()
            throws Exception {
        try (FakeServer graphServer = new FakeServer(exchange -> {
            String delta = graphServerUrl(exchange) + exchange.getRequestURI().getPath()
                    + "?delta=1";
            respond(exchange, 200, "{\"value\":[{"
                    + "\"id\":\"graph-message\",\"conversationId\":\"graph-thread\","
                    + "\"receivedDateTime\":\"2026-10-28T00:00:00Z\","
                    + "\"from\":{\"emailAddress\":{\"address\":\"sender@example.com\"}},"
                    + "\"toRecipients\":[{\"emailAddress\":{\"address\":\"to@example.com\"}}],"
                    + "\"ccRecipients\":[{\"emailAddress\":{\"address\":\"cc@example.com\"}}],"
                    + "\"bccRecipients\":[{\"emailAddress\":{\"address\":\"secret@example.com\"}}],"
                    + "\"subject\":\"subject\",\"body\":{\"contentType\":\"text\","
                    + "\"content\":\"body\"}}],\"@odata.deltaLink\":\"" + delta + "\"}");
        }); FakeServer gmailServer = new FakeServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/history")) {
                respond(exchange, 200, "{\"historyId\":\"101\",\"history\":[{"
                        + "\"messagesAdded\":[{\"message\":{\"id\":\"gmail-message\"}}]}]}");
                return;
            }
            respond(exchange, 200, gmailMessageWithBcc("gmail-message"));
        })) {
            MailConnectorPort.ProviderMessage graphMessage = graph(graphServer).synchronize(
                    new MailConnectorPort.SyncRequest(
                            context(), "user@example.com", null, 50)).messages().getFirst();
            MailConnectorPort.ProviderMessage gmailMessage = gmail(gmailServer).synchronize(
                    new MailConnectorPort.SyncRequest(
                            context(), "user@example.com", "history:100", 50))
                    .messages().getFirst();

            assertThat(graphMessage.recipients())
                    .containsExactly("to@example.com", "cc@example.com")
                    .doesNotContain("secret@example.com");
            assertThat(gmailMessage.recipients())
                    .containsExactly("to@example.com", "cc@example.com")
                    .doesNotContain("secret@example.com");
        }
    }

    @Test
    void transportDoesNotFollowCrossOriginRedirectOrAllowAuthorizationOverride()
            throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        try (FakeServer target = new FakeServer(exchange -> {
            targetCalls.incrementAndGet();
            respond(exchange, 200, "{}");
        }); FakeServer source = new FakeServer(exchange -> {
            exchange.getResponseHeaders().set("Location", target.baseUrl() + "/stolen");
            respond(exchange, 302, null);
        })) {
            MailProviderHttpTransport transport = transport(source);
            try (var lease = tokenLease()) {
                assertThat(transport.get(transport.uri(uri -> uri.path("/redirect")),
                        lease, Map.of()).status()).isEqualTo(302);
            }
            assertThat(targetCalls).hasValue(0);
            try (var lease = tokenLease()) {
                assertThatThrownBy(() -> transport.get(
                        transport.uri(uri -> uri.path("/headers")), lease,
                        Map.of("Authorization", "Bearer attacker")))
                        .isInstanceOf(MailProviderHttpTransport.TransportFailure.class);
            }
        }
    }

    @Test
    void tokenBrokerPreservesRateLimitAndDoesNotFollowRedirect() throws Exception {
        try (FakeServer limited = new FakeServer(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "120");
            respond(exchange, 429, "{}");
        })) {
            RemoteMailProviderTokenOwner owner = tokenOwner(limited);
            assertThatThrownBy(() -> owner.lease(
                    MailConnectorPort.ProviderFamily.MICROSOFT_GRAPH, context()))
                    .isInstanceOfSatisfying(MailProviderException.class, failure -> {
                        assertThat(failure.category())
                                .isEqualTo(MailProviderException.Category.RATE_LIMIT);
                        assertThat(failure.retryAfter()).isAfter(Instant.now());
                    });
        }
        AtomicInteger redirected = new AtomicInteger();
        try (FakeServer target = new FakeServer(exchange -> {
            redirected.incrementAndGet();
            respond(exchange, 200, "{\"accessToken\":\"should-not-be-read-token\","
                    + "\"expiresAt\":\"2026-10-29T00:00:00Z\"}");
        }); FakeServer source = new FakeServer(exchange -> {
            exchange.getResponseHeaders().set("Location", target.baseUrl() + "/token");
            respond(exchange, 302, null);
        })) {
            assertThatThrownBy(() -> tokenOwner(source).lease(
                    MailConnectorPort.ProviderFamily.MICROSOFT_GRAPH, context()))
                    .isInstanceOf(MailProviderException.class);
            assertThat(redirected).hasValue(0);
        }
    }

    private RemoteMailProviderTokenOwner tokenOwner(FakeServer server) {
        return new RemoteMailProviderTokenOwner(RestClient.builder(), mapper,
                server.baseUrl(), "localhost", true, 32768);
    }

    private MicrosoftGraphMailConnector graph(FakeServer server) {
        return new MicrosoftGraphMailConnector(broker(), transport(server),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    private GmailMailConnector gmail(FakeServer server) {
        return new GmailMailConnector(broker(), transport(server),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "projects/project/topics/mail", "projects/project/subscriptions/mail");
    }

    private MailProviderHttpTransport transport(FakeServer server) {
        return new MailProviderHttpTransport(RestClient.builder(), mapper,
                server.baseUrl(), "localhost", true, 8 * 1024 * 1024, 32 * 1024 * 1024);
    }

    private MailProviderTokenBroker broker() {
        return new MailProviderTokenBroker(List.of(new MailProviderTokenOwner() {
            @Override public boolean supports(MailConnectorPort.ProviderFamily provider,
                                              MailConnectorPort.ConnectionContext context) {
                return true;
            }
            @Override public AccessTokenLease lease(MailConnectorPort.ProviderFamily provider,
                                                    MailConnectorPort.ConnectionContext context) {
                return tokenLease();
            }
        }));
    }

    private MailProviderTokenOwner.AccessTokenLease tokenLease() {
        return new MailProviderTokenOwner.AccessTokenLease(
                "provider-access-token".toCharArray(), Instant.now().plusSeconds(3600));
    }

    private MailConnectorPort.ConnectionContext context() {
        return new MailConnectorPort.ConnectionContext(
                new ExecutionContext("1", "2", Set.of(), "provider-test"),
                UUID.randomUUID(), URI.create("secret://mail/provider"), "example.com");
    }

    private MailConnectorPort.SendRequest sendRequest() {
        return new MailConnectorPort.SendRequest(context(), "user@example.com",
                UUID.randomUUID(), List.of("to@example.com"), List.of(), List.of(),
                "Subject", "Body", MailConnectorPort.BodyFormat.TEXT, List.of(),
                MailConnectorPort.SenderMode.ACCOUNT, null);
    }

    private static String gmailMessage(String id) {
        return "{\"id\":\"" + id + "\",\"threadId\":\"thread-" + id
                + "\",\"historyId\":\"101\",\"internalDate\":\"1793145600000\","
                + "\"payload\":{\"mimeType\":\"text/plain\",\"headers\":["
                + "{\"name\":\"From\",\"value\":\"a@example.com\"},"
                + "{\"name\":\"To\",\"value\":\"b@example.com\"},"
                + "{\"name\":\"Subject\",\"value\":\"subject\"}],"
                + "\"body\":{\"data\":\"Ym9keQ\",\"size\":4}}}";
    }

    private static String gmailMessageWithBcc(String id) {
        return "{\"id\":\"" + id + "\",\"threadId\":\"thread-" + id
                + "\",\"historyId\":\"101\",\"internalDate\":\"1793145600000\","
                + "\"payload\":{\"mimeType\":\"text/plain\",\"headers\":["
                + "{\"name\":\"From\",\"value\":\"sender@example.com\"},"
                + "{\"name\":\"To\",\"value\":\"to@example.com\"},"
                + "{\"name\":\"Cc\",\"value\":\"cc@example.com\"},"
                + "{\"name\":\"Bcc\",\"value\":\"secret@example.com\"},"
                + "{\"name\":\"Subject\",\"value\":\"subject\"}],"
                + "\"body\":{\"data\":\"Ym9keQ\",\"size\":4}}}";
    }

    private static String graphServerUrl(HttpExchange exchange) {
        return "http://localhost:" + exchange.getLocalAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (body != null) exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;
        private FakeServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", handler);
            server.start();
        }
        private String baseUrl() { return "http://localhost:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
