package com.dwp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * SSE (Server-Sent Events) 응답 헤더 보장 필터
 * 
 * SSE 요청에 대해 응답 헤더를 보장합니다:
 * - Content-Type: text/event-stream
 * - Cache-Control: no-cache
 * - Transfer-Encoding: chunked (자동 설정되지만 명시적으로 보장)
 * 
 * 지원하는 요청 방식:
 * - GET 요청: Accept: text/event-stream 헤더 확인
 * - POST 요청: /stream 경로 포함 시 SSE로 처리 (프론트엔드 요구사항)
 * 
 * Spring Cloud Gateway는 기본적으로 스트리밍을 지원하지만,
 * 프론트엔드까지 헤더가 정확히 전달되도록 보장합니다.
 * 
 * ⚠️ 중요: 프론트엔드는 POST /api/aura/test/stream을 사용하며,
 * context 데이터가 커서 POST 방식을 선택했습니다.
 * Gateway는 POST 요청에 대한 SSE 응답을 정상적으로 지원합니다.
 */
@Slf4j
@Component
public class SseResponseHeaderFilter implements GlobalFilter, Ordered {

    private static final String ACCEPT_HEADER = "Accept";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_CACHE = "no-cache";
    private static final String TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String CHUNKED = "chunked";
    private static final String CONNECTION = "Connection";
    private static final String KEEP_ALIVE = "keep-alive";
    private static final String X_ACCEL_BUFFERING = "X-Accel-Buffering";
    private static final String NO = "no";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        
        // SSE 요청인지 확인
        // 1. Accept 헤더에 text/event-stream 포함 여부 확인
        String acceptHeader = request.getHeaders().getFirst(ACCEPT_HEADER);
        boolean hasAcceptHeader = acceptHeader != null && acceptHeader.contains(TEXT_EVENT_STREAM);
        
        // 2. POST 요청도 SSE를 지원하므로 (프론트엔드 요구사항), 경로로도 확인
        String path = request.getURI().getPath();
        boolean isStreamPath = path != null && path.contains("/stream");
        
        // SSE 요청인지 판단 (Accept 헤더 또는 /stream 경로)
        boolean isSseRequest = hasAcceptHeader || isStreamPath;
        
        // SSE 요청이 아니면 일반 필터 체인 진행
        if (!isSseRequest) {
            return chain.filter(exchange);
        }

        // C29: SSE 스트리밍 시작 로깅 강화 (추적용 헤더 포함)
        String correlationId = request.getHeaders().getFirst("X-Correlation-ID");
        String agentId = request.getHeaders().getFirst("X-Agent-ID");
        String tenantId = request.getHeaders().getFirst("X-Tenant-ID");
        String userId = request.getHeaders().getFirst("X-User-ID");
        
        log.info("SSE stream started: method={}, path={}, correlationId={}, agentId={}, tenantId={}, userId={}", 
                request.getMethod(), path, correlationId, agentId, tenantId, userId);

        // 응답 헤더를 보장하기 위한 데코레이터 생성
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            @SuppressWarnings("null")
            public HttpHeaders getHeaders() {
                HttpHeaders headers = super.getHeaders();
                
                // SSE 응답 헤더 보장
                if (!headers.containsKey(CONTENT_TYPE)) {
                    headers.set(CONTENT_TYPE, TEXT_EVENT_STREAM);
                    log.debug("Set Content-Type: {}", TEXT_EVENT_STREAM);
                }
                
                // Cache-Control 헤더 보장
                if (!headers.containsKey(CACHE_CONTROL)) {
                    headers.set(CACHE_CONTROL, NO_CACHE);
                    log.debug("Set Cache-Control: {}", NO_CACHE);
                }
                
                // Connection: keep-alive 헤더 보장 (SSE 스트리밍 유지)
                if (!headers.containsKey(CONNECTION)) {
                    headers.set(CONNECTION, KEEP_ALIVE);
                    log.debug("Set Connection: {}", KEEP_ALIVE);
                }
                
                // X-Accel-Buffering: no 헤더 보장 (Nginx 프록시 버퍼링 방지)
                headers.set(X_ACCEL_BUFFERING, NO);
                log.debug("Set X-Accel-Buffering: {}", NO);
                
                // Transfer-Encoding: chunked는 Spring Cloud Gateway가 자동으로 설정하지만,
                // 명시적으로 확인하여 로깅
                String transferEncoding = headers.getFirst(TRANSFER_ENCODING);
                if (transferEncoding == null || !transferEncoding.contains(CHUNKED)) {
                    // Gateway가 자동으로 chunked를 설정하므로, 로깅만 수행
                    log.debug("Transfer-Encoding will be set to chunked by Gateway");
                } else {
                    log.debug("Transfer-Encoding already set: {}", transferEncoding);
                }
                
                return headers;
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    @Override
    public int getOrder() {
        // HeaderPropagationFilter보다 나중에 실행되어 응답 헤더를 보장
        return -50;
    }
}
