package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingMessageSourceResolverTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final SourceReference reference = new SourceReference(
            "MESSAGING_MESSAGE", conversationId.toString(), messageId.toString());
    private final AccessContext actor = new AccessContext(7L, 11L,
            "APP.WORK:VIEW,APP.WORK:UPDATE,APP.MESSAGING:VIEW", null, null, "ko");
    private final AtomicReference<Map<String, Object>> data = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> workSourceToken = new AtomicReference<>();
    private final AtomicReference<String> tenant = new AtomicReference<>();
    private final AtomicReference<String> user = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startOwner() throws Exception {
        data.set(Map.of(
                "conversationId", conversationId,
                "messageId", messageId,
                "channelName", "DWP Product Room",
                "senderName", "김채원 책임",
                "receivedAt", "2026-09-04T09:12:00+09:00",
                "editedAt", "2026-09-04T09:15:00+09:00",
                "excerpt", "이번 주 공지 초안을 정리해 주세요",
                "version", 4));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/work-sources/conversations/", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-DWP-Service-Token"));
            workSourceToken.set(exchange.getRequestHeaders().getFirst("X-DWP-Work-Source-Token"));
            tenant.set(exchange.getRequestHeaders().getFirst("X-DWP-Tenant-ID"));
            user.set(exchange.getRequestHeaders().getFirst("X-DWP-User-ID"));
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "status", "SUCCESS", "success", true, "data", data.get()));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopOwner() {
        if (server != null) server.stop(0);
    }

    @Test
    void hydratesOnlyAuthoritativeBoundedMetadataWithCanonicalInternalRoute() {
        ResolvedSource result = resolver().resolve(actor, reference).orElseThrow();

        assertThat(result.reference()).isEqualTo(reference);
        assertThat(result.title()).isEqualTo("이번 주 공지 초안을 정리해 주세요");
        assertThat(result.channelName()).isEqualTo("DWP Product Room");
        assertThat(result.senderName()).isEqualTo("김채원 책임");
        assertThat(result.receivedAt()).isEqualTo(OffsetDateTime.parse("2026-09-04T09:12:00+09:00"));
        assertThat(result.sourceEditedAt()).isEqualTo(OffsetDateTime.parse("2026-09-04T09:15:00+09:00"));
        assertThat(result.sourceMessageId()).isEqualTo(messageId.toString());
        assertThat(result.sourceVersion()).isEqualTo(4);
        assertThat(result.sourceRoute()).isEqualTo(
                "/messages/inbox?conversation=" + conversationId + "&message=" + messageId);
        assertThat(token.get()).isEqualTo("messaging-service-test");
        assertThat(workSourceToken.get()).isEqualTo("work-to-messaging-test");
        assertThat(tenant.get()).isEqualTo("7");
        assertThat(user.get()).isEqualTo("11");
    }

    @Test
    void accessRevocationDeletionAndIdentityMismatchNeverExposeMetadata() {
        for (int unavailableStatus : new int[] {403, 404, 410}) {
            status.set(unavailableStatus);
            assertThat(resolver().resolve(actor, reference)).isEmpty();
        }

        status.set(200);
        data.set(Map.of(
                "conversationId", UUID.randomUUID(),
                "messageId", messageId,
                "channelName", "foreign tenant",
                "senderName", "hidden",
                "receivedAt", "2026-09-04T09:12:00+09:00",
                "excerpt", "secret",
                "version", 1));
        assertThatThrownBy(() -> resolver().resolve(actor, reference))
                .isInstanceOf(BaseException.class)
                .hasMessageNotContaining("foreign tenant")
                .hasMessageNotContaining("secret");
    }

    @Test
    void serviceCredentialRejectionIsAnOperationalFailureNotAccessRevocation() {
        status.set(401);

        assertThatThrownBy(() -> resolver().resolve(actor, reference))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    @Test
    void malformedIdentityMissingPermissionAndUnconfiguredCredentialsFailClosed() {
        assertThat(resolver().resolve(new AccessContext(7L, 11L, "APP.WORK:VIEW", null, null, null), reference))
                .isEmpty();
        assertThat(resolver().resolve(actor,
                new SourceReference("MESSAGING_MESSAGE", conversationId.toString(), "not-a-message")))
                .isEmpty();
        MessagingMessageSourceResolver missing = new MessagingMessageSourceResolver(
                mapper, baseUrl, "", "", true,
                HttpClient.newBuilder().build(), Duration.ofSeconds(1));
        assertThatThrownBy(() -> missing.resolve(actor, reference)).isInstanceOf(BaseException.class);
    }

    @Test
    void nonIntegralVersionAndOversizedOwnerResponseFailClosed() {
        data.set(Map.of(
                "conversationId", conversationId,
                "messageId", messageId,
                "channelName", "DWP Product Room",
                "senderName", "김채원 책임",
                "receivedAt", "2026-09-04T09:12:00+09:00",
                "excerpt", "current message",
                "version", 4.5));
        assertThatThrownBy(() -> resolver().resolve(actor, reference))
                .isInstanceOf(BaseException.class);

        data.set(Map.of(
                "conversationId", conversationId,
                "messageId", messageId,
                "channelName", "DWP Product Room",
                "senderName", "김채원 책임",
                "receivedAt", "2026-09-04T09:12:00+09:00",
                "excerpt", "x".repeat(20_000),
                "version", 5));
        assertThatThrownBy(() -> resolver().resolve(actor, reference))
                .isInstanceOf(BaseException.class);
    }

    private MessagingMessageSourceResolver resolver() {
        return new MessagingMessageSourceResolver(mapper, baseUrl, "messaging-service-test",
                "work-to-messaging-test", true,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(2));
    }
}
