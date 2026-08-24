package com.dwp.gateway.productsurface;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Component
public class ProductSurfaceAuthorityClient {

    private static final String INTERNAL_PATH =
            "/internal/auth/v1/product-surface-authority/evaluate";
    private static final String TOKEN_HEADER = "X-DWP-Product-Surface-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final String SERVICE_IDENTITY = "dwp-gateway";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";

    private final WebClient authClient;
    private final String serviceToken;
    private final Duration timeout;

    public ProductSurfaceAuthorityClient(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_AUTH_URL:http://localhost:8001}") String authServiceUrl,
            @Value("${dwp.auth.product-surface-token:}") String serviceToken,
            @Value("${dwp.product-surface.authority-timeout:2s}") Duration timeout) {
        this.authClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.timeout = timeout;
    }

    Mono<ProductSurfaceContextDtos.AuthorityResult> evaluate(
            ProductSurfaceContextDtos.RequestContext context,
            String productKey,
            String surfaceKey,
            String routeContractKey,
            String contextKey,
            String contextScopeKey) {
        if (serviceToken.isBlank()) return Mono.just(unavailable(context, productKey, surfaceKey));
        var request = new ProductSurfaceContextDtos.AuthorityEvaluateRequest(
                context.tenantId(),
                context.actorId(),
                productKey,
                surfaceKey,
                context.activeAccessMode(),
                routeContractKey,
                contextKey,
                contextScopeKey,
                context.supportSessionRef(),
                context.supportRevision(),
                context.supportScopes());
        return authClient.post()
                .uri(INTERNAL_PATH)
                .headers(headers -> trustedHeaders(headers, context))
                .bodyValue(request)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(ProductSurfaceContextDtos.AuthorityResult.class)
                        : response.createException().flatMap(Mono::error))
                .timeout(timeout)
                .onErrorReturn(unavailable(context, productKey, surfaceKey));
    }

    private void trustedHeaders(
            HttpHeaders headers,
            ProductSurfaceContextDtos.RequestContext context) {
        headers.set(TOKEN_HEADER, serviceToken);
        headers.set(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY);
        headers.set(USER_HEADER, Long.toString(context.actorId()));
        headers.set(TENANT_HEADER, Long.toString(context.tenantId()));
        copy(headers, CORRELATION_HEADER, context.correlationId());
        copy(headers, TRACE_PARENT_HEADER, context.traceParent());
        copy(headers, TRACE_STATE_HEADER, context.traceState());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    private void copy(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) headers.set(name, value);
    }

    private ProductSurfaceContextDtos.AuthorityResult unavailable(
            ProductSurfaceContextDtos.RequestContext context,
            String productKey,
            String surfaceKey) {
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE,
                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                null,
                null,
                null,
                productKey,
                surfaceKey,
                null,
                context.activeAccessMode(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
