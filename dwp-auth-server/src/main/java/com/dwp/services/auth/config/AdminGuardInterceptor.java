package com.dwp.services.auth.config;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.rbac.AdminGuardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Admin 권한 검증 Interceptor
 * 
 * /api/admin/** 경로에 대한 ADMIN 역할 검증을 수행합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminGuardInterceptor implements HandlerInterceptor {
    
    private final AdminGuardService adminGuardService;
    
    @Override
    public boolean preHandle(@org.springframework.lang.NonNull HttpServletRequest request,
                            @org.springframework.lang.NonNull HttpServletResponse response,
                            @org.springframework.lang.NonNull Object handler) throws Exception {
        String path = request.getRequestURI();
        
        // /api/admin/** 또는 /admin/** 경로만 체크
        if (!path.startsWith("/api/admin/") && !path.startsWith("/admin/")) {
            return true;
        }
        
        // 인증 정보 추출
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt)) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "인증이 필요합니다.");
        }
        
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Long userId = Long.parseLong(jwt.getSubject());
        Object tenantIdClaim = jwt.getClaim("tenant_id");
        Long tenantId = tenantIdClaim != null ? Long.parseLong(tenantIdClaim.toString()) : null;
        
        if (tenantId == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "테넌트 정보가 없습니다.");
        }
        
        // ADMIN 권한 검증
        adminGuardService.requireAdminRole(tenantId, userId);
        
        return true;
    }
}
