package com.dwp.gateway.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.audit.HttpAuditEventPublisher;
import com.dwp.gateway.audit.GatewayDenialAuditSink.Denial;
import com.dwp.gateway.filter.SupportSessionContextFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReactiveGatewayDenialAuditClientTest {

    private static final String TOKEN = "audit-ingest-token-at-least-24-characters";
    private static final String SECRET = "audit-privacy-secret-at-least-24-characters";

    @Test
    void createsAClosedPrivacyMinimizedEnvelopeWithStableHmacEvidence() throws Exception {
        ReactiveGatewayDenialAuditClient client = client(
                ignored -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build()));
        MockServerWebExchange exchange = exchange();
        UUID eventId = UUID.fromString("10000000-0000-0000-0000-000000000001");

        AuditEvent first = client.event(exchange,
                Denial.providerDataPlane(null), eventId);
        AuditEvent second = client.event(exchange,
                Denial.providerDataPlane(null), UUID.randomUUID());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(first);

        assertThat(first.eventId()).isEqualTo(eventId);
        assertThat(first.tenantId()).isEqualTo(42L);
        assertThat(first.targetId()).isEqualTo("/api/people/**");
        assertThat(first.actorId()).startsWith("hmac-sha256:").isEqualTo(second.actorId());
        assertThat(first.sessionIdHash()).startsWith("hmac-sha256:")
                .isEqualTo(second.sessionIdHash());
        assertThat(first.clientAddressHash()).startsWith("hmac-sha256:")
                .isEqualTo(second.clientAddressHash());
        assertThat(first.actorId()).isNotEqualTo(first.sessionIdHash());
        assertThat(first.metadata()).containsOnlyKeys(
                "schemaVersion", "method", "routeTemplate", "httpStatus",
                "denialCode", "identityPlane", "traceStatePresent",
                "supportContextPresent");
        assertThat(first.correlationId()).isNull();
        assertThat(first.traceId()).isEqualTo("11111111111111111111111111111111");
        assertThat(json)
                .doesNotContain("customer@example.test")
                .doesNotContain("email=customer")
                .doesNotContain("raw-session-family")
                .doesNotContain("opaque-support-secret")
                .doesNotContain("203.0.113.9")
                .doesNotContain("operator@example.test");
    }

    @Test
    void publishesOnlyToTheConfiguredInternalSinkWithServiceIdentityHeaders() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ReactiveGatewayDenialAuditClient client = client(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());
        });

        client.publish(exchange(), Denial.productAuthority(
                "ROUTE_NOT_REGISTERED", "/api/people/v1/people/{personId}"))
                .block();

        assertThat(captured.get().url().toString())
                .isEqualTo("http://platform:8002/internal/audit/events");
        assertThat(captured.get().headers().getFirst(
                HttpAuditEventPublisher.INGEST_TOKEN_HEADER)).isEqualTo(TOKEN);
        assertThat(captured.get().headers().getFirst(
                HttpAuditEventPublisher.SERVICE_NAME_HEADER)).isEqualTo("dwp-gateway");
        assertThat(captured.get().headers()).doesNotContainKey("Cookie");
    }

    @Test
    void rejectsCollectorFailureAndMissingEvidenceConfiguration() {
        ReactiveGatewayDenialAuditClient rejected = client(
                ignored -> Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).build()));
        ReactiveGatewayDenialAuditClient unconfigured = new ReactiveGatewayDenialAuditClient(
                WebClient.builder(), "", "", "", "local", "gateway-1", Duration.ofSeconds(1));
        ReactiveGatewayDenialAuditClient wrongPath = new ReactiveGatewayDenialAuditClient(
                WebClient.builder(), "http://platform:8002/api/audit/events", TOKEN, SECRET,
                "local", "gateway-1", Duration.ofSeconds(1));

        assertThatThrownBy(() -> rejected.publish(
                        exchange(), Denial.providerDataPlane(null)).block())
                .isInstanceOf(ReactiveGatewayDenialAuditClient.AuditSinkUnavailableException.class);
        assertThatThrownBy(() -> unconfigured.publish(
                        exchange(), Denial.providerDataPlane(null)).block())
                .isInstanceOf(ReactiveGatewayDenialAuditClient.AuditSinkUnavailableException.class);
        assertThatThrownBy(() -> wrongPath.publish(
                        exchange(), Denial.providerDataPlane(null)).block())
                .isInstanceOf(ReactiveGatewayDenialAuditClient.AuditSinkUnavailableException.class);
    }

    @Test
    void canonicalRouteFallbackNeverRetainsUnknownPathSegments() {
        assertThat(ReactiveGatewayDenialAuditClient.routeTemplate(
                null, "/api/platform/v1/users/customer@example.test"))
                .isEqualTo("/api/platform/**");
        assertThat(ReactiveGatewayDenialAuditClient.routeTemplate(
                "/api/platform/v1/users/{userId}", "/ignored"))
                .isEqualTo("/api/platform/v1/users/{userId}");
        assertThat(ReactiveGatewayDenialAuditClient.routeTemplate(
                "https://attacker.example.test/raw", "/private/customer@example.test"))
                .isEqualTo("/api/**");
    }

    private ReactiveGatewayDenialAuditClient client(
            org.springframework.web.reactive.function.client.ExchangeFunction exchange) {
        return new ReactiveGatewayDenialAuditClient(
                WebClient.builder().exchangeFunction(exchange),
                "http://platform:8002/internal/audit/events",
                TOKEN,
                SECRET,
                "test",
                "gateway-test-1",
                Duration.ofSeconds(1));
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest
                .get("https://workspace.example.test/api/people/v1/people/"
                        + "customer@example.test?email=customer@example.test")
                .remoteAddress(new InetSocketAddress("203.0.113.9", 443))
                .header(VerifiedIdentityFilter.USER_HEADER, "operator@example.test")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "42")
                .header(VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER, "raw-session-family")
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                .header("X-Correlation-ID", "customer@example.test")
                .header("traceparent",
                        "00-11111111111111111111111111111111-2222222222222222-01")
                .header(SupportSessionContextFilter.SUPPORT_SESSION_HEADER, "support-1")
                .cookie(new HttpCookie(
                        SupportSessionContextFilter.SUPPORT_COOKIE, "opaque-support-secret"))
                .build());
    }
}
