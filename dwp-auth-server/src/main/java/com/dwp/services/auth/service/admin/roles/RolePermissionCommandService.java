package com.dwp.services.auth.service.admin.roles;

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
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.service.rbac.AdminGuardService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

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
    private final RoleMemberRepository roleMemberRepository;
    private final UserRepository userRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    private final AdminGuardService adminGuardService;
    private final RoleAuditHelper roleAuditHelper;
    
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
        
        // 기존 권한 조회 (감사로그 diff 추적용)
        List<RolePermission> beforePermissions = new ArrayList<>(
                rolePermissionRepository.findByTenantIdAndRoleId(tenantId, roleId));
        
        // 리소스/권한 맵 생성 (감사로그용)
        Set<Long> resourceIds = new HashSet<>();
        Set<Long> permissionIds = new HashSet<>();
        beforePermissions.forEach(rp -> {
            resourceIds.add(rp.getResourceId());
            permissionIds.add(rp.getPermissionId());
        });
        
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (UpdateRolePermissionsRequest.RolePermissionItem item : request.getItems()) {
                // PR-03D: resourceKey 검증 (없으면 400)
                if (item.getResourceKey() == null || item.getResourceKey().trim().isEmpty()) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "resourceKey는 필수입니다.");
                }
                
                // 리소스 조회 (resourceKey 기반)
                List<Resource> resources = resourceRepository.findByTenantIdAndKey(tenantId, item.getResourceKey());
                if (resources.isEmpty()) {
                    throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                            String.format("리소스를 찾을 수 없습니다: resourceKey=%s", item.getResourceKey()));
                }
                Resource resource = resources.get(0);  // tenant-specific 우선
                resourceIds.add(resource.getResourceId());
                
                // PR-03D: permissionCode 검증
                if (item.getPermissionCode() == null || item.getPermissionCode().trim().isEmpty()) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "permissionCode는 필수입니다.");
                }
                
                // 권한 조회 (permissionCode 기반)
                Permission permission = permissionRepository.findByCode(item.getPermissionCode())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                                String.format("권한을 찾을 수 없습니다: permissionCode=%s", item.getPermissionCode())));
                permissionIds.add(permission.getPermissionId());
                
                // PR-03D: 코드 유효성 검증 (CodeResolver)
                codeResolver.require("PERMISSION_CODE", item.getPermissionCode());
                
                // effect=null이면 삭제
                if (item.getEffect() == null) {
                    beforePermissions.stream()
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
                    RolePermission existingPermission = beforePermissions.stream()
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
        
        // 변경 후 권한 조회 (diff 추적용)
        List<RolePermission> afterPermissions = rolePermissionRepository.findByTenantIdAndRoleId(tenantId, roleId);
        
        // 리소스/권한 맵 생성 (감사로그 diff 추적용)
        Map<Long, Resource> resourceMap = new HashMap<>();
        Map<Long, Permission> permissionMap = new HashMap<>();
        
        if (!resourceIds.isEmpty()) {
            resourceMap = resourceRepository.findByResourceIdIn(new ArrayList<>(resourceIds))
                    .stream()
                    .collect(Collectors.toMap(Resource::getResourceId, r -> r));
        }
        
        if (!permissionIds.isEmpty()) {
            permissionMap = permissionRepository.findByPermissionIdIn(new ArrayList<>(permissionIds))
                    .stream()
                    .collect(Collectors.toMap(Permission::getPermissionId, p -> p));
        }
        
        // PR-03E: 캐시 무효화 (변경 즉시 반영)
        // 해당 역할을 가진 모든 사용자의 캐시 무효화
        invalidateRolePermissionCache(tenantId, roleId);
        
        // 감사 로그 (diff 추적 강화)
        Map<String, Object> auditMetadata = new HashMap<>();
        
        // 변경 전/후 간소화된 권한 목록
        auditMetadata.put("before", roleAuditHelper.simplifyPermissions(beforePermissions, resourceMap, permissionMap));
        auditMetadata.put("after", roleAuditHelper.simplifyPermissions(afterPermissions, resourceMap, permissionMap));
        
        // 변경된 항목만 식별 가능하도록 diff 생성
        List<Map<String, Object>> changedOnly = roleAuditHelper.createPermissionDiff(
                beforePermissions, afterPermissions, resourceMap, permissionMap);
        auditMetadata.put("changedOnly", changedOnly);
        auditMetadata.put("changeCount", changedOnly.size());
        
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_PERMISSION_BULK_UPDATE", 
                "ROLE_PERMISSION", roleId, auditMetadata, httpRequest);
    }
    
    /**
     * PR-03E: 역할 권한 변경 시 캐시 무효화
     * 
     * 해당 역할을 가진 모든 사용자의 캐시를 무효화합니다.
     */
    private void invalidateRolePermissionCache(Long tenantId, Long roleId) {
        // 해당 역할을 가진 모든 멤버 조회
        List<com.dwp.services.auth.entity.RoleMember> members = roleMemberRepository.findByTenantIdAndRoleId(tenantId, roleId);
        for (com.dwp.services.auth.entity.RoleMember member : members) {
            if ("USER".equals(member.getSubjectType())) {
                adminGuardService.invalidateCache(tenantId, member.getSubjectId());
            } else if ("DEPARTMENT".equals(member.getSubjectType())) {
                // 부서의 모든 사용자 캐시 무효화
                List<com.dwp.services.auth.entity.User> users = userRepository.findByTenantIdAndPrimaryDepartmentId(tenantId, member.getSubjectId());
                for (com.dwp.services.auth.entity.User user : users) {
                    if (user.getUserId() != null) {
                        adminGuardService.invalidateCache(tenantId, user.getUserId());
                    }
                }
            }
        }
    }
}
