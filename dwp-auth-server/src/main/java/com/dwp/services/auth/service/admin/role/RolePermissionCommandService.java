package com.dwp.services.auth.service.admin.role;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.UpdateRolePermissionsRequest;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 역할 권한 관리 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class RolePermissionCommandService {
    
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    
    /**
     * 역할 권한 업데이트 (Bulk Upsert/Delete) - resourceKey/permissionCode 기반
     * 
     * 정책:
     * - effect=null이면 해당 매핑 삭제
     * - effect="ALLOW" 또는 "DENY"이면 upsert
     */
    public void updateRolePermissions(Long tenantId, Long actorUserId, Long roleId,
                                     UpdateRolePermissionsRequest request, HttpServletRequest httpRequest) {
        // 역할 존재 확인
        roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // 기존 권한 조회 (삭제할 항목 찾기 위해)
        List<RolePermission> existingPermissions = rolePermissionRepository.findByTenantIdAndRoleId(tenantId, roleId);
        
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (UpdateRolePermissionsRequest.RolePermissionItem item : request.getItems()) {
                // 리소스 조회 (resourceKey 기반)
                List<Resource> resources = resourceRepository.findByTenantIdAndKey(tenantId, item.getResourceKey());
                if (resources.isEmpty()) {
                    throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                            String.format("리소스를 찾을 수 없습니다: resourceKey=%s", item.getResourceKey()));
                }
                Resource resource = resources.get(0);  // tenant-specific 우선
                
                // 권한 조회 (permissionCode 기반)
                Permission permission = permissionRepository.findByCode(item.getPermissionCode())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                                String.format("권한을 찾을 수 없습니다: permissionCode=%s", item.getPermissionCode())));
                
                // PERMISSION_CODE 검증
                codeResolver.require("PERMISSION_CODE", item.getPermissionCode());
                
                // effect=null이면 삭제
                if (item.getEffect() == null) {
                    existingPermissions.stream()
                            .filter(rp -> rp.getResourceId().equals(resource.getResourceId()) 
                                    && rp.getPermissionId().equals(permission.getPermissionId()))
                            .findFirst()
                            .ifPresent(rolePermissionRepository::delete);
                } else {
                    // effect 검증 (ALLOW/DENY)
                    codeResolver.require("EFFECT_TYPE", item.getEffect());
                    if (!"ALLOW".equals(item.getEffect()) && !"DENY".equals(item.getEffect())) {
                        throw new BaseException(ErrorCode.INVALID_CODE, "effect는 ALLOW 또는 DENY여야 합니다.");
                    }
                    
                    // 기존 권한 찾기 (upsert)
                    RolePermission existingPermission = existingPermissions.stream()
                            .filter(rp -> rp.getResourceId().equals(resource.getResourceId()) 
                                    && rp.getPermissionId().equals(permission.getPermissionId()))
                            .findFirst()
                            .orElse(null);
                    
                    if (existingPermission != null) {
                        // 업데이트
                        existingPermission.setEffect(item.getEffect());
                        rolePermissionRepository.save(existingPermission);
                    } else {
                        // 신규 생성
                        RolePermission rolePermission = RolePermission.builder()
                                .tenantId(tenantId)
                                .roleId(roleId)
                                .resourceId(resource.getResourceId())
                                .permissionId(permission.getPermissionId())
                                .effect(item.getEffect())
                                .build();
                        rolePermissionRepository.save(rolePermission);
                    }
                }
            }
        }
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_PERMISSION_BULK_UPDATE", "ROLE", roleId,
                null, request, httpRequest);
    }
}
