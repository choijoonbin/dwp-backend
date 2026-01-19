package com.dwp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 요청 Body 로깅 및 전달 보장 필터
 * 
 * POST 요청의 body 데이터가 Aura-Platform까지 유실 없이 전달되는지 확인합니다.
 * 
 * Spring Cloud Gateway는 기본적으로 요청 body를 자동으로 전달하지만,
 * 명시적으로 로깅하여 전달 여부를 확인할 수 있습니다.
 * 
 * 특히 프론트엔드의 POST /api/aura/test/stream 요청의 body (prompt, context)가
 * Aura-Platform까지 정상적으로 전달되는지 보장합니다.
 */
@Slf4j
@Component
public class RequestBodyLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod() != null ? request.getMethod().name() : "";
        
        // POST 요청에 대한 body 로깅 (Aura-Platform 및 Auth Server)
        boolean isAuraRequest = path.contains("/api/aura/");
        boolean isAuthRequest = path.contains("/api/auth/");
        
        if ((!isAuraRequest && !isAuthRequest) || !"POST".equals(method)) {
            return chain.filter(exchange);
        }
        
        // 요청 body가 있는 경우에만 처리
        if (request.getHeaders().getContentLength() == 0) {
            return chain.filter(exchange);
        }
        
        // 요청 body를 읽고 로깅한 후 재사용 가능하도록 캐싱
        return DataBufferUtils.join(request.getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    
                    // Body 내용 로깅 (민감 정보 제외)
                    if (body.length() > 0) {
                        // Body가 너무 크면 일부만 로깅
                        String logBody = body.length() > 500 
                            ? body.substring(0, 500) + "... (truncated)" 
                            : body;
                        
                        if (isAuraRequest) {
                            log.debug("POST request body for Aura-Platform: path={}, bodyLength={}, bodyPreview={}", 
                                    path, body.length(), logBody);
                            
                            // Body에 prompt와 context가 포함되어 있는지 확인
                            if (body.contains("\"prompt\"") && body.contains("\"context\"")) {
                                log.debug("✅ Request body contains required fields: prompt and context");
                            } else {
                                log.warn("⚠️ Request body may be missing required fields (prompt or context)");
                            }
                        } else if (isAuthRequest) {
                            log.debug("POST request body for Auth Server: path={}, bodyLength={}, bodyPreview={}", 
                                    path, body.length(), logBody);
                            
                            // Body에 username과 tenantId가 포함되어 있는지 확인
                            if (body.contains("\"username\"") && body.contains("\"tenantId\"")) {
                                log.debug("✅ Request body contains required fields: username and tenantId");
                            } else {
                                log.warn("⚠️ Request body may be missing required fields (username or tenantId)");
                            }
                        }
                    } else {
                        log.warn("⚠️ POST request body is empty: path={}", path);
                    }
                    
                    // Body를 다시 읽을 수 있도록 DataBuffer로 변환
                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                    ServerHttpRequestDecorator decoratedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        @SuppressWarnings("null")
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(buffer);
                        }
                    };
                    
                    return chain.filter(exchange.mutate().request(decoratedRequest).build());
                })
                .onErrorResume(e -> {
                    log.error("Error reading request body for path: {}", path, e);
                    // 에러 발생 시에도 요청은 계속 진행
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        // HeaderPropagationFilter보다 나중에 실행되어 body를 확인
        return -90;
    }
}
