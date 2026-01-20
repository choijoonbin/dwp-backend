package com.dwp.services.auth.service.rbac;

import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 권한 조회 서비스 (CQRS: Query 전용)
 * 
 * PermissionCalculator를 활용하여 권한 조회 로직을 담당합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@SuppressWarnings("null")
public class PermissionQueryService {
    
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionCalculator permissionCalculator;
    
    /**
     * 사용자의 권한 목록 조회
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @return 권한 목록
     */
    public List<PermissionDTO> getPermissions(Long userId, Long tenantId) {
        // 1. 사용자의 역할 ID 목록 조회
        List<Long> roleIds = permissionCalculator.getAllRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) {
            return List.of();
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
        
        // 4. PermissionDTO 목록 생성 (ALLOW만 필터링)
        return rolePermissions.stream()
                .filter(rp -> "ALLOW".equals(rp.getEffect()))
                .map(rp -> {
                    Resource resource = resources.stream()
                            .filter(r -> r.getResourceId().equals(rp.getResourceId()))
                            .findFirst()
                            .orElse(null);
                    Permission permission = permissions.stream()
                            .filter(p -> p.getPermissionId().equals(rp.getPermissionId()))
                            .findFirst()
                            .orElse(null);
                    
                    if (resource == null || permission == null) {
                        return null;
                    }
                    
                    return PermissionDTO.builder()
                            .resourceType(resource.getType())
                            .resourceKey(resource.getKey())
                            .resourceName(resource.getName())
                            .permissionCode(permission.getCode())
                            .permissionName(permission.getName())
                            .effect(rp.getEffect())
                            .resourceCategory(resource.getResourceCategory())
                            .resourceKind(resource.getResourceKind())
                            .eventKey(resource.getEventKey())
                            .trackingEnabled(resource.getTrackingEnabled())
                            .eventActions(resource.getEventActions())
                            .build();
                })
                .filter(dto -> dto != null)
                .collect(Collectors.toList());
    }
    
    /**
     * 사용자의 권한 Set 조회 (resourceKey, permissionCode, effect)
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @return 권한 Set<(resourceKey, permissionCode, effect)>
     */
    public Set<PermissionCalculator.PermissionTuple> getPermissionSet(Long userId, Long tenantId) {
        return permissionCalculator.getPermissionSet(userId, tenantId);
    }
}
