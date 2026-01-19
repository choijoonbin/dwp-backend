package com.dwp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 재연결 지원 필터
 * 
 * SSE 응답에 id: 라인을 추가하여 재연결을 지원합니다.
 * - Last-Event-ID 헤더를 Aura-Platform으로 전달
 * - SSE 응답에 id: 라인 추가 (이벤트 ID 생성)
 * 
 * SSE 표준:
 * - 각 이벤트는 id: 라인을 포함할 수 있음
 * - 클라이언트는 Last-Event-ID 헤더로 마지막 수신한 이벤트 ID를 전송
 * - 서버는 Last-Event-ID를 기반으로 재연결 시 중단된 지점부터 재개 가능
 */
@Slf4j
@Component
public class SseReconnectionFilter implements GlobalFilter, Ordered {

    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    private static final AtomicLong eventIdCounter = new AtomicLong(0);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String acceptHeader = request.getHeaders().getFirst(HttpHeaders.ACCEPT);
        String path = request.getURI().getPath();
        
        // SSE 요청인지 확인
        boolean isSseRequest = (acceptHeader != null && acceptHeader.contains(TEXT_EVENT_STREAM))
                || (path != null && path.contains("/stream"));
        
        if (!isSseRequest) {
            return chain.filter(exchange);
        }

        // Last-Event-ID 헤더 확인 및 로깅
        String lastEventId = request.getHeaders().getFirst(LAST_EVENT_ID_HEADER);
        if (lastEventId != null && !lastEventId.isEmpty()) {
            log.info("SSE reconnection detected: Last-Event-ID={}, path={}", lastEventId, path);
            // Last-Event-ID 헤더를 Aura-Platform으로 전달 (HeaderPropagationFilter가 처리)
        }

        // SSE 응답에 id: 라인 추가를 위한 데코레이터
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            @SuppressWarnings("null")
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                // SSE 응답 스트림을 가로채서 id: 라인 추가
                return Flux.from(body)
                        .map(dataBuffer -> {
                            // DataBuffer를 읽어서 문자열로 변환
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            DataBufferUtils.release(dataBuffer);
                            
                            String content = new String(bytes, StandardCharsets.UTF_8);
                            
                            // SSE 이벤트 형식 확인 및 id: 라인 추가
                            String modifiedContent = addEventIdIfNeeded(content);
                            
                            // 수정된 내용을 DataBuffer로 변환
                            return originalResponse.bufferFactory().wrap(
                                    modifiedContent.getBytes(StandardCharsets.UTF_8)
                            );
                        })
                        .then();  // Flux를 Mono<Void>로 변환
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    /**
     * SSE 이벤트에 id: 라인이 없으면 추가
     * 
     * @param content 원본 SSE 이벤트 내용
     * @return id: 라인이 추가된 SSE 이벤트 내용
     */
    private String addEventIdIfNeeded(String content) {
        // 이미 id: 라인이 있으면 그대로 반환
        if (content.contains("id:")) {
            return content;
        }
        
        // SSE 이벤트 형식: data: ...\n\n 또는 data: ...\n\n\n
        // 각 이벤트 블록에 id: 라인 추가
        String[] events = content.split("\n\n");
        StringBuilder result = new StringBuilder();
        
        for (String event : events) {
            if (event.trim().isEmpty()) {
                result.append("\n\n");
                continue;
            }
            
            // 이벤트 ID 생성 (타임스탬프 + 카운터)
            long eventId = System.currentTimeMillis() * 1000 + eventIdCounter.incrementAndGet() % 1000;
            
            // id: 라인을 맨 앞에 추가
            result.append("id: ").append(eventId).append("\n");
            result.append(event);
            result.append("\n\n");
        }
        
        return result.toString();
    }

    @Override
    public int getOrder() {
        // SseResponseHeaderFilter 이후에 실행되어 응답 본문을 수정
        return -40;
    }
}
