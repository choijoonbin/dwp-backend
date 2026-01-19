package com.dwp.services.auth.service.rbac;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin 권한 검증 서비스
 * 
 * /api/admin/** 엔드포인트 접근 시 ADMIN 역할을 가진 사용자인지 검증합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGuardService {
    
    private final RoleMemberRepository roleMemberRepository;
    private final RoleRepository roleRepository;
    private final CodeResolver codeResolver;
    
    /**
     * 사용자가 ADMIN 역할을 가지고 있는지 확인
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     * @return ADMIN 역할을 가지고 있으면 true
     */
    @Transactional(readOnly = true)
    public boolean hasAdminRole(Long tenantId, Long userId) {
        // 1. 사용자의 역할 ID 목록 조회
        List<Long> roleIds = roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId);
        
        if (roleIds.isEmpty()) {
            return false;
        }
        
        // 2. 역할 코드 조회
        List<com.dwp.services.auth.entity.Role> roles = roleRepository.findByRoleIdIn(roleIds);
        
        // 3. ADMIN 역할 코드 확인 (CodeResolver 사용)
        String adminRoleCode = "ADMIN";
        codeResolver.require("ROLE_CODE", adminRoleCode);
        
        return roles.stream()
                .anyMatch(role -> role.getTenantId().equals(tenantId) && 
                                adminRoleCode.equals(role.getCode()));
    }
    
    /**
     * ADMIN 역할 검증 (없으면 예외 발생)
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     * @throws BaseException ADMIN 역할이 없는 경우
     */
    public void requireAdminRole(Long tenantId, Long userId) {
        if (!hasAdminRole(tenantId, userId)) {
            log.warn("Admin role required but user does not have it: tenantId={}, userId={}", tenantId, userId);
            throw new BaseException(ErrorCode.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
    }
}
