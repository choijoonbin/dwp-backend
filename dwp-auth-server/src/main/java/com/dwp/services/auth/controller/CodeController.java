package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.CodeGroupResponse;
import com.dwp.services.auth.dto.CodeResponse;
import com.dwp.services.auth.dto.admin.CodeUsageResponse;
import com.dwp.services.auth.dto.admin.CreateCodeGroupRequest;
import com.dwp.services.auth.dto.admin.CreateCodeRequest;
import com.dwp.services.auth.dto.admin.UpdateCodeGroupRequest;
import com.dwp.services.auth.dto.admin.UpdateCodeRequest;
import com.dwp.services.auth.service.CodeManagementService;
import com.dwp.services.auth.service.admin.codeusages.CodeUsageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 코드 관리 API 컨트롤러
 * 
 * Admin 화면에서 코드 조회 및 CRUD를 위한 API를 제공합니다.
 */
@RestController
@RequestMapping("/admin/codes")
@RequiredArgsConstructor
public class CodeController {
    
    private final CodeManagementService codeManagementService;
    private final CodeUsageService codeUsageService;
    
    /**
     * 코드 그룹 목록 조회
     * GET /api/admin/codes/groups
     */
    @GetMapping("/groups")
    public ApiResponse<List<CodeGroupResponse>> getGroups() {
        return ApiResponse.success(codeManagementService.getAllGroups());
    }
    
    /**
     * PR-06C: 그룹별 코드 목록 조회 (tenantScope 필터 지원)
     * GET /api/admin/codes?groupKey=RESOURCE_TYPE&tenantScope=ALL&enabled=true
     * 
     * tenantScope: COMMON | TENANT | ALL
     */
    @GetMapping
    public ApiResponse<?> getCodes(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(required = false) String groupKey,
            @RequestParam(required = false) String tenantScope,
            @RequestParam(required = false) Boolean enabled) {
        
        if (groupKey != null && !groupKey.isEmpty()) {
            return ApiResponse.success(codeManagementService.getCodesByGroup(groupKey, tenantId, tenantScope, enabled));
        }
        
        // groupKey가 없으면 모든 그룹의 코드를 맵으로 반환 (하위 호환성)
        Map<String, List<CodeResponse>> allCodes = codeManagementService.getAllCodesByGroup();
        return ApiResponse.success(allCodes);
    }
    
    /**
     * 모든 그룹의 코드를 맵으로 조회
     * GET /api/admin/codes/all
     */
    @GetMapping("/all")
    public ApiResponse<Map<String, List<CodeResponse>>> getAllCodes() {
        return ApiResponse.success(codeManagementService.getAllCodesByGroup());
    }
    
    /**
     * PR-06D: 메뉴별 코드 조회 (보안 강화)
     * GET /api/admin/codes/usage?resourceKey=menu.admin.users
     * 
     * 보안 검증:
     * - ADMIN 권한 필수
     * - resourceKey 접근 권한 (VIEW) 체크
     * - enabled된 code group만 반환
     */
    @GetMapping("/usage")
    public ApiResponse<CodeUsageResponse> getCodesByUsage(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @RequestParam("resourceKey") String resourceKey) {
        Long userId = getUserId(authentication);
        return ApiResponse.success(codeUsageService.getCodesByResourceKey(tenantId, userId, resourceKey));
    }
    
    /**
     * 메뉴별 사용 코드 그룹 목록 조회
     * GET /api/admin/codes/usage/groups?resourceKey=menu.admin.users
     */
    @GetMapping("/usage/groups")
    public ApiResponse<List<String>> getCodeGroupKeysByUsage(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam("resourceKey") String resourceKey) {
        return ApiResponse.success(codeUsageService.getCodeGroupKeysByResourceKey(tenantId, resourceKey));
    }
    
    /**
     * 코드 그룹 생성
     * POST /api/admin/codes/groups
     */
    @PostMapping("/groups")
    public ApiResponse<CodeGroupResponse> createCodeGroup(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateCodeGroupRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(codeManagementService.createCodeGroup(tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * 코드 그룹 수정
     * PUT /api/admin/codes/groups/{sysCodeGroupId}
     */
    @PutMapping("/groups/{sysCodeGroupId}")
    public ApiResponse<CodeGroupResponse> updateCodeGroup(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long sysCodeGroupId,
            @Valid @RequestBody UpdateCodeGroupRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(codeManagementService.updateCodeGroup(tenantId, actorUserId, sysCodeGroupId, request, httpRequest));
    }
    
    /**
     * 코드 그룹 삭제
     * DELETE /api/admin/codes/groups/{sysCodeGroupId}
     */
    @DeleteMapping("/groups/{sysCodeGroupId}")
    public ApiResponse<Void> deleteCodeGroup(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long sysCodeGroupId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        codeManagementService.deleteCodeGroup(tenantId, actorUserId, sysCodeGroupId, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * 코드 생성
     * POST /api/admin/codes
     */
    @PostMapping
    public ApiResponse<CodeResponse> createCode(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateCodeRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(codeManagementService.createCode(tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * 코드 수정
     * PUT /api/admin/codes/{sysCodeId}
     */
    @PutMapping("/{sysCodeId}")
    public ApiResponse<CodeResponse> updateCode(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long sysCodeId,
            @Valid @RequestBody UpdateCodeRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(codeManagementService.updateCode(tenantId, actorUserId, sysCodeId, request, httpRequest));
    }
    
    /**
     * 코드 삭제
     * DELETE /api/admin/codes/{sysCodeId}
     */
    @DeleteMapping("/{sysCodeId}")
    public ApiResponse<Void> deleteCode(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long sysCodeId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        codeManagementService.deleteCode(tenantId, actorUserId, sysCodeId, httpRequest);
        return ApiResponse.success(null);
    }
    
    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return Long.parseLong(((Jwt) authentication.getPrincipal()).getSubject());
        }
        return null;
    }
}
