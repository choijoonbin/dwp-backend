package com.dwp.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 헤더 전파 필터
 * 
 * Authorization, X-Tenant-ID, X-DWP-Source 등의 헤더를
 * 다운스트림 서비스로 전파합니다.
 * 
 * Spring Cloud Gateway는 기본적으로 모든 헤더를 전파하지만,
 * 명시적으로 보장하기 위해 이 필터를 추가했습니다.
 */
@Slf4j
@Component
public class HeaderPropagationFilter implements GlobalFilter, Ordered {

    // 전파할 헤더 목록
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_TENANT_ID = "X-Tenant-ID";
    private static final String HEADER_DWP_SOURCE = "X-DWP-Source";
    private static final String HEADER_DWP_CALLER_TYPE = "X-DWP-Caller-Type";
    private static final String HEADER_USER_ID = "X-User-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpRequest.Builder requestBuilder = request.mutate();

        // Authorization 헤더 전파 확인 및 로깅
        String authorization = request.getHeaders().getFirst(HEADER_AUTHORIZATION);
        if (authorization != null && !authorization.isEmpty()) {
            log.debug("Propagating {} header to downstream service", HEADER_AUTHORIZATION);
            // Gateway는 기본적으로 모든 헤더를 전파하므로 별도 설정 불필요
            // 하지만 로깅을 통해 전파 여부를 확인할 수 있습니다.
        }

        // X-Tenant-ID 헤더 전파 확인
        String tenantId = request.getHeaders().getFirst(HEADER_TENANT_ID);
        if (tenantId != null && !tenantId.isEmpty()) {
            log.debug("Propagating {} header: {}", HEADER_TENANT_ID, tenantId);
        }

        // X-DWP-Source 헤더 전파 확인
        String source = request.getHeaders().getFirst(HEADER_DWP_SOURCE);
        if (source != null && !source.isEmpty()) {
            log.debug("Propagating {} header: {}", HEADER_DWP_SOURCE, source);
        }

        // X-DWP-Caller-Type 헤더 전파 확인 (에이전트 호출 식별)
        String callerType = request.getHeaders().getFirst(HEADER_DWP_CALLER_TYPE);
        if (callerType != null && !callerType.isEmpty()) {
            log.debug("Propagating {} header: {}", HEADER_DWP_CALLER_TYPE, callerType);
        }

        // X-User-ID 헤더 전파 확인
        String userId = request.getHeaders().getFirst(HEADER_USER_ID);
        if (userId != null && !userId.isEmpty()) {
            log.debug("Propagating {} header: {}", HEADER_USER_ID, userId);
        }

        // 요청 경로 로깅 (디버깅용)
        String path = request.getURI().getPath();
        if (path.contains("/api/aura/")) {
            log.info("Routing to Aura-Platform: {} with headers: Authorization={}, X-Tenant-ID={}, X-DWP-Source={}, X-DWP-Caller-Type={}",
                    path,
                    authorization != null ? "present" : "missing",
                    tenantId != null ? tenantId : "missing",
                    source != null ? source : "missing",
                    callerType != null ? callerType : "missing");
        }

        // Gateway는 기본적으로 모든 헤더를 전파하므로 requestBuilder를 변경할 필요 없음
        // 하지만 명시적으로 헤더를 보존하려면 아래와 같이 설정할 수 있습니다:
        // requestBuilder.header(HEADER_AUTHORIZATION, authorization);

        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
    }

    @Override
    public int getOrder() {
        // 다른 필터보다 먼저 실행되도록 낮은 순서 설정
        return -100;
    }
}
