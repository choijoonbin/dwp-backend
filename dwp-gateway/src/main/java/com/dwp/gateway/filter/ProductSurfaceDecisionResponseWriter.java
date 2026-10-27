package com.dwp.gateway.filter;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static com.dwp.gateway.filter.ProductSurfaceDecisionHeaders.*;
import static com.dwp.gateway.filter.ProductSurfaceDecisionContextSupport.blank;

/** Writes decision revisions and preserves fail-closed denial-audit behavior. */
final class ProductSurfaceDecisionResponseWriter {

    private final ObjectMapper objectMapper;
    private final GatewayDenialAuditSink denialAudit;

    ProductSurfaceDecisionResponseWriter(
            ObjectMapper objectMapper, GatewayDenialAuditSink denialAudit) {
        this.objectMapper = objectMapper;
        this.denialAudit = denialAudit;
    }

    Mono<Void> deny(
            ServerWebExchange exchange,
            String code,
            String currentRevision,
            String routeTemplate) {
        return denialAudit.publish(exchange,
                        GatewayDenialAuditSink.Denial.productAuthority(
                                code, routeTemplate))
                .then(Mono.defer(() -> error(
                        exchange, HttpStatus.FORBIDDEN, code, currentRevision)))
                .onErrorResume(ignored -> error(
                        exchange,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AUDIT_EVIDENCE_UNAVAILABLE",
                        currentRevision));
    }

    void responseRevision(ServerWebExchange exchange, String revision) {
        if (blank(revision)) return;
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(RESPONSE_REVISION_HEADER, revision);
            return Mono.empty();
        });
    }

    Mono<Void> error(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String currentRevision) {
        try {
            responseRevision(exchange, currentRevision);
            String correlation = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
            byte[] body = objectMapper.writeValueAsBytes(
                    ProductSurfaceContextDtos.ApiResponse.error(code, code, correlation));
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception exception) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }
    }

}
