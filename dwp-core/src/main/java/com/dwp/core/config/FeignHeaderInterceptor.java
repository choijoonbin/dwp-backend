package com.dwp.core.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * FeignClient 요청 시 공통 헤더를 자동으로 전파하는 Interceptor
 * 
 * AI 에이전트(Aura)가 사용자를 대신해 API를 호출할 때 필요한 헤더:
 * - X-DWP-Source: 요청의 출처 (AURA, FRONTEND, INTERNAL 등)
 * - X-Tenant-ID: 멀티테넌시 환경에서 테넌트 식별자
 * - Authorization: JWT 토큰 전파
 */
@Slf4j
@Component
public class FeignHeaderInterceptor implements RequestInterceptor {
    
    // 전파할 헤더 목록
    private static final String HEADER_SOURCE = "X-DWP-Source";
    private static final String HEADER_TENANT_ID = "X-Tenant-ID";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_USER_ID = "X-User-ID";
    
    @Override
    public void apply(RequestTemplate template) {
        // 현재 HTTP 요청 컨텍스트에서 헤더 추출
        ServletRequestAttributes attributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        
        if (attributes == null) {
            log.debug("No request context available for Feign header propagation");
            return;
        }
        
        HttpServletRequest request = attributes.getRequest();
        
        // X-DWP-Source 헤더 전파 (AI 에이전트 식별)
        String source = request.getHeader(HEADER_SOURCE);
        if (source != null && !source.isEmpty()) {
            template.header(HEADER_SOURCE, source);
            log.debug("Propagating header: {} = {}", HEADER_SOURCE, source);
        }
        
        // X-Tenant-ID 헤더 전파 (멀티테넌시)
        String tenantId = request.getHeader(HEADER_TENANT_ID);
        if (tenantId != null && !tenantId.isEmpty()) {
            template.header(HEADER_TENANT_ID, tenantId);
            log.debug("Propagating header: {} = {}", HEADER_TENANT_ID, tenantId);
        }
        
        // Authorization 헤더 전파 (JWT 토큰)
        String authorization = request.getHeader(HEADER_AUTHORIZATION);
        if (authorization != null && !authorization.isEmpty()) {
            template.header(HEADER_AUTHORIZATION, authorization);
            log.debug("Propagating header: {} = Bearer ***", HEADER_AUTHORIZATION);
        }
        
        // X-User-ID 헤더 전파 (사용자 식별)
        String userId = request.getHeader(HEADER_USER_ID);
        if (userId != null && !userId.isEmpty()) {
            template.header(HEADER_USER_ID, userId);
            log.debug("Propagating header: {} = {}", HEADER_USER_ID, userId);
        }
    }
}
