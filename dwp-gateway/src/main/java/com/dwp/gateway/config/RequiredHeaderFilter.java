package com.dwp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 필수 헤더 검증 필터
 * 
 * Gateway 단일 진입점으로서 필수 헤더를 검증합니다:
 * - X-Tenant-ID: 멀티테넌시 식별자 (필수)
 * - X-DWP-Source: 요청 출처 (권장, 없으면 기본값 설정)
 * 
 * 정책:
 * - X-Tenant-ID가 없으면 400 Bad Request 반환
 * - X-DWP-Source가 없으면 기본값 "FRONTEND" 설정
 * - X-DWP-Caller-Type이 없으면 기본값 "USER" 설정
 * 
 * 예외:
 * - 공개 API (/api/auth/login, /api/auth/policy 등)는 제외
 * - 내부 API (/internal/**)는 제외
 */
@Slf4j
@Component
public class RequiredHeaderFilter implements GlobalFilter, Ordered {

    private static final String HEADER_TENANT_ID = "X-Tenant-ID";
    private static final String HEADER_DWP_SOURCE = "X-DWP-Source";
    private static final String HEADER_DWP_CALLER_TYPE = "X-DWP-Caller-Type";
    private static final String DEFAULT_SOURCE = "FRONTEND";
    private static final String DEFAULT_CALLER_TYPE = "USER";

    // 필수 헤더 검증 제외 경로 (공개 API)
    private static final String[] EXCLUDED_PATHS = {
        "/api/auth/login",
        "/api/auth/policy",
        "/api/auth/idp",
        "/api/monitoring/page-view",
        "/api/monitoring/event",
        "/internal/"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 제외 경로 확인
        if (isExcludedPath(path)) {
            return chain.filter(exchange);
        }

        // X-Tenant-ID 필수 검증
        String tenantId = request.getHeaders().getFirst(HEADER_TENANT_ID);
        if (tenantId == null || tenantId.trim().isEmpty()) {
            log.warn("Missing required header X-Tenant-ID for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        // X-DWP-Source 기본값 설정 (없는 경우)
        String source = request.getHeaders().getFirst(HEADER_DWP_SOURCE);
        if (source == null || source.trim().isEmpty()) {
            log.debug("Setting default X-DWP-Source: {} for path: {}", DEFAULT_SOURCE, path);
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header(HEADER_DWP_SOURCE, DEFAULT_SOURCE)
                    .build();
            exchange = exchange.mutate().request(mutatedRequest).build();
        }

        // X-DWP-Caller-Type 기본값 설정 (없는 경우)
        String callerType = request.getHeaders().getFirst(HEADER_DWP_CALLER_TYPE);
        if (callerType == null || callerType.trim().isEmpty()) {
            log.debug("Setting default X-DWP-Caller-Type: {} for path: {}", DEFAULT_CALLER_TYPE, path);
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(HEADER_DWP_CALLER_TYPE, DEFAULT_CALLER_TYPE)
                    .build();
            exchange = exchange.mutate().request(mutatedRequest).build();
        }

        return chain.filter(exchange);
    }

    private boolean isExcludedPath(String path) {
        if (path == null) {
            return false;
        }
        for (String excluded : EXCLUDED_PATHS) {
            if (path.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        // HeaderPropagationFilter보다 먼저 실행되어 필수 헤더 검증 및 기본값 설정
        return -200;
    }
}
