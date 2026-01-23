package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.codeusages.CodeUsageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 코드 사용 정의 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/code-usages")
@RequiredArgsConstructor
public class CodeUsageController {
    
    private final CodeUsageService codeUsageService;
    
    /**
     * PR-07A: 코드 사용 정의 목록 조회 고도화. P1-2: codeGroupKey 추가.
     * GET /api/admin/code-usages?resourceKey=&codeGroupKey=&keyword=&enabled=
     */
    @GetMapping
    public ApiResponse<PageResponse<CodeUsageSummary>> getCodeUsages(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) String codeGroupKey,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.success(codeUsageService.getCodeUsages(tenantId, page, size, resourceKey, codeGroupKey, keyword, enabled));
    }
    
    /**
     * P1-5: 코드 사용 정의 상세 조회
     * GET /api/admin/code-usages/{sysCodeUsageId}
     */
    @GetMapping("/{sysCodeUsageId}")
    public ApiResponse<CodeUsageDetail> getCodeUsageDetail(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @PathVariable Long sysCodeUsageId) {
        return ApiResponse.success(codeUsageService.getCodeUsageDetail(tenantId, sysCodeUsageId));
    }
    
    /**
     * 코드 사용 정의 생성
     * POST /api/admin/code-usages
     */
    @PostMapping
    public ApiResponse<CodeUsageSummary> createCodeUsage(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateCodeUsageRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(codeUsageService.createCodeUsage(tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * 코드 사용 정의 수정
     * PATCH /api/admin/code-usages/{sysCodeUsageId}
     */
    @PatchMapping("/{sysCodeUsageId}")
    public ApiResponse<CodeUsageSummary> updateCodeUsage(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long sysCodeUsageId,
            @Valid @RequestBody UpdateCodeUsageRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(codeUsageService.updateCodeUsage(tenantId, actorUserId, sysCodeUsageId, request, httpRequest));
    }
    
    /**
     * 코드 사용 정의 삭제
     * DELETE /api/admin/code-usages/{sysCodeUsageId}
     */
    @DeleteMapping("/{sysCodeUsageId}")
    public ApiResponse<Void> deleteCodeUsage(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable Long sysCodeUsageId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        codeUsageService.deleteCodeUsage(tenantId, actorUserId, sysCodeUsageId, httpRequest);
        return ApiResponse.success(null);
    }
    
    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return Long.parseLong(((Jwt) authentication.getPrincipal()).getSubject());
        }
        return null;
    }
}
