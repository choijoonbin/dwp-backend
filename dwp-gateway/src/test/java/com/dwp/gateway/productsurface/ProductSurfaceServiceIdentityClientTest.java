package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSurfaceServiceIdentityClientTest {

    @Test
    void authorityClientSendsTheTrustedGatewayIdentity() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ProductSurfaceAuthorityClient client = new ProductSurfaceAuthorityClient(
                capturingBuilder(captured),
                "http://auth.test",
                "trusted-auth-token",
                Duration.ofSeconds(1));

        client.evaluate(context(), "hcm", "hcm.personal", null, null, null).block();

        assertGatewayIdentity(captured.get());
        assertThat(captured.get().headers().getFirst("X-DWP-Product-Surface-Token"))
                .isEqualTo("trusted-auth-token");
    }

    @Test
    void governedRouteClientSendsTheTrustedGatewayIdentity() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        GovernedRouteAuthorityClient client = new GovernedRouteAuthorityClient(
                capturingBuilder(captured),
                "http://auth.test",
                "trusted-auth-token",
                Duration.ofSeconds(1));
        var request = new ProductSurfaceContextDtos.GovernedEvaluationRequest(
                new ProductSurfaceContextDtos.Subject("GOVERNED_CONTEXT", null, null),
                "work.work",
                "route.context.work__work.review-detail.data",
                null,
                null);

        client.evaluate(context(), request).block();

        assertGatewayIdentity(captured.get());
        assertThat(captured.get().headers().getFirst("X-DWP-Product-Surface-Token"))
                .isEqualTo("trusted-auth-token");
    }

    @Test
    void eligibilityClientSendsTheTrustedGatewayIdentity() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ProductSurfaceEligibilityClient client = new ProductSurfaceEligibilityClient(
                capturingBuilder(captured),
                "http://people.test",
                "trusted-people-token",
                Duration.ofSeconds(1));

        client.evaluate(context(), authority(), null, OffsetDateTime.now()).block();

        assertGatewayIdentity(captured.get());
        assertThat(captured.get().headers().getFirst("X-DWP-Service-Token"))
                .isEqualTo("trusted-people-token");
    }

    private WebClient.Builder capturingBuilder(AtomicReference<ClientRequest> captured) {
        return WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
        });
    }

    private ProductSurfaceContextDtos.RequestContext context() {
        return new ProductSurfaceContextDtos.RequestContext(
                42L,
                17L,
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                null,
                null,
                List.of(),
                "correlation-1",
                null,
                null);
    }

    private ProductSurfaceContextDtos.AuthorityResult authority() {
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.ALLOWED,
                "ALLOWED",
                "auth-rev-1",
                "policy-rev-1",
                "ctx-1",
                "hcm",
                "workspace",
                "MEMBER",
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                ProductSurfaceContextDtos.AccessSource.ENTITLEMENT,
                "APP.HCM",
                List.of(),
                List.of(),
                null,
                false,
                true,
                null,
                null,
                null,
                null,
                null,
                "evidence-1");
    }

    private void assertGatewayIdentity(ClientRequest request) {
        assertThat(request).isNotNull();
        assertThat(request.headers().getFirst("X-DWP-Service-Identity"))
                .isEqualTo("dwp-gateway");
    }
}
