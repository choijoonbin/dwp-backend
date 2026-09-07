package com.dwp.services.platform.workhub.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.Priority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static com.dwp.services.platform.workhub.assignment.MeetingFollowupProtocol.*;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;
import static org.assertj.core.api.Assertions.*;

class MeetingFollowupSourceAuthorityTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final byte[] testSecret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private final AccessContext actor = new AccessContext(7L, 11L, "APP.WORK:VIEW,APP.WORK:UPDATE", null, null, "ko");
    private final SourceIdentity identity = new SourceIdentity(SourceSystem.MEETING_FOLLOWUP,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final AtomicReference<Function<Request, Object>> response = new AtomicReference<>(this::allowed);
    private final AtomicReference<Request> received = new AtomicReference<>();
    private final AtomicReference<JsonNode> receivedClaims = new AtomicReference<>();
    private final AtomicReference<HttpHeaders> receivedHeaders = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicBoolean stallBody = new AtomicBoolean();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach void startOwnerStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(MeetingFollowupProtocol.PATH, exchange -> {
            try {
                calls.incrementAndGet();
                HttpHeaders headers = new HttpHeaders();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
                receivedHeaders.set(headers);
                byte[] body = exchange.getRequestBody().readAllBytes();
                Request request = mapper.readValue(body, Request.class);
                String token = exchange.getRequestHeaders().getFirst(ASSERTION_HEADER);
                String[] parts = token.split("\\.");
                assertThat(parts).hasSize(3);
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(testSecret, "HmacSHA256"));
                assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(mac.doFinal(
                        (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
                JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
                assertThat(claims.path("bodySha256").asText()).isEqualTo(
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
                assertThat(claims.path("tenantId").asLong()).isEqualTo(request.tenantId());
                assertThat(claims.path("actorUserId").asLong()).isEqualTo(request.actorUserId());
                assertThat(claims.path("candidateId").asText()).isEqualTo(request.source().candidateId().toString());
                assertThat(exchange.getRequestHeaders().getFirst("X-DWP-Service-Token")).isNull();
                received.set(request);
                receivedClaims.set(claims);
                if (stallBody.get()) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    Thread.sleep(1500);
                    return;
                }
                Object payload = response.get().apply(request);
                byte[] bytes = payload instanceof byte[] raw ? raw : mapper.writeValueAsBytes(payload);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Location", baseUrl + MeetingFollowupProtocol.PATH);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception | AssertionError failure) {
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stopOwnerStub() {
        RequestContextHolder.resetRequestAttributes();
        if (server != null) server.stop(0);
    }

    @Test void propagatesObservabilityWithoutForwardingCallerCredentialsOrIdentityHeaders() {
        String parentTrace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockHttpServletRequest incoming = new MockHttpServletRequest();
        incoming.addHeader("X-Correlation-ID", "work-assignment-correlation");
        incoming.addHeader("traceparent", parentTrace);
        incoming.addHeader("tracestate", "vendor=work-source");
        incoming.addHeader("Authorization", "Bearer caller-only-credential");
        incoming.addHeader("Cookie", "dwp_session=caller-only-session");
        incoming.addHeader("X-DWP-Service-Token", "caller-only-service-token");
        incoming.addHeader("X-User-ID", "999");
        incoming.addHeader("X-Tenant-ID", "999");
        incoming.addHeader("X-Permissions", "APP.ADMIN:UPDATE");
        incoming.addHeader("X-Session-ID", "caller-only-session");
        incoming.addHeader(ASSERTION_HEADER, "caller-cannot-supply-workload-assertion");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incoming));

        assertThat(adapter(Duration.ofSeconds(2)).confirmCreate(actor, identity, 3).source()).isEqualTo(identity);

        HttpHeaders outbound = receivedHeaders.get();
        assertThat(outbound.getFirst("X-Correlation-ID")).isEqualTo("work-assignment-correlation");
        assertThat(outbound.getFirst("traceparent"))
                .matches("00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01")
                .isNotEqualTo(parentTrace);
        assertThat(outbound.getFirst("tracestate")).isEqualTo("vendor=work-source");
        for (String name : List.of("Authorization", "Cookie", "X-DWP-Service-Token",
                "X-User-ID", "X-Tenant-ID", "X-Permissions", "X-Session-ID")) {
            assertThat(outbound.getFirst(name)).as(name).isNull();
        }
        assertThat(outbound.getFirst(ASSERTION_HEADER)).isNotEqualTo("caller-cannot-supply-workload-assertion");
        assertThat(receivedClaims.get().path("actorUserId").asLong()).isEqualTo(actor.userId());
        assertThat(receivedClaims.get().path("tenantId").asLong()).isEqualTo(actor.tenantId());
    }

    @Test void createsOnlyFromConfirmedTermsAndUsesDedicatedBoundAssertion() {
        var source = adapter(Duration.ofSeconds(2));
        var task = source.confirmCreate(actor, identity, 3);
        assertThat(task.source()).isEqualTo(identity);
        assertThat(task.assigneeUserId()).isEqualTo(21);
        assertThat(task.title()).isEqualTo("Confirmed independent task");
        assertThat(task.description()).isEqualTo("Human reviewed work terms");
        assertThat(received.get().targetAssigneeUserId()).isNull();
        assertThat(received.get().expectedSourceVersion()).isEqualTo(3);
        assertThat(receivedClaims.get().path("aud").asText()).isEqualTo(AUDIENCE);
        assertThat(source.inspect(actor, identity, 3).source().sourceRoute())
                .isEqualTo("/meetings/follow-ups?meetingId=" + identity.meetingId()
                        + "&reportId=" + identity.reportId() + "&candidateId=" + identity.candidateId());
    }

    @Test void reassignRequiresOwnerDecisionForExactNewAssignee() {
        adapter(Duration.ofSeconds(2)).requireReassignment(actor, identity, 31);
        assertThat(received.get().action()).isEqualTo(Operation.REASSIGN);
        assertThat(received.get().targetAssigneeUserId()).isEqualTo(31);
        response.set(request -> new Response(7L, 11L, request.source(), request.action(), false,
                "ASSIGNEE_NOT_ELIGIBLE", null, OriginalAccess.AVAILABLE, false, null));
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).requireReassignment(actor, identity, 32))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }

    @Test void removedSourceHidesAllProvenanceAndNewCreateFails() {
        response.set(request -> new Response(7L, 11L, request.source(), request.action(), false,
                "SOURCE_DELETED", null, OriginalAccess.DELETED, false, null));
        var source = adapter(Duration.ofSeconds(2));
        assertThat(source.inspect(actor, identity, 3)).isEqualTo(new WorkAssignmentSourceAuthority.Inspection(
                new SourceView(SourceAvailability.UNAVAILABLE, null, null, null), false));
        assertThatThrownBy(() -> source.confirmCreate(actor, identity, 3)).isInstanceOf(BaseException.class);
    }

    @Test void differentActorOrCandidateResponseNeverBecomesAvailable() {
        response.set(request -> new Response(7L, 99L, request.source(), request.action(), true,
                null, 3L, OriginalAccess.AVAILABLE, true, request.action() == Operation.CREATE ? terms() : null));
        var source = adapter(Duration.ofSeconds(2));
        assertThatThrownBy(() -> source.confirmCreate(actor, identity, 3)).isInstanceOf(BaseException.class);
        assertThat(source.inspect(actor, identity, 3).source().availability()).isEqualTo(SourceAvailability.UNAVAILABLE);
        response.set(request -> new Response(7L, 11L,
                new Source(request.source().meetingId(), request.source().reportId(), UUID.randomUUID()),
                request.action(), true, null, 3L, OriginalAccess.AVAILABLE, true, terms()));
        assertThatThrownBy(() -> source.confirmCreate(actor, identity, 3)).isInstanceOf(BaseException.class);
    }

    @Test void changedConfirmedVersionReturnsConflictWithoutCreating() {
        response.set(request -> new Response(7L, 11L, request.source(), request.action(), true,
                null, 4L, OriginalAccess.AVAILABLE, true, terms()));
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).confirmCreate(actor, identity, 3))
                .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test void duplicateDecisionKeysAndTrailingJsonFailClosed() throws Exception {
        Request request = new Request(7, 11, new Source(identity.meetingId(), identity.reportId(), identity.candidateId()),
                Operation.CREATE, null, 3L);
        String valid = mapper.writeValueAsString(allowed(request));
        response.set(ignored -> valid.replace("\"allowed\":true", "\"allowed\":false,\"allowed\":true")
                .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).confirmCreate(actor, identity, 3)).isInstanceOf(BaseException.class);
        response.set(ignored -> (valid + "{\"allowed\":false}").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).confirmCreate(actor, identity, 3)).isInstanceOf(BaseException.class);
    }

    @Test void redirectsAreNotFollowedAndOversizedResponseIsNotExposed() {
        status.set(302);
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).confirmCreate(actor, identity, 3)).isInstanceOf(BaseException.class);
        assertThat(calls.get()).isEqualTo(1);
        status.set(200);
        response.set(request -> ("SENSITIVE_ORIGINAL_CONTENT" + "x".repeat(17_000)).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2)).confirmCreate(actor, identity, 3))
                .isInstanceOf(BaseException.class).hasMessageNotContaining("SENSITIVE_ORIGINAL_CONTENT");
    }

    @Test void timeoutHidesSourceAndDoesNotRetryAutomatically() {
        response.set(request -> { try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return allowed(request); });
        assertThat(adapter(Duration.ofMillis(80)).inspect(actor, identity, 3).source().availability())
                .isEqualTo(SourceAvailability.UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void overallDeadlineAlsoCancelsAStalledChunkedBodyAfterHeaders() {
        stallBody.set(true);
        long started = System.nanoTime();
        assertThat(adapter(Duration.ofMillis(150)).inspect(actor, identity, 3).source().availability())
                .isEqualTo(SourceAvailability.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void missingCredentialsAndImplicitHttpFailClosedWithoutNetwork() {
        var missing = new MeetingFollowupSourceAuthority(mapper, baseUrl, "", "", true);
        assertThat(missing.inspect(actor, identity, 3).source().availability()).isEqualTo(SourceAvailability.UNAVAILABLE);
        var plaintextDisabled = new MeetingFollowupSourceAuthority(mapper, baseUrl, "work-test",
                Base64.getEncoder().encodeToString(testSecret), false);
        assertThatThrownBy(() -> plaintextDisabled.confirmCreate(actor, identity, 3)).isInstanceOf(BaseException.class);
        assertThat(calls.get()).isZero();
    }

    private MeetingFollowupSourceAuthority adapter(Duration timeout) {
        return new MeetingFollowupSourceAuthority(mapper, baseUrl, "work-test",
                Base64.getEncoder().encodeToString(testSecret), true,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), timeout);
    }
    private Response allowed(Request request) {
        return new Response(request.tenantId(), request.actorUserId(), request.source(), request.action(),
                true, null, 3L, OriginalAccess.AVAILABLE, true, request.action() == Operation.CREATE ? terms() : null);
    }
    private ApprovedTask terms() { return new ApprovedTask(21, "Confirmed independent task", "Human reviewed work terms", Priority.NORMAL, null); }
}
