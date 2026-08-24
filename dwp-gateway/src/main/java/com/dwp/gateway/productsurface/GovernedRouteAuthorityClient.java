package com.dwp.gateway.productsurface;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class GovernedRouteAuthorityClient {

    private static final String INTERNAL_PATH =
            "/internal/auth/v1/governed-route-authority/evaluate";
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

    public GovernedRouteAuthorityClient(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_AUTH_URL:http://localhost:8001}") String authServiceUrl,
            @Value("${dwp.auth.product-surface-token:}") String serviceToken,
            @Value("${dwp.product-surface.authority-timeout:2s}") Duration timeout) {
        this.authClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.timeout = timeout;
    }

    Mono<ProductSurfaceContextDtos.GovernedAuthorityResult> evaluate(
            ProductSurfaceContextDtos.RequestContext context,
            ProductSurfaceContextDtos.GovernedEvaluationRequest request) {
        if (serviceToken.isBlank()) return Mono.just(unavailable(context, request));
        ProductSurfaceContextDtos.GovernedTarget target = request.target();
        var internalRequest = new ProductSurfaceContextDtos.GovernedAuthorityRequest(
                context.tenantId(),
                context.actorId(),
                request.navigationContextId(),
                request.routeContractKey(),
                context.activeAccessMode(),
                target == null ? null : target.opaqueTargetRef(),
                target == null ? null : target.expectedObjectVersion(),
                request.contextKey());
        return authClient.post()
                .uri(INTERNAL_PATH)
                .headers(headers -> trustedHeaders(headers, context))
                .bodyValue(internalRequest)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(ProductSurfaceContextDtos.GovernedAuthorityResult.class)
                        : response.createException().flatMap(Mono::error))
                .timeout(timeout)
                .onErrorReturn(unavailable(context, request));
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

    private ProductSurfaceContextDtos.GovernedAuthorityResult unavailable(
            ProductSurfaceContextDtos.RequestContext context,
            ProductSurfaceContextDtos.GovernedEvaluationRequest request) {
        return new ProductSurfaceContextDtos.GovernedAuthorityResult(
                ProductSurfaceContextDtos.GovernedDecision.AUTHORITY_UNAVAILABLE,
                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                null,
                null,
                null,
                request.navigationContextId(),
                null,
                context.activeAccessMode(),
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
