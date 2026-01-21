package com.dwp.services.auth.service.admin.roles;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.CreateRoleRequest;
import com.dwp.services.auth.dto.admin.RoleDetail;
import com.dwp.services.auth.dto.admin.UpdateRoleRequest;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 역할 변경 서비스 (CQRS: Command 전용)
 * 
 * 역할 CRUD만 담당합니다.
 * 역할 멤버/권한 관리는 RoleMemberCommandService, RolePermissionCommandService로 위임합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class RoleCommandService {
    
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    private final RoleQueryService roleQueryService;
    
    /**
     * 역할 생성
     */
    @Transactional
    public RoleDetail createRole(Long tenantId, Long actorUserId, CreateRoleRequest request,
                                HttpServletRequest httpRequest) {
        // 역할 코드 중복 체크
        roleRepository.findByTenantIdAndCode(tenantId, request.getRoleCode())
                .ifPresent(r -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 역할 코드입니다.");
                });
        
        // ROLE_CODE 검증
        codeResolver.require("ROLE_CODE", request.getRoleCode());
        
        Role role = Role.builder()
                .tenantId(tenantId)
                .code(request.getRoleCode())
                .name(request.getRoleName())
                .description(request.getDescription())
                .build();
        role = roleRepository.save(role);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_CREATE", "ROLE", role.getRoleId(),
                null, role, httpRequest);
        
        return roleQueryService.getRoleDetail(tenantId, role.getRoleId());
    }
    
    /**
     * 역할 수정
     */
    @Transactional
    public RoleDetail updateRole(Long tenantId, Long actorUserId, Long roleId, UpdateRoleRequest request,
                                HttpServletRequest httpRequest) {
        Role role = roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // ADMIN 역할 삭제 금지
        String adminRoleCode = "ADMIN";
        codeResolver.require("ROLE_CODE", adminRoleCode);
        if (adminRoleCode.equals(role.getCode())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "ADMIN 역할은 수정할 수 없습니다.");
        }
        
        Role before = copyRole(role);
        
        if (request.getRoleCode() != null) {
            // 역할 코드 중복 체크 (본인 제외)
            roleRepository.findByTenantIdAndCode(tenantId, request.getRoleCode())
                    .filter(r -> !r.getRoleId().equals(roleId))
                    .ifPresent(r -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 역할 코드입니다.");
                    });
            codeResolver.require("ROLE_CODE", request.getRoleCode());
            role.setCode(request.getRoleCode());
        }
        if (request.getRoleName() != null) {
            role.setName(request.getRoleName());
        }
        if (request.getDescription() != null) {
            role.setDescription(request.getDescription());
        }
        
        role = roleRepository.save(role);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_UPDATE", "ROLE", roleId,
                before, role, httpRequest);
        
        return roleQueryService.getRoleDetail(tenantId, roleId);
    }
    
    /**
     * 역할 삭제
     * 
     * PR-03F: 삭제 충돌 정책 (409)
     * - members/permissions 존재하면 409 ROLE_IN_USE 반환
     * - soft delete 권장이지만 현재는 hard delete (향후 개선)
     */
    @Transactional
    public void deleteRole(Long tenantId, Long actorUserId, Long roleId, HttpServletRequest httpRequest) {
        Role role = roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // ADMIN 역할 삭제 금지
        String adminRoleCode = "ADMIN";
        codeResolver.require("ROLE_CODE", adminRoleCode);
        if (adminRoleCode.equals(role.getCode())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "ADMIN 역할은 삭제할 수 없습니다.");
        }
        
        // PR-03F: members/permissions 존재 확인
        long memberCount = roleMemberRepository.countByTenantIdAndRoleId(tenantId, roleId);
        long permissionCount = rolePermissionRepository.countByTenantIdAndRoleId(tenantId, roleId);
        
        if (memberCount > 0 || permissionCount > 0) {
            throw new BaseException(ErrorCode.ROLE_IN_USE, 
                    String.format("역할이 사용 중입니다. 멤버(%d명)나 권한(%d개)을 먼저 제거해주세요.", memberCount, permissionCount));
        }
        
        Role before = copyRole(role);
        
        // 역할 삭제 (members/permissions가 없으므로 안전)
        roleRepository.delete(role);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_DELETE", "ROLE", roleId,
                before, null, httpRequest);
    }
    
    
    private Role copyRole(Role role) {
        return Role.builder()
                .roleId(role.getRoleId())
                .tenantId(role.getTenantId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .build();
    }
}
