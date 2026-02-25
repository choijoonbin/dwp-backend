package com.dwp.services.synapsex.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Aura Case Tab Feign Client 설정
 * - timeout: application.yml feign.client.config.aura-case-tab (ReadTimeout 최소 30초 권장, LLM 배치 10~20초 소요)
 * - retry: 1회 (period 500ms, maxAttempts 2)
 * - RequestInterceptor: 사용자 토큰 있으면 전달, 없으면(배치) X-Internal-Service-Key + X-Tenant-ID 부착
 * - RestTemplate: Aura 웹훅(조치 완료 알림) 등 아웃바운드 HTTP용
 */
@Slf4j
@Configuration
public class AuraClientConfig {

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_INTERNAL_SERVICE_KEY = "X-Internal-Service-Key";
    private static final String HEADER_TENANT_ID = "X-Tenant-ID";

    @Value("${aura.api.internal-key:}")
    private String internalKey;

    @Bean
    public RequestInterceptor auraCaseTabRequestInterceptor() {
        return template -> {
            // 1) 이미 Authorization이 있으면(컨트롤러에서 전달) 그대로 사용
            if (hasNonEmptyHeader(template, HEADER_AUTHORIZATION)) {
                if (log.isTraceEnabled()) log.trace("Aura request: using existing Authorization header");
                return;
            }
            // 2) 현재 요청 컨텍스트에서 Authorization 추출 (사용자 요청 경유 시)
            String authFromRequest = getAuthorizationFromCurrentRequest();
            if (authFromRequest != null && !authFromRequest.isBlank()) {
                template.header(HEADER_AUTHORIZATION, authFromRequest);
                if (log.isTraceEnabled()) log.trace("Aura request: using Authorization from current request");
                return;
            }
            // 3) 배치/비동기: 내부 API 키 + 테넌트 ID 주입
            if (internalKey != null && !internalKey.isBlank()) {
                template.header(HEADER_INTERNAL_SERVICE_KEY, internalKey);
                ensureTenantIdHeader(template);
                if (log.isTraceEnabled()) log.trace("Aura request: using X-Internal-Service-Key and X-Tenant-ID (batch/internal)");
            } else {
                log.debug("Aura request: no user token and no aura.api.internal-key set; request may get 401");
            }
        };
    }

    /** 배치 시 테넌트 컨텍스트를 Aura에 전달. 템플릿에 없으면 현재 요청 또는 AuraTenantContext에서 채움 */
    private void ensureTenantIdHeader(RequestTemplate template) {
        if (hasNonEmptyHeader(template, HEADER_TENANT_ID)) return;
        String tenantFromRequest = getTenantIdFromCurrentRequest();
        if (tenantFromRequest != null && !tenantFromRequest.isBlank()) {
            template.header(HEADER_TENANT_ID, tenantFromRequest);
            return;
        }
        Long tenantFromContext = AuraTenantContext.getTenantId();
        if (tenantFromContext != null) {
            template.header(HEADER_TENANT_ID, String.valueOf(tenantFromContext));
        }
    }

    private boolean hasNonEmptyHeader(RequestTemplate template, String name) {
        Collection<String> values = template.headers().get(name);
        if (values == null || values.isEmpty()) return false;
        return values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    private String getAuthorizationFromCurrentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                return sra.getRequest().getHeader(HEADER_AUTHORIZATION);
            }
        } catch (Exception e) {
            if (log.isTraceEnabled()) log.trace("Could not get request attributes for Aura auth: {}", e.getMessage());
        }
        return null;
    }

    private String getTenantIdFromCurrentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                String v = sra.getRequest().getHeader(HEADER_TENANT_ID);
                return v;
            }
        } catch (Exception e) {
            if (log.isTraceEnabled()) log.trace("Could not get request attributes for X-Tenant-ID: {}", e.getMessage());
        }
        return null;
    }

    @Bean
    public Retryer auraCaseTabRetryer() {
        return new Retryer.Default(500, TimeUnit.SECONDS.toMillis(2), 2);
    }

    @Bean
    public ErrorDecoder auraCaseTabErrorDecoder() {
        return (methodKey, response) -> {
            int status = response.status();
            String reason = response.reason() != null ? response.reason() : "";
            if (status == 401) {
                log.warn(
                    "Aura 401 Unauthorized method={} reason={}. " +
                    "Batch/internal: ensure AURA_INTERNAL_API_KEY is set and Aura accepts X-Internal-Service-Key. " +
                    "User call: ensure Authorization header is forwarded from Gateway.",
                    methodKey, reason
                );
            }
            return feign.FeignException.errorStatus(methodKey, response);
        };
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
