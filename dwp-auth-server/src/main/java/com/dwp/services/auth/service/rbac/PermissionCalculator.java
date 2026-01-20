package com.dwp.services.auth.service.rbac;

import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 권한 계산 핵심 로직 (CQRS 분리: Query 전용)
 * 
 * USER + DEPARTMENT role 합산, DENY 우선 정책을 구현합니다.
 * 테스트 대상: 권한 계산 로직의 정확성 검증
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionCalculator {
    
    private final RoleMemberRepository roleMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final CodeResolver codeResolver;
    
    /**
     * 사용자의 모든 역할 ID 조회 (USER + DEPARTMENT)
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     * @return 역할 ID 목록
     */
    public List<Long> getAllRoleIds(Long tenantId, Long userId) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElse(null);
        if (user == null) {
            return List.of();
        }
        
        Long departmentId = user.getPrimaryDepartmentId();
        if (departmentId != null) {
            // 부서 role 포함
            return roleMemberRepository.findAllRoleIdsByTenantIdAndUserIdAndDepartmentId(
                    tenantId, userId, departmentId);
        } else {
            // 부서 없으면 USER만
            return roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId);
        }
    }
    
    /**
     * resourceKey + permissionCode 기반 권한 검사 (DENY 우선 정책)
     * 
     * 권한 계산 정책:
     * 1) 사용자 직접 role 할당 (USER) + 부서 role 할당 (DEPARTMENT) 모두 포함
     * 2) DENY 우선: DENY가 하나라도 있으면 거부
     * 3) ALLOW 하나라도 있으면 허용 (DENY 없을 때)
     * 4) 아무것도 없으면 거부
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @param resourceKey 리소스 키 (예: menu.admin.users)
     * @param permissionCode 권한 코드 (예: VIEW, USE, EDIT, EXECUTE)
     * @return 권한이 있으면 true
     */
    public boolean canAccess(Long userId, Long tenantId, String resourceKey, String permissionCode) {
        // CodeResolver 기반 검증 (하드코딩 금지)
        codeResolver.require("PERMISSION_CODE", permissionCode);
        
        // 1. 리소스 조회 (resourceKey 기반)
        List<Resource> resources = resourceRepository.findByTenantIdAndKey(tenantId, resourceKey);
        if (resources.isEmpty()) {
            log.warn("Resource not found: tenantId={}, resourceKey={}", tenantId, resourceKey);
            return false;
        }
        Resource resource = resources.get(0);  // tenant-specific 우선
        
        // 2. 권한 조회 (permissionCode 기반)
        Permission permission = permissionRepository.findByCode(permissionCode)
                .orElse(null);
        if (permission == null) {
            log.warn("Permission not found: permissionCode={}", permissionCode);
            return false;
        }
        
        // 3. 사용자의 모든 역할 ID 조회 (USER + DEPARTMENT)
        List<Long> roleIds = getAllRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) {
            return false;
        }
        
        // 4. 역할-권한 매핑 조회
        List<RolePermission> rolePermissions = rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, roleIds);
        
        // 5. DENY 우선 정책 적용 (CodeResolver 기반 검증)
        String denyEffect = "DENY";
        String allowEffect = "ALLOW";
        codeResolver.require("EFFECT_TYPE", denyEffect);
        codeResolver.require("EFFECT_TYPE", allowEffect);
        
        boolean hasDeny = rolePermissions.stream()
                .anyMatch(rp -> rp.getResourceId().equals(resource.getResourceId()) &&
                               rp.getPermissionId().equals(permission.getPermissionId()) &&
                               denyEffect.equals(rp.getEffect()));
        
        if (hasDeny) {
            log.debug("Permission DENY: userId={}, tenantId={}, resourceKey={}, permissionCode={}",
                    userId, tenantId, resourceKey, permissionCode);
            return false;  // DENY 우선
        }
        
        // 6. ALLOW 확인
        boolean hasAllow = rolePermissions.stream()
                .anyMatch(rp -> rp.getResourceId().equals(resource.getResourceId()) &&
                               rp.getPermissionId().equals(permission.getPermissionId()) &&
                               allowEffect.equals(rp.getEffect()));
        
        log.debug("Permission check: userId={}, tenantId={}, resourceKey={}, permissionCode={}, hasAllow={}",
                userId, tenantId, resourceKey, permissionCode, hasAllow);
        
        return hasAllow;
    }
    
    /**
     * 사용자의 권한 Set 조회 (resourceKey, permissionCode, effect)
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @return 권한 Set<(resourceKey, permissionCode, effect)>
     */
    public Set<PermissionTuple> getPermissionSet(Long userId, Long tenantId) {
        // 1. 사용자의 모든 역할 ID 조회 (USER + DEPARTMENT)
        List<Long> roleIds = getAllRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        
        // 2. 역할-권한 매핑 조회
        List<RolePermission> rolePermissions = rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, roleIds);
        
        // 3. 리소스 및 권한 정보 조회
        Set<Long> resourceIds = rolePermissions.stream()
                .map(RolePermission::getResourceId)
                .collect(Collectors.toSet());
        Set<Long> permissionIds = rolePermissions.stream()
                .map(RolePermission::getPermissionId)
                .collect(Collectors.toSet());
        
        List<Resource> resources = resourceRepository.findByResourceIdIn(List.copyOf(resourceIds));
        List<Permission> permissions = permissionRepository.findByPermissionIdIn(List.copyOf(permissionIds));
        
        // 4. PermissionTuple Set 생성 (정렬 안정성 보장)
        // TreeSet을 사용하여 resourceKey + permissionCode 기준 정렬
        Set<PermissionTuple> permissionSet = new TreeSet<>(
                Comparator.comparing(PermissionTuple::getResourceKey)
                        .thenComparing(PermissionTuple::getPermissionCode)
                        .thenComparing(PermissionTuple::getEffect)
        );
        
        for (RolePermission rp : rolePermissions) {
            Resource resource = resources.stream()
                    .filter(r -> r.getResourceId().equals(rp.getResourceId()))
                    .findFirst()
                    .orElse(null);
            Permission permission = permissions.stream()
                    .filter(p -> p.getPermissionId().equals(rp.getPermissionId()))
                    .findFirst()
                    .orElse(null);
            
            if (resource != null && permission != null) {
                // CodeResolver 기반 effect 검증
                String effect = rp.getEffect();
                codeResolver.require("EFFECT_TYPE", effect);
                
                permissionSet.add(new PermissionTuple(
                        resource.getKey(),
                        permission.getCode(),
                        effect
                ));
            }
        }
        
        return permissionSet;
    }
    
    /**
     * 권한 튜플 (내부 클래스)
     */
    public static class PermissionTuple {
        private final String resourceKey;
        private final String permissionCode;
        private final String effect;
        
        public PermissionTuple(String resourceKey, String permissionCode, String effect) {
            this.resourceKey = resourceKey;
            this.permissionCode = permissionCode;
            this.effect = effect;
        }
        
        public String getResourceKey() {
            return resourceKey;
        }
        
        public String getPermissionCode() {
            return permissionCode;
        }
        
        public String getEffect() {
            return effect;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PermissionTuple that = (PermissionTuple) o;
            return resourceKey.equals(that.resourceKey) &&
                   permissionCode.equals(that.permissionCode) &&
                   effect.equals(that.effect);
        }
        
        @Override
        public int hashCode() {
            return resourceKey.hashCode() * 31 * 31 + permissionCode.hashCode() * 31 + effect.hashCode();
        }
    }
}
