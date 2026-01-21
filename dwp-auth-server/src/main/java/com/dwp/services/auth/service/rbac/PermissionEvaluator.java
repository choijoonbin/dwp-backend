package com.dwp.services.auth.service.rbac;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-09A: 권한 체크 유틸(표준)
 * 
 * resourceKey + permissionCode 기반 권한 검증을 제공합니다.
 * 권한이 없으면 예외를 발생시켜 enforcement를 보장합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionEvaluator {
    
    private final AdminGuardService adminGuardService;
    
    /**
     * PR-09A: 권한 검증 (없으면 예외 발생)
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @param resourceKey 리소스 키 (예: menu.admin.users)
     * @param permissionCode 권한 코드 (예: VIEW, EDIT, DELETE)
     * @throws BaseException 권한이 없는 경우 FORBIDDEN (403)
     */
    public void requirePermission(Long userId, Long tenantId, String resourceKey, String permissionCode) {
        if (!adminGuardService.canAccess(userId, tenantId, resourceKey, permissionCode)) {
            log.warn("Permission denied: userId={}, tenantId={}, resourceKey={}, permissionCode={}",
                    userId, tenantId, resourceKey, permissionCode);
            throw new BaseException(ErrorCode.FORBIDDEN,
                    String.format("권한이 없습니다: resourceKey=%s, permissionCode=%s", resourceKey, permissionCode));
        }
    }
    
    /**
     * 권한 확인 (예외 없이 boolean 반환)
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @param resourceKey 리소스 키
     * @param permissionCode 권한 코드
     * @return 권한이 있으면 true
     */
    public boolean hasPermission(Long userId, Long tenantId, String resourceKey, String permissionCode) {
        return adminGuardService.canAccess(userId, tenantId, resourceKey, permissionCode);
    }
}
