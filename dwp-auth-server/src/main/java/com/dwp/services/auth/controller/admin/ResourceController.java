package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.resources.ResourceManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 리소스 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/resources")
@RequiredArgsConstructor
public class ResourceController {
    
    private final ResourceManagementService resourceManagementService;
    
    /**
     * 리소스 트리 조회
     * GET /api/admin/resources/tree
     */
    @GetMapping("/tree")
    public ApiResponse<List<ResourceSummary>> getResourceTree(
            @RequestHeader("X-Tenant-ID") Long tenantId) {
        return ApiResponse.success(resourceManagementService.getResourceTree(tenantId));
    }
    
    /**
     * P1-6: 리소스 상세 조회
     * GET /api/admin/resources/{comResourceId}
     */
    @GetMapping("/{comResourceId}")
    public ApiResponse<ResourceSummary> getResourceById(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @PathVariable("comResourceId") Long comResourceId) {
        return ApiResponse.success(resourceManagementService.getResourceById(tenantId, comResourceId));
    }
    
    /**
     * PR-04B: 리소스 목록 조회 (운영 수준). P1-3: resourceType alias for type.
     * GET /api/admin/resources?type=|resourceType=&keyword=&...
     */
    @GetMapping
    public ApiResponse<PageResponse<ResourceSummary>> getResources(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean trackingEnabled) {
        String effectiveType = type != null ? type : resourceType;
        return ApiResponse.success(resourceManagementService.getResources(
                tenantId, page, size, keyword, effectiveType, category, kind, parentId, enabled, trackingEnabled));
    }
    
    /**
     * 리소스 생성
     * POST /api/admin/resources
     */
    @PostMapping
    public ApiResponse<ResourceSummary> createResource(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateResourceRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(resourceManagementService.createResource(tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * 리소스 수정
     * PUT /api/admin/resources/{comResourceId}
     */
    @PutMapping("/{comResourceId}")
    public ApiResponse<ResourceSummary> updateResource(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comResourceId") Long resourceId,
            @Valid @RequestBody UpdateResourceRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(resourceManagementService.updateResource(tenantId, actorUserId, resourceId, request, httpRequest));
    }
    
    /**
     * 리소스 삭제
     * DELETE /api/admin/resources/{comResourceId}
     */
    @DeleteMapping("/{comResourceId}")
    public ApiResponse<Void> deleteResource(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comResourceId") Long resourceId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        resourceManagementService.deleteResource(tenantId, actorUserId, resourceId, httpRequest);
        return ApiResponse.success(null);
    }
    
    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return Long.parseLong(((Jwt) authentication.getPrincipal()).getSubject());
        }
        return null;
    }
}
