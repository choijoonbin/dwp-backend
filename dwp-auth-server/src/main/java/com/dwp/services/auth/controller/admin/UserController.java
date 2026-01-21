package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.users.UserManagementService;
import com.dwp.services.auth.service.rbac.PermissionEvaluator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 사용자 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UserController {
    
    private final UserManagementService userManagementService;
    private final PermissionEvaluator permissionEvaluator;
    
    /**
     * PR-09B: 사용자 목록 조회 (VIEW 권한 체크)
     * GET /api/admin/users
     */
    @GetMapping
    public ApiResponse<PageResponse<UserSummary>> getUsers(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String idpProviderType,
            @RequestParam(required = false) String loginType) {
        Long userId = getUserId(authentication);
        permissionEvaluator.requirePermission(userId, tenantId, "menu.admin.users", "VIEW");
        return ApiResponse.success(userManagementService.getUsers(
                tenantId, page, size, keyword, departmentId, roleId, status, idpProviderType, loginType));
    }
    
    /**
     * PR-09B: 사용자 생성 (EDIT 권한 체크)
     * POST /api/admin/users
     */
    @PostMapping
    public ApiResponse<UserDetail> createUser(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크 (CREATE → EDIT)
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        return ApiResponse.success(userManagementService.createUser(
                tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * PR-09B: 사용자 상세 조회 (VIEW 권한 체크)
     * GET /api/admin/users/{comUserId}
     */
    @GetMapping("/{comUserId}")
    public ApiResponse<UserDetail> getUserDetail(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: VIEW 권한 체크 (READ → VIEW)
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "VIEW");
        return ApiResponse.success(userManagementService.getUserDetail(tenantId, userId));
    }
    
    /**
     * PR-09B: 사용자 수정 (EDIT 권한 체크)
     * PUT /api/admin/users/{comUserId}
     */
    @PutMapping("/{comUserId}")
    public ApiResponse<UserDetail> updateUser(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            @Valid @RequestBody UpdateUserRequest request,
            HttpServletRequest httpRequest) {
        return updateUserInternal(tenantId, authentication, userId, request, httpRequest);
    }
    
    /**
     * PR-09B: 사용자 수정 (PATCH) (EDIT 권한 체크)
     * PATCH /api/admin/users/{comUserId}
     */
    @PatchMapping("/{comUserId}")
    public ApiResponse<UserDetail> patchUser(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            @Valid @RequestBody UpdateUserRequest request,
            HttpServletRequest httpRequest) {
        return updateUserInternal(tenantId, authentication, userId, request, httpRequest);
    }
    
    private ApiResponse<UserDetail> updateUserInternal(
            Long tenantId, Authentication authentication, Long userId,
            UpdateUserRequest request, HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        return ApiResponse.success(userManagementService.updateUser(
                tenantId, actorUserId, userId, request, httpRequest));
    }
    
    /**
     * PR-09B: 사용자 상태 변경 (EDIT 권한 체크)
     * POST /api/admin/users/{comUserId}/status
     */
    @PostMapping("/{comUserId}/status")
    public ApiResponse<UserDetail> updateUserStatus(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크 (UPDATE → EDIT)
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        return ApiResponse.success(userManagementService.updateUserStatus(
                tenantId, actorUserId, userId, request, httpRequest));
    }
    
    /**
     * PR-09B: 사용자 삭제 (EDIT 권한 체크)
     * DELETE /api/admin/users/{comUserId}
     */
    @DeleteMapping("/{comUserId}")
    public ApiResponse<Void> deleteUser(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크 (DELETE → EDIT)
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        userManagementService.deleteUser(tenantId, actorUserId, userId, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * PR-09B: 비밀번호 재설정 (EDIT 권한 체크)
     * POST /api/admin/users/{comUserId}/reset-password
     */
    @PostMapping("/{comUserId}/reset-password")
    public ApiResponse<ResetPasswordResponse> resetPassword(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            @RequestBody(required = false) ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        if (request == null) {
            request = ResetPasswordRequest.builder().build();
        }
        return ApiResponse.success(userManagementService.resetPassword(
                tenantId, actorUserId, userId, request, httpRequest));
    }
    
    /**
     * PR-09B: 사용자 역할 조회 (VIEW 권한 체크)
     * GET /api/admin/users/{comUserId}/roles
     */
    @GetMapping("/{comUserId}/roles")
    public ApiResponse<java.util.List<UserRoleInfo>> getUserRoles(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: VIEW 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "VIEW");
        return ApiResponse.success(userManagementService.getUserRoles(tenantId, userId));
    }
    
    /**
     * PR-09B: 사용자 역할 업데이트 (EDIT 권한 체크)
     * PUT /api/admin/users/{comUserId}/roles
     */
    @PutMapping("/{comUserId}/roles")
    public ApiResponse<Void> updateUserRoles(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            @Valid @RequestBody UpdateUserRolesRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        userManagementService.updateUserRoles(tenantId, actorUserId, userId, request, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * PR-09B: 사용자 역할 추가 (EDIT 권한 체크)
     * POST /api/admin/users/{comUserId}/roles
     */
    @PostMapping("/{comUserId}/roles")
    public ApiResponse<UserRoleInfo> addUserRole(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            @RequestBody java.util.Map<String, Long> request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        Long roleId = request.get("roleId");
        if (roleId == null) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.INVALID_INPUT_VALUE, "roleId는 필수입니다.");
        }
        return ApiResponse.success(userManagementService.addUserRole(
                tenantId, actorUserId, userId, roleId, httpRequest));
    }
    
    /**
     * PR-09B: 사용자 역할 삭제 (EDIT 권한 체크)
     * DELETE /api/admin/users/{comUserId}/roles/{comRoleId}
     */
    @DeleteMapping("/{comUserId}/roles/{comRoleId}")
    public ApiResponse<Void> removeUserRole(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comUserId") Long userId,
            @PathVariable("comRoleId") Long roleId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.users", "EDIT");
        userManagementService.removeUserRole(tenantId, actorUserId, userId, roleId, httpRequest);
        return ApiResponse.success(null);
    }
    
    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return Long.parseLong(((Jwt) authentication.getPrincipal()).getSubject());
        }
        return null;
    }
}
