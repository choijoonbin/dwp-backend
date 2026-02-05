package com.dwp.core.config;

import com.dwp.core.constant.HeaderConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * FeignClient 요청 시 표준 헤더를 자동으로 전파하는 Interceptor
 * 
 * DWP 플랫폼 계약 준수:
 * - Authorization: JWT 토큰 (인증)
 * - X-Tenant-ID: 멀티테넌트 식별자 (데이터 격리)
 * - X-User-ID: 사용자 식별자 (사용자 컨텍스트)
 * - X-Agent-ID: AI 에이전트 세션/클라이언트 식별자 (에이전트 추적)
 * - X-DWP-Source: 요청 출처 (AURA, FRONTEND, INTERNAL, BATCH)
 * - X-DWP-Caller-Type: 호출자 타입 (USER, AGENT, SYSTEM)
 * 
 * 헤더 전파 규칙:
 * - 헤더가 존재하는 경우에만 전파 (null 주입 금지)
 * - 비동기 호출 등으로 RequestContext가 없는 경우 안전하게 처리
 * - 로그에 전파 헤더 기록 (디버깅 용이성)
 * 
 * Auto-Configuration:
 * - CoreFeignAutoConfiguration에서 자동 등록
 * - @EnableFeignClients가 있는 서비스에만 적용
 */
@Slf4j
public class FeignHeaderInterceptor implements RequestInterceptor {
    
    @Override
    public void apply(RequestTemplate template) {
        // 현재 HTTP 요청 컨텍스트에서 헤더 추출
        ServletRequestAttributes attributes;
        try {
            attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        } catch (IllegalStateException e) {
            // 비동기 호출 등으로 RequestContext가 없는 경우
            log.debug("No request context available for Feign header propagation (async call or scheduler)");
            return;
        }
        
        if (attributes == null) {
            log.debug("No request context available for Feign header propagation");
            return;
        }
        
        HttpServletRequest request = attributes.getRequest();
        
        // HeaderConstants.REQUIRED_PROPAGATION_HEADERS 목록에 있는 모든 헤더 전파
        for (String headerName : HeaderConstants.REQUIRED_PROPAGATION_HEADERS) {
            String headerValue = request.getHeader(headerName);
            if (headerValue != null && !headerValue.isEmpty()) {
                template.header(headerName, headerValue);
                
                // Authorization 헤더는 민감 정보이므로 마스킹
                if (HeaderConstants.AUTHORIZATION.equals(headerName)) {
                    log.debug("Propagating header: {} = Bearer ***", headerName);
                } else {
                    log.debug("Propagating header: {} = {}", headerName, headerValue);
                }
            }
        }
        
        // 전파된 헤더 요약 로그 (INFO 레벨)
        if (log.isInfoEnabled()) {
            long propagatedCount = HeaderConstants.REQUIRED_PROPAGATION_HEADERS.stream()
                .filter(h -> request.getHeader(h) != null && !request.getHeader(h).isEmpty())
                .count();
            log.info("Feign header propagation: {}/{} headers propagated to downstream service",
                    propagatedCount, HeaderConstants.REQUIRED_PROPAGATION_HEADERS.size());
        }
    }
}
