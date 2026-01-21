package com.dwp.services.auth.config;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.config.AdminEndpointPolicyRegistry.RequiredPermission;
import com.dwp.services.auth.service.audit.AuditLogService;
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

import java.util.List;
import java.util.Map;

/**
 * Admin RBAC Enforcement Interceptor (Ultra Enhanced)
 * 
 * /api/admin/** 경로에 대한 Endpoint Policy Registry 기반 권한 검증을 수행합니다.
 * 
 * 정책 적용 방식:
 * 1) ADMIN인지 먼저 검사 (optional)
 * 2) registry에서 policy 찾기
 * 3) policy가 있으면 canAccess(userId, tenantId, resourceKey, permissionCode) 검사
 * 4) 없으면 기본 정책:
 *    - RELAX 모드: admin만 통과
 *    - STRICT 모드: deny (TODO)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminGuardInterceptor implements HandlerInterceptor {
    
    private final AdminGuardService adminGuardService;
    private final AdminEndpointPolicyRegistry endpointPolicyRegistry;
    private final AuditLogService auditLogService;
    
    @Override
    public boolean preHandle(@org.springframework.lang.NonNull HttpServletRequest request,
                            @org.springframework.lang.NonNull HttpServletResponse response,
                            @org.springframework.lang.NonNull Object handler) throws Exception {
        String path = request.getRequestURI();
        
        // /api/admin/** 또는 /admin/** 경로만 체크
        if (!path.startsWith("/api/admin/") && !path.startsWith("/admin/")) {
            return true;
        }
        
        // PR-01B: 표준화된 인증/권한 검증 흐름
        // 1) 인증 정보 추출 (Spring Security에서 이미 JWT 검증 완료)
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt)) {
            log.warn("Authentication required but not found: path={}", path);
            throw new BaseException(ErrorCode.AUTH_REQUIRED, "인증이 필요합니다.");
        }
        
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            log.warn("Invalid JWT subject: path={}, subject={}", path, jwt.getSubject());
            throw new BaseException(ErrorCode.TOKEN_INVALID, "유효하지 않은 토큰입니다.");
        }
        
        // 2) Tenant ID 추출 및 검증 (JWT 클레임 필수)
        Object tenantIdClaim = jwt.getClaim("tenant_id");
        Long tenantId = null;
        if (tenantIdClaim != null) {
            try {
                tenantId = Long.parseLong(tenantIdClaim.toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid tenant_id in JWT: path={}, tenantId={}", path, tenantIdClaim);
            }
        }
        
        if (tenantId == null) {
            log.warn("Tenant ID missing in JWT: path={}", path);
            throw new BaseException(ErrorCode.TENANT_MISSING, "테넌트 정보가 필요합니다.");
        }
        
        // 3) Tenant 격리 검증 (JWT tenant_id + X-Tenant-ID 헤더 일치 확인)
        // PR-01B 정책: 불일치 시 403 (FORBIDDEN)
        String headerTenantId = request.getHeader("X-Tenant-ID");
        if (headerTenantId != null) {
            try {
                Long headerTenantIdLong = Long.parseLong(headerTenantId);
                if (!tenantId.equals(headerTenantIdLong)) {
                    log.warn("Tenant ID mismatch: JWT tenantId={}, Header tenantId={}, path={}", 
                            tenantId, headerTenantIdLong, path);
                    throw new BaseException(ErrorCode.TENANT_MISMATCH, "테넌트 정보가 일치하지 않습니다.");
                }
            } catch (NumberFormatException e) {
                // 헤더 파싱 실패는 무시 (JWT 기준으로 진행)
            }
        }
        
        // Endpoint Policy Registry 기반 권한 검증 (Ultra Enhanced)
        String method = request.getMethod();
        List<RequiredPermission> policies = endpointPolicyRegistry.findPolicies(method, path);
        
        if (policies.isEmpty()) {
            // Policy 없음: RELAX 모드 (admin만 통과) 또는 STRICT 모드 (deny)
            AdminEndpointPolicyRegistry.PolicyMode mode = endpointPolicyRegistry.getMode();
            if (mode == AdminEndpointPolicyRegistry.PolicyMode.STRICT) {
                // TODO: STRICT 모드 구현 (policy 없으면 deny)
                log.warn("STRICT mode not implemented yet, falling back to RELAX: path={}", path);
            }
            // PR-01B: 표준화된 requireAdmin() 사용
            // RELAX 모드: admin만 통과
            adminGuardService.requireAdmin(tenantId, userId);
        } else {
            // Policy 있음: 각 policy에 대해 권한 검사
            boolean hasAccess = false;
            String deniedResourceKey = null;
            String deniedPermissionCode = null;
            
            for (RequiredPermission policy : policies) {
                boolean canAccess = adminGuardService.canAccess(userId, tenantId, 
                        policy.getResourceKey(), policy.getPermissionCode());
                
                if (canAccess) {
                    hasAccess = true;
                    break;  // 하나라도 허용되면 통과
                } else {
                    deniedResourceKey = policy.getResourceKey();
                    deniedPermissionCode = policy.getPermissionCode();
                }
            }
            
            if (!hasAccess) {
                // RBAC 실패 감사로그 기록
                recordRbacDenyAuditLog(tenantId, userId, method, path, 
                        deniedResourceKey, deniedPermissionCode, request);
                
                log.warn("RBAC access denied: tenantId={}, userId={}, method={}, path={}, resourceKey={}, permissionCode={}",
                        tenantId, userId, method, path, deniedResourceKey, deniedPermissionCode);
                throw new BaseException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
            }
        }
        
        return true;
    }
    
    /**
     * RBAC 실패 감사로그 기록
     */
    private void recordRbacDenyAuditLog(Long tenantId, Long userId, String method, String path,
                                       String resourceKey, String permissionCode,
                                       HttpServletRequest request) {
        try {
            // Map 기반 오버로드 호출 (resourceId, metadataMap)
            auditLogService.recordAuditLog(
                    tenantId,
                    userId,
                    "RBAC_DENY",
                    "RBAC",
                    null,  // resourceId
                    Map.of(
                            "method", method,
                            "path", path,
                            "resourceKey", resourceKey != null ? resourceKey : "N/A",
                            "permissionCode", permissionCode != null ? permissionCode : "N/A"
                    ),
                    request
            );
        } catch (Exception e) {
            log.error("Failed to record RBAC_DENY audit log", e);
        }
    }
}
