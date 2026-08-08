package com.dwp.gateway;

import com.dwp.gateway.filter.AgentServiceIdentityFilter;
import com.dwp.gateway.filter.PlatformServiceIdentityFilter;
import com.dwp.gateway.filter.ServiceIdentitySanitizingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceIdentityFilterChainTest {

    @Test
    void keepsTheAgentTokenAfterThePlatformFilterRuns() {
        assertForwardedToken("/api/agent/v1/plans/preview", "agent-token");
    }

    @Test
    void keepsThePlatformTokenAfterTheAgentFilterRuns() {
        assertForwardedToken("/api/platform/v1/admin/reference-sets", "platform-token");
    }

    private void assertForwardedToken(String path, String expectedToken) {
        ServiceIdentitySanitizingFilter sanitizer = new ServiceIdentitySanitizingFilter();
        AgentServiceIdentityFilter agent = new AgentServiceIdentityFilter("agent-token");
        PlatformServiceIdentityFilter platform =
                new PlatformServiceIdentityFilter("platform-token");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path)
                        .header(ServiceIdentitySanitizingFilter.SERVICE_TOKEN_HEADER, "attacker")
                        .build());
        AtomicReference<String> forwarded = new AtomicReference<>();
        GatewayFilterChain terminal = current -> {
            forwarded.set(current.getRequest().getHeaders()
                    .getFirst(ServiceIdentitySanitizingFilter.SERVICE_TOKEN_HEADER));
            return Mono.empty();
        };

        sanitizer.filter(
                exchange,
                sanitized -> agent.filter(
                        sanitized,
                        withAgentIdentity -> platform.filter(withAgentIdentity, terminal)))
                .block();

        assertThat(forwarded.get()).isEqualTo(expectedToken);
    }
}

