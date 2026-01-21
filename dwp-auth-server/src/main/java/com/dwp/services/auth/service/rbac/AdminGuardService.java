package com.dwp.services.auth.service.rbac;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Admin 권한 검증 서비스 (Enhanced: RBAC Enforcement)
 * 
 * /api/admin/** 엔드포인트 접근 시 ADMIN 역할을 가진 사용자인지 검증합니다.
 * 권한 조회는 PermissionQueryService로, 캐시 관리는 PermissionCacheManager로 위임합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGuardService {
    
    private final RoleMemberRepository roleMemberRepository;
    private final RoleRepository roleRepository;
    private final CodeResolver codeResolver;
    private final PermissionCalculator permissionCalculator;
    private final PermissionQueryService permissionQueryService;
    private final PermissionCacheManager permissionCacheManager;
    
    /**
     * 권한 튜플 (캐시용) - PermissionCalculator로 이동
     * @deprecated Use PermissionCalculator.PermissionTuple instead
     */
    @Deprecated
    private static class PermissionTuple {
        private final String resourceKey;
        private final String permissionCode;
        private final String effect;
        
        @Deprecated
        public PermissionTuple(String resourceKey, String permissionCode, String effect) {
            this.resourceKey = resourceKey;
            this.permissionCode = permissionCode;
            this.effect = effect;
        }
        
        @Deprecated
        public String getResourceKey() {
            return resourceKey;
        }
        
        @Deprecated
        public String getPermissionCode() {
            return permissionCode;
        }
        
        @Deprecated
        public String getEffect() {
            return effect;
        }
        
        @Override
        @Deprecated
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PermissionTuple that = (PermissionTuple) o;
            return resourceKey.equals(that.resourceKey) &&
                   permissionCode.equals(that.permissionCode) &&
                   effect.equals(that.effect);
        }
        
        @Override
        @Deprecated
        public int hashCode() {
            return resourceKey.hashCode() * 31 * 31 + permissionCode.hashCode() * 31 + effect.hashCode();
        }
    }
    
    /**
     * ADMIN 역할 코드 조회 (CodeResolver 기반, 하드코딩 제거)
     * 
     * TODO: 향후 sys_auth_policies 같은 정책 테이블로 확장 가능
     */
    private String getAdminRoleCode() {
        // ROLE_CODE 그룹에서 ADMIN 코드 조회
        List<String> roleCodes = codeResolver.getCodes("ROLE_CODE");
        String adminCode = roleCodes.stream()
                .filter(code -> "ADMIN".equalsIgnoreCase(code))
                .findFirst()
                .orElse("ADMIN");  // 기본값 (하위 호환)
        
        codeResolver.require("ROLE_CODE", adminCode);
        return adminCode;
    }
    
    /**
     * 사용자가 ADMIN 역할을 가지고 있는지 확인 (캐시 적용)
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     * @return ADMIN 역할을 가지고 있으면 true
     */
    @Transactional(readOnly = true)
    public boolean hasAdminRole(Long tenantId, Long userId) {
        String cacheKey = tenantId + ":" + userId;
        
        Boolean cached = permissionCacheManager.getAdminRole(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // 1. 사용자의 역할 ID 목록 조회
        List<Long> roleIds = roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId);
        
        if (roleIds.isEmpty()) {
            permissionCacheManager.putAdminRole(cacheKey, false);
            return false;
        }
        
        // 2. 역할 코드 조회
        List<Role> roles = roleRepository.findByRoleIdIn(roleIds);
        
        // 3. ADMIN 역할 코드 확인 (CodeResolver 사용, 하드코딩 제거)
        String adminRoleCode = getAdminRoleCode();
        
        boolean isAdmin = roles.stream()
                .anyMatch(role -> role.getTenantId().equals(tenantId) && 
                                adminRoleCode.equals(role.getCode()));
        
        log.debug("Admin role check: tenantId={}, userId={}, isAdmin={}", tenantId, userId, isAdmin);
        permissionCacheManager.putAdminRole(cacheKey, isAdmin);
        return isAdmin;
    }
    
    /**
     * ADMIN 역할 확인 (별칭, hasAdminRole과 동일)
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     * @return ADMIN 역할을 가지고 있으면 true
     */
    public boolean isAdmin(Long tenantId, Long userId) {
        return hasAdminRole(tenantId, userId);
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
            throw new BaseException(ErrorCode.ADMIN_FORBIDDEN, "관리자 권한이 필요합니다.");
        }
    }
    
    /**
     * Admin API 접근 권한 검증 (표준화된 단일 진입점)
     * 
     * PR-01B: AdminGuard Enforcement 표준화
     * - JWT 인증은 Spring Security에서 이미 완료된 상태
     * - Tenant 검증은 Interceptor에서 수행
     * - 이 메서드는 ADMIN 권한만 검증
     * 
     * @param tenantId 테넌트 ID (JWT 클레임에서 추출)
     * @param userId 사용자 ID (JWT subject에서 추출)
     * @throws BaseException ADMIN 권한이 없는 경우 FORBIDDEN (403)
     */
    public void requireAdmin(Long tenantId, Long userId) {
        requireAdminRole(tenantId, userId);
    }
    
    /**
     * resourceKey + permissionCode 기반 권한 검사 (Ultra Enhanced: DENY 우선, 부서 role 포함)
     * 
     * 권한 계산 정책:
     * 1) ADMIN이면 모든 권한 허용
     * 2) 사용자 직접 role 할당 (USER) + 부서 role 할당 (DEPARTMENT) 모두 포함
     * 3) DENY 우선: DENY가 하나라도 있으면 거부
     * 4) ALLOW 하나라도 있으면 허용 (DENY 없을 때)
     * 5) 아무것도 없으면 거부
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @param resourceKey 리소스 키 (예: menu.admin.users)
     * @param permissionCode 권한 코드 (예: VIEW, USE, EDIT, EXECUTE)
     * @return 권한이 있으면 true
     */
    @Transactional(readOnly = true)
    public boolean canAccess(Long userId, Long tenantId, String resourceKey, String permissionCode) {
        // 1. ADMIN이면 모든 권한 허용
        if (hasAdminRole(tenantId, userId)) {
            return true;
        }
        
        // 2. PermissionCalculator로 위임 (권한 계산 핵심 로직)
        return permissionCalculator.canAccess(userId, tenantId, resourceKey, permissionCode);
    }
    
    /**
     * 사용자의 권한 목록 조회 (캐시 적용)
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @return 권한 목록
     */
    @Transactional(readOnly = true)
    public List<PermissionDTO> getPermissions(Long userId, Long tenantId) {
        String cacheKey = tenantId + ":" + userId;
        
        List<PermissionDTO> cached = permissionCacheManager.getPermissions(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        List<PermissionDTO> permissions = permissionQueryService.getPermissions(userId, tenantId);
        permissionCacheManager.putPermissions(cacheKey, permissions);
        return permissions;
    }
    
    /**
     * 사용자의 권한 Set 조회 (캐시 적용, Ultra Enhanced)
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @return 권한 Set<(resourceKey, permissionCode, effect)>
     */
    @Transactional(readOnly = true)
    public Set<PermissionTuple> getPermissionSet(Long userId, Long tenantId) {
        String cacheKey = tenantId + ":" + userId;
        
        Set<PermissionCalculator.PermissionTuple> calculatorSet = permissionCacheManager.getPermissionSet(
                cacheKey, () -> permissionQueryService.getPermissionSet(userId, tenantId));
        
        // PermissionCalculator.PermissionTuple -> AdminGuardService.PermissionTuple 변환 (하위 호환)
        // 정렬 안정성 보장을 위해 TreeSet 사용
        Set<PermissionTuple> result = new TreeSet<>(
                Comparator.comparing(PermissionTuple::getResourceKey)
                        .thenComparing(PermissionTuple::getPermissionCode)
                        .thenComparing(PermissionTuple::getEffect)
        );
        for (PermissionCalculator.PermissionTuple tuple : calculatorSet) {
            result.add(new PermissionTuple(
                    tuple.getResourceKey(),
                    tuple.getPermissionCode(),
                    tuple.getEffect()
            ));
        }
        return result;
    }
    
    /**
     * 캐시 무효화 (RoleMember/RolePermission/Role 변경 시 호출)
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID (null이면 해당 tenant의 모든 캐시 무효화)
     */
    public void invalidateCache(Long tenantId, Long userId) {
        permissionCacheManager.invalidateCache(tenantId, userId);
    }
}
