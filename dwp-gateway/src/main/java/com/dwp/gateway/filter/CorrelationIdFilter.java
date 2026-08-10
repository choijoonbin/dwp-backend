package com.dwp.gateway.filter;

import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.observability.api.TraceContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final String HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = UUID.randomUUID().toString();
        } else {
            correlationId = correlationId.replaceAll("[^A-Za-z0-9._:-]", "_");
        }
        TraceContext traceContext = TraceContext.childOf(
                exchange.getRequest().getHeaders().getFirst("traceparent"));

        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(HEADER, correlationId)
                .header("traceparent", traceContext.traceParent())
                .build();
        exchange.getAttributes().put(ApiHistoryAttributes.CORRELATION_ID, correlationId);
        exchange.getAttributes().put(ApiHistoryAttributes.TRACE_ID, traceContext.traceId());
        exchange.getAttributes().put(ApiHistoryAttributes.SPAN_ID, traceContext.spanId());
        if (traceContext.parentSpanId() != null) {
            exchange.getAttributes().put(
                    ApiHistoryAttributes.PARENT_SPAN_ID, traceContext.parentSpanId());
        }
        exchange.getResponse().getHeaders().set(HEADER, correlationId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
