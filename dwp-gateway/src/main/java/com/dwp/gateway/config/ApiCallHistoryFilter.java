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
import reactor.util.context.Context;

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
        
        // traceId 생성 또는 기존 traceId 유지 (X-Trace-Id 헤더가 있으면 사용)
        ServerHttpRequest request = exchange.getRequest();
        String existingTraceId = request.getHeaders().getFirst("X-Trace-Id");
        final String traceId = (existingTraceId != null && !existingTraceId.trim().isEmpty()) 
                ? existingTraceId 
                : UUID.randomUUID().toString();
        exchange.getAttributes().put("traceId", traceId);
        
        // 컨텍스트 정보 추출 (로깅 추적성 강화)
        String tenantIdStr = request.getHeaders().getFirst("X-Tenant-ID");
        String userIdStr = request.getHeaders().getFirst("X-User-ID");
        String agentId = request.getHeaders().getFirst("X-Agent-ID");
        String path = request.getURI().getPath();
        
        // Reactive Context에 컨텍스트 정보 설정 (로깅 추적성 강화)
        // Reactive 환경에서는 Context를 통해 전파 (MDC는 Thread-local이므로 제한적)
        return chain.filter(exchange)
                .contextWrite(Context.of(
                        "traceId", traceId,
                        "tenantId", tenantIdStr != null ? tenantIdStr : "",
                        "userId", userIdStr != null ? userIdStr : "",
                        "agentId", agentId != null ? agentId : "",
                        "path", path
                ))
                .then(Mono.fromRunnable(() -> {
            try {
                long endTime = System.currentTimeMillis();
                long latency = endTime - startTime;
                
                // SSE 요청 여부 확인 (요약 기록 정책 적용)
                // - 요청 Accept 헤더 확인
                // - 경로에 /stream 포함 여부 확인
                // - 응답 Content-Type 확인 (text/event-stream)
                boolean isSseRequest = isSseRequest(request, exchange);
                
                String source = request.getHeaders().getFirst("X-DWP-Source");
                
                // Content-Length 헤더에서 요청/응답 크기 추출
                String requestContentLength = request.getHeaders().getFirst("Content-Length");
                String responseContentLength = exchange.getResponse().getHeaders().getFirst("Content-Length");
                
                Long requestSizeBytes = null;
                if (requestContentLength != null) {
                    try {
                        requestSizeBytes = Long.parseLong(requestContentLength);
                    } catch (NumberFormatException e) {
                        // 무시
                    }
                }
                
                Long responseSizeBytes = null;
                if (responseContentLength != null) {
                    try {
                        responseSizeBytes = Long.parseLong(responseContentLength);
                    } catch (NumberFormatException e) {
                        // 무시
                    }
                }
                
                Integer statusCode = (exchange.getResponse().getStatusCode() != null) 
                        ? exchange.getResponse().getStatusCode().value() : 200;
                
                // 비정상 종료 원인 추출 (499: Client Closed, 504: Gateway Timeout 등)
                String errorCode = null;
                if (statusCode >= 400) {
                    if (statusCode == 499) {
                        errorCode = "CLIENT_CLOSED";
                    } else if (statusCode == 504) {
                        errorCode = "GATEWAY_TIMEOUT";
                    } else if (statusCode >= 500) {
                        errorCode = "SERVER_ERROR";
                    } else {
                        errorCode = "CLIENT_ERROR";
                    }
                }
                
                // MDC에 컨텍스트 정보 설정 (로깅 추적성 강화)
                // Reactive 환경에서는 직접 MDC 사용이 제한적이므로, 로그 메시지에 포함
                String logContext = String.format("[traceId=%s, tenantId=%s, userId=%s, agentId=%s, path=%s]",
                        traceId, tenantIdStr != null ? tenantIdStr : "N/A",
                        userIdStr != null ? userIdStr : "N/A",
                        agentId != null ? agentId : "N/A", path);
                
                // SSE 요청은 요약만 기록 (장시간 스트리밍으로 인한 과도한 로그 방지)
                // 정책: 1회 요청에 대해 요약 1건만 기록
                // - duration/status/tenantId/userId/route/traceId만 기록
                // - payload 크기는 기록하지 않음 (스트리밍이므로 의미 없음)
                // - chunk마다 저장 금지 (요청 시작 시 1회만 기록)
                if (isSseRequest) {
                    log.info("SSE request summary {} - path={}, status={}, latency={}ms", 
                            logContext, path, statusCode, latency);
                    // SSE 요청은 요약 정보만 기록 (requestSizeBytes, responseSizeBytes는 null)
                    ApiCallHistoryRequest historyRequest = ApiCallHistoryRequest.builder()
                            .tenantId(parseId(tenantIdStr, 1L))
                            .userId(parseId(userIdStr, null))
                            .agentId(agentId)
                            .method(request.getMethod().name())
                            .path(path)
                            .queryString(null)  // SSE 요청은 쿼리스트링 기록 안 함
                            .statusCode(statusCode)
                            .latencyMs(latency)
                            .requestSizeBytes(null)  // SSE 요청은 크기 기록 안 함
                            .responseSizeBytes(null)  // SSE 응답은 스트리밍이므로 크기 기록 안 함
                            .ipAddress(getClientIp(request))
                            .userAgent(request.getHeaders().getFirst("User-Agent"))
                            .traceId(traceId)
                            .errorCode(errorCode)  // 비정상 종료 원인 기록
                            .source(source != null ? source : "FRONTEND")
                            .build();
                    sendToAuthServer(historyRequest);
                } else {
                    // 일반 요청은 전체 정보 기록
                    log.debug("API request {} - path={}, status={}, latency={}ms", 
                            logContext, path, statusCode, latency);
                    ApiCallHistoryRequest historyRequest = ApiCallHistoryRequest.builder()
                            .tenantId(parseId(tenantIdStr, 1L))
                            .userId(parseId(userIdStr, null))
                            .agentId(agentId)
                            .method(request.getMethod().name())
                            .path(path)
                            .queryString(request.getURI().getQuery())
                            .statusCode(statusCode)
                            .latencyMs(latency)
                            .requestSizeBytes(requestSizeBytes)
                            .responseSizeBytes(responseSizeBytes)
                            .ipAddress(getClientIp(request))
                            .userAgent(request.getHeaders().getFirst("User-Agent"))
                            .traceId(traceId)
                            .errorCode(errorCode)
                            .source(source != null ? source : "FRONTEND")
                            .build();
                    sendToAuthServer(historyRequest);
                }
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

    /**
     * SSE 요청 여부 확인
     * 
     * 정책:
     * - path가 /api/aura/** 이고
     * - Accept 헤더에 text/event-stream 포함 또는
     * - 경로에 /stream 포함 또는
     * - 응답 Content-Type이 text/event-stream 이면 SSE 요청으로 간주
     * 
     * @param request HTTP 요청
     * @param exchange ServerWebExchange (응답 헤더 확인용)
     * @return SSE 요청 여부
     */
    private boolean isSseRequest(ServerHttpRequest request, ServerWebExchange exchange) {
        String path = request.getURI().getPath();
        
        // 1. Aura 경로 확인
        boolean isAuraPath = path != null && path.startsWith("/api/aura/");
        if (!isAuraPath) {
            return false;
        }
        
        // 2. Accept 헤더에 text/event-stream 포함 여부 확인
        String acceptHeader = request.getHeaders().getFirst("Accept");
        boolean hasAcceptHeader = acceptHeader != null && acceptHeader.contains("text/event-stream");
        
        // 3. 경로에 /stream 포함 여부 확인
        boolean isStreamPath = path != null && path.contains("/stream");
        
        // 4. 응답 Content-Type이 text/event-stream인지 확인
        String responseContentType = exchange.getResponse().getHeaders().getFirst("Content-Type");
        boolean isSseResponse = responseContentType != null && responseContentType.contains("text/event-stream");
        
        return hasAcceptHeader || isStreamPath || isSseResponse;
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
        private String agentId;
        private String method;
        private String path;
        private String queryString;
        private Integer statusCode;
        private Long latencyMs;
        private Long requestSizeBytes;
        private Long responseSizeBytes;
        private String ipAddress;
        private String userAgent;
        private String traceId;
        private String errorCode;
        private String source;
    }
}
