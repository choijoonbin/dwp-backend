package com.dwp.services.auth.service.admin.roles;

import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Role 관련 감사로그 헬퍼
 * 
 * Bulk 권한 변경 시 diff 추적 및 변경된 항목만 식별 가능하도록 지원
 */
@Slf4j
@Component
public class RoleAuditHelper {
    
    /**
     * 권한 변경 diff 생성
     * 
     * @param beforePermissions 변경 전 권한 목록
     * @param afterPermissions 변경 후 권한 목록
     * @param resourceMap 리소스 ID -> Resource 맵
     * @param permissionMap 권한 ID -> Permission 맵
     * @return 변경된 항목 목록 (changedOnly)
     */
    public List<Map<String, Object>> createPermissionDiff(
            List<RolePermission> beforePermissions,
            List<RolePermission> afterPermissions,
            Map<Long, Resource> resourceMap,
            Map<Long, Permission> permissionMap) {
        
        // 변경 전/후를 키로 변환 (resourceId + permissionId)
        Map<String, RolePermission> beforeMap = beforePermissions.stream()
                .collect(Collectors.toMap(
                    rp -> getPermissionKey(rp.getResourceId(), rp.getPermissionId()),
                    rp -> rp
                ));
        
        Map<String, RolePermission> afterMap = afterPermissions.stream()
                .collect(Collectors.toMap(
                    rp -> getPermissionKey(rp.getResourceId(), rp.getPermissionId()),
                    rp -> rp
                ));
        
        List<Map<String, Object>> changedOnly = new ArrayList<>();
        
        // 추가된 항목
        for (RolePermission after : afterPermissions) {
            String key = getPermissionKey(after.getResourceId(), after.getPermissionId());
            RolePermission before = beforeMap.get(key);
            
            if (before == null) {
                // 신규 추가
                Map<String, Object> change = new HashMap<>();
                change.put("resourceKey", getResourceKey(after.getResourceId(), resourceMap));
                change.put("permissionCode", getPermissionCode(after.getPermissionId(), permissionMap));
                change.put("beforeEffect", null);
                change.put("afterEffect", after.getEffect());
                change.put("changeType", "ADDED");
                changedOnly.add(change);
            } else if (!Objects.equals(before.getEffect(), after.getEffect())) {
                // 효과 변경 (DENY → ALLOW 등)
                Map<String, Object> change = new HashMap<>();
                change.put("resourceKey", getResourceKey(after.getResourceId(), resourceMap));
                change.put("permissionCode", getPermissionCode(after.getPermissionId(), permissionMap));
                change.put("beforeEffect", before.getEffect());
                change.put("afterEffect", after.getEffect());
                change.put("changeType", "UPDATED");
                changedOnly.add(change);
            }
        }
        
        // 삭제된 항목
        for (RolePermission before : beforePermissions) {
            String key = getPermissionKey(before.getResourceId(), before.getPermissionId());
            if (!afterMap.containsKey(key)) {
                Map<String, Object> change = new HashMap<>();
                change.put("resourceKey", getResourceKey(before.getResourceId(), resourceMap));
                change.put("permissionCode", getPermissionCode(before.getPermissionId(), permissionMap));
                change.put("beforeEffect", before.getEffect());
                change.put("afterEffect", null);
                change.put("changeType", "REMOVED");
                changedOnly.add(change);
            }
        }
        
        return changedOnly;
    }
    
    /**
     * 권한 목록을 간소화된 형태로 변환 (핵심 필드만)
     * 
     * @param permissions 권한 목록
     * @param resourceMap 리소스 ID -> Resource 맵
     * @param permissionMap 권한 ID -> Permission 맵
     * @return 간소화된 권한 목록
     */
    public List<Map<String, Object>> simplifyPermissions(
            List<RolePermission> permissions,
            Map<Long, Resource> resourceMap,
            Map<Long, Permission> permissionMap) {
        
        return permissions.stream()
                .map(rp -> {
                    Map<String, Object> simplified = new HashMap<>();
                    simplified.put("resourceKey", getResourceKey(rp.getResourceId(), resourceMap));
                    simplified.put("permissionCode", getPermissionCode(rp.getPermissionId(), permissionMap));
                    simplified.put("effect", rp.getEffect());
                    return simplified;
                })
                .collect(Collectors.toList());
    }
    
    private String getPermissionKey(Long resourceId, Long permissionId) {
        return resourceId + ":" + permissionId;
    }
    
    private String getResourceKey(Long resourceId, Map<Long, Resource> resourceMap) {
        Resource resource = resourceMap.get(resourceId);
        return resource != null ? resource.getKey() : "unknown:" + resourceId;
    }
    
    private String getPermissionCode(Long permissionId, Map<Long, Permission> permissionMap) {
        Permission permission = permissionMap.get(permissionId);
        return permission != null ? permission.getCode() : "unknown:" + permissionId;
    }
}
