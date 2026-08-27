package com.dwp.gateway;

import com.dwp.gateway.filter.AgentServiceIdentityFilter;
import com.dwp.gateway.filter.ServiceIdentitySanitizingFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceIdentityFilterTest {

    @Test
    void replacesSpoofedServiceTokenOnAgentRoutes() {
        AgentServiceIdentityFilter filter = new AgentServiceIdentityFilter("gateway-secret");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .header(AgentServiceIdentityFilter.SERVICE_TOKEN_HEADER, "spoofed")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(
                AgentServiceIdentityFilter.SERVICE_TOKEN_HEADER))
                .isEqualTo("gateway-secret");
    }

    @Test
    void replacesSpoofedAssertionWithGatewaySignedIdentity() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentServiceIdentityFilter filter = new AgentServiceIdentityFilter(
                "gateway-secret",
                "gateway-agent-identity-signing-secret-at-least-32-characters",
                "gateway-agent-v1",
                objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/ask")
                .header("X-DWP-Delegated-Identity", "spoofed")
                .header("X-DWP-User-ID", "user-7")
                .header("X-DWP-Tenant-ID", "tenant-1")
                .header("X-Correlation-ID", "corr-1")
                .header("X-DWP-Roles", "EMPLOYEE")
                .header("X-DWP-Permissions", "APP.ASK:VIEW")
                .header("X-DWP-Resource-Roles",
                        "app_owner@rs_mail,APP_ACCESS_APPROVER@RS_HRIS")
                .build());
        AtomicReference<String> forwardedAssertion = new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwardedAssertion.set(filteredExchange.getRequest().getHeaders()
                    .getFirst("X-DWP-Delegated-Identity"));
            return Mono.empty();
        }).block();

        assertThat(forwardedAssertion.get()).isNotEqualTo("spoofed").contains(".");
        assertThat(forwardedAssertion.get().split("\\.")).hasSize(3);
        String encodedClaims = forwardedAssertion.get().split("\\.")[1];
        assertThat(objectMapper.readTree(Base64.getUrlDecoder().decode(encodedClaims))
                .path("resourceRoles").toString()).isEqualTo(
                "[\"APP_ACCESS_APPROVER@RS_HRIS\",\"APP_OWNER@RS_MAIL\"]");
    }

    @Test
    void failsClosedInsteadOfSigningPartiallyValidResourceRoleEvidence() {
        AgentServiceIdentityFilter filter = new AgentServiceIdentityFilter(
                "gateway-secret",
                "gateway-agent-identity-signing-secret-at-least-32-characters",
                "gateway-agent-v1",
                new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .header("X-DWP-Resource-Roles",
                        "APP_OWNER@RS_MAIL,APP_ACCESS_MANAGER@../RS_HRIS")
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenAgentServiceIdentityIsNotConfigured() {
        AgentServiceIdentityFilter filter = new AgentServiceIdentityFilter(" ");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void stripsInternalServiceTokenBeforeRoutingExternalRequests() {
        ServiceIdentitySanitizingFilter filter = new ServiceIdentitySanitizingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/auth/me")
                .header(AgentServiceIdentityFilter.SERVICE_TOKEN_HEADER, "spoofed")
                .header("X-DWP-Delegated-Identity", "spoofed-assertion")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().containsKey(
                ServiceIdentitySanitizingFilter.SERVICE_TOKEN_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                "X-DWP-Delegated-Identity")).isFalse();
    }
}
