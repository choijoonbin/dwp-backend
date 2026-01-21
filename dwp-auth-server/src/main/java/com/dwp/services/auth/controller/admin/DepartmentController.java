package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.departments.DepartmentManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 부서 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/departments")
@RequiredArgsConstructor
public class DepartmentController {
    
    private final DepartmentManagementService departmentManagementService;
    
    /**
     * 부서 목록 조회
     * GET /api/admin/departments
     */
    @GetMapping
    public ApiResponse<PageResponse<DepartmentSummary>> getDepartments(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(departmentManagementService.getDepartments(tenantId, page, size, keyword));
    }
    
    /**
     * 부서 생성
     * POST /api/admin/departments
     */
    @PostMapping
    public ApiResponse<DepartmentSummary> createDepartment(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateDepartmentRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(departmentManagementService.createDepartment(
                tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * 부서 수정
     * PUT /api/admin/departments/{departmentId}
     */
    @PutMapping("/{departmentId}")
    public ApiResponse<DepartmentSummary> updateDepartment(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long departmentId,
            @Valid @RequestBody UpdateDepartmentRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(departmentManagementService.updateDepartment(
                tenantId, actorUserId, departmentId, request, httpRequest));
    }
    
    /**
     * 부서 삭제
     * DELETE /api/admin/departments/{departmentId}
     */
    @DeleteMapping("/{departmentId}")
    public ApiResponse<Void> deleteDepartment(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long departmentId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        departmentManagementService.deleteDepartment(tenantId, actorUserId, departmentId, httpRequest);
        return ApiResponse.success(null);
    }
    
    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return Long.parseLong(((Jwt) authentication.getPrincipal()).getSubject());
        }
        return null;
    }
}
