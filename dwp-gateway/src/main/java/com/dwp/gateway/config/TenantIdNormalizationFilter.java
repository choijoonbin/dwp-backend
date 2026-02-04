package com.dwp.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Aura 라우팅 시 X-Tenant-ID 숫자 정규화.
 * Synapse Audit ingest는 숫자(Long) 또는 숫자 문자열만 처리. 비숫자("tenant1") 시 스킵됨.
 * - X-Tenant-ID가 이미 숫자(또는 숫자 문자열)면 통과
 * - 비숫자면 JWT tenant_id claim에서 추출 시도 → 숫자면 교체
 * - 해결 불가 시 400
 */
@Slf4j
@Component
public class TenantIdNormalizationFilter implements GlobalFilter, Ordered {

    private static final String HEADER_TENANT_ID = "X-Tenant-ID";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");

    private static final int ORDER_AFTER_REQUIRED = -199;  // RequiredHeaderFilter(-200) 다음

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path == null || !path.startsWith("/api/aura/")) {
            return chain.filter(exchange);
        }

        String tenantId = exchange.getRequest().getHeaders().getFirst(HEADER_TENANT_ID);
        if (tenantId == null || tenantId.isBlank()) {
            return chain.filter(exchange);
        }

        if (NUMERIC.matcher(tenantId.trim()).matches()) {
            return chain.filter(exchange);
        }

        String normalized = extractTenantIdFromJwt(exchange.getRequest());
        if (normalized != null) {
            log.debug("X-Tenant-ID normalized: {} -> {} (from JWT) for path: {}", tenantId, normalized, path);
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header(HEADER_TENANT_ID, normalized)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        log.warn("X-Tenant-ID non-numeric and no JWT tenant_id: {} for path: {}. Aura Audit ingest will skip.", tenantId, path);
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"X-Tenant-ID must be numeric (e.g. 1) for Aura API. Non-numeric values like 'tenant1' cause Audit ingest to be skipped.\"}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private String extractTenantIdFromJwt(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HEADER_AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String token = auth.substring(7).trim();
        if (token.isEmpty()) return null;

        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(payload);
            JsonNode tid = node.get("tenant_id");
            if (tid == null) tid = node.get("tenantId");
            if (tid == null) return null;
            String val = tid.isNumber() ? String.valueOf(tid.asLong()) : tid.asText("");
            if (!val.isEmpty() && NUMERIC.matcher(val).matches()) return val;
        } catch (Exception e) {
            log.trace("JWT tenant_id extract failed: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public int getOrder() {
        return ORDER_AFTER_REQUIRED;
    }
}
