package com.dwp.gateway.config;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Gateway API 호출 이력 적재 필터
 */
@Slf4j
@Component
@SuppressWarnings("null")
public class ApiCallHistoryFilter implements GlobalFilter, Ordered {

    private final WebClient webClient;

    @Value("${dwp.internal-api.auth-server-url:http://localhost:8001}")
    private String authServerUrl;

    public ApiCallHistoryFilter(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        exchange.getAttributes().put("traceId", traceId);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            try {
                long endTime = System.currentTimeMillis();
                long latency = endTime - startTime;

                ServerHttpRequest request = exchange.getRequest();
                
                String tenantIdStr = request.getHeaders().getFirst("X-Tenant-ID");
                String userIdStr = request.getHeaders().getFirst("X-User-ID");
                String source = request.getHeaders().getFirst("X-DWP-Source");
                
                Integer statusCode = (exchange.getResponse().getStatusCode() != null) 
                        ? exchange.getResponse().getStatusCode().value() : 200;
                
                // Auth Server에 비동기로 적재 요청
                ApiCallHistoryRequest historyRequest = ApiCallHistoryRequest.builder()
                        .tenantId(parseId(tenantIdStr, 1L))
                        .userId(parseId(userIdStr, null))
                        .method(request.getMethod().name())
                        .path(request.getURI().getPath())
                        .queryString(request.getURI().getQuery())
                        .statusCode(statusCode)
                        .latencyMs(latency)
                        .ipAddress(getClientIp(request))
                        .userAgent(request.getHeaders().getFirst("User-Agent"))
                        .traceId(traceId)
                        .source(source != null ? source : "FRONTEND")
                        .build();

                sendToAuthServer(historyRequest);
            } catch (Exception e) {
                log.error("Failed to process API call history logging", e);
            }
        }));
    }

    private void sendToAuthServer(ApiCallHistoryRequest historyRequest) {
        webClient.post()
                .uri(authServerUrl + "/internal/api-call-history")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(historyRequest)
                .retrieve()
                .bodyToMono(Void.class)
                .subscribe(
                        null,
                        ex -> log.warn("Failed to send API call history to auth-server: {}", ex.getMessage())
                );
    }

    private Long parseId(String idStr, Long defaultValue) {
        if (idStr == null || idStr.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getClientIp(ServerHttpRequest request) {
        String xf = request.getHeaders().getFirst("X-Forwarded-For");
        if (xf != null) return xf.split(",")[0];
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "0.0.0.0";
    }

    @Override
    public int getOrder() {
        // 응답이 완료된 후 기록해야 하므로 가장 나중에 실행되도록 설정
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Getter
    @Setter
    @Builder
    public static class ApiCallHistoryRequest {
        private Long tenantId;
        private Long userId;
        private String method;
        private String path;
        private String queryString;
        private Integer statusCode;
        private Long latencyMs;
        private String ipAddress;
        private String userAgent;
        private String traceId;
        private String errorCode;
        private String source;
    }
}
