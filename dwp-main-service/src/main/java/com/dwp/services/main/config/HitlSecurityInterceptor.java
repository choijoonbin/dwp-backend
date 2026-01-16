package com.dwp.services.main.config;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.main.util.JwtTokenValidator;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HITL 보안 인터셉터
 * 
 * HITL 작업(승인/거절) 시 JWT 권한을 재검증합니다.
 * 
 * 보안 가이드:
 * - 삭제, 메일 발송 등 중요한 작업은 반드시 JWT 권한을 재검증해야 합니다.
 * - 사용자가 직접 승인한 작업만 실행되도록 보장합니다.
 * 
 * 주의: JwtTokenValidator 빈이 있을 때만 등록됩니다.
 */
@Slf4j
@Component
@ConditionalOnBean(JwtTokenValidator.class)
public class HitlSecurityInterceptor implements HandlerInterceptor {
    
    private final JwtTokenValidator jwtTokenValidator;
    
    public HitlSecurityInterceptor(JwtTokenValidator jwtTokenValidator) {
        this.jwtTokenValidator = jwtTokenValidator;
    }
    
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_TENANT_ID = "X-Tenant-ID";
    private static final String HEADER_USER_ID = "X-User-ID";
    
    @Override
    @SuppressWarnings("null")
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {
        
        // HITL 엔드포인트만 검증
        String path = request.getRequestURI();
        if (!path.contains("/hitl/approve") && !path.contains("/hitl/reject")) {
            return true;  // HITL 엔드포인트가 아니면 통과
        }
        
        // Authorization 헤더 확인
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            log.warn("HITL request without Authorization header: {}", path);
            throw new BaseException(ErrorCode.UNAUTHORIZED, "Authorization header is required for HITL operations");
        }
        
        // JWT 토큰 추출 및 검증
        String token = authorization.substring(BEARER_PREFIX.length());
        Claims claims = jwtTokenValidator.validateToken(token);
        
        // JWT에서 추출한 정보
        String jwtTenantId = jwtTokenValidator.extractTenantId(token);
        String jwtUserId = jwtTokenValidator.extractUserId(token);
        
        // X-Tenant-ID 헤더 확인 (멀티테넌시)
        String headerTenantId = request.getHeader(HEADER_TENANT_ID);
        if (headerTenantId == null || headerTenantId.isEmpty()) {
            log.warn("HITL request without X-Tenant-ID header: {}", path);
            throw new BaseException(ErrorCode.UNAUTHORIZED, "X-Tenant-ID header is required");
        }
        
        // JWT의 tenant_id와 헤더의 X-Tenant-ID 일치 확인
        if (!jwtTenantId.equals(headerTenantId)) {
            log.warn("HITL request tenant ID mismatch: JWT={}, Header={}", jwtTenantId, headerTenantId);
            throw new BaseException(ErrorCode.FORBIDDEN, "Tenant ID mismatch between JWT and header");
        }
        
        // X-User-ID 헤더 확인
        String headerUserId = request.getHeader(HEADER_USER_ID);
        if (headerUserId == null || headerUserId.isEmpty()) {
            log.warn("HITL request without X-User-ID header: {}", path);
            throw new BaseException(ErrorCode.UNAUTHORIZED, "X-User-ID header is required");
        }
        
        // JWT의 sub와 헤더의 X-User-ID 일치 확인
        if (!jwtUserId.equals(headerUserId)) {
            log.warn("HITL request user ID mismatch: JWT={}, Header={}", jwtUserId, headerUserId);
            throw new BaseException(ErrorCode.FORBIDDEN, "User ID mismatch between JWT and header");
        }
        
        log.debug("HITL security check passed: path={}, tenantId={}, userId={}, claims={}", 
                path, headerTenantId, headerUserId, claims);
        
        return true;
    }
}
