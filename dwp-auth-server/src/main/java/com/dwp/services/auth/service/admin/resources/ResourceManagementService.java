package com.dwp.services.auth.service.admin.resources;

import com.dwp.services.auth.dto.admin.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 리소스 관리 서비스 (Facade)
 * 
 * 기존 API 호환성을 유지하기 위한 Facade 패턴 적용
 * 실제 로직은 ResourceQueryService, ResourceCommandService로 위임
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ResourceManagementService {
    
    private final ResourceQueryService resourceQueryService;
    private final ResourceCommandService resourceCommandService;
    
    /**
     * 리소스 트리 조회 (관리용)
     */
    public List<ResourceSummary> getResourceTree(Long tenantId) {
        return resourceQueryService.getResourceTree(tenantId);
    }
    
    /**
     * PR-04B: 리소스 목록 조회 (운영 수준)
     */
    public PageResponse<ResourceSummary> getResources(Long tenantId, int page, int size,
                                                      String keyword, String type, String category, 
                                                      String kind, Long parentId, Boolean enabled, Boolean trackingEnabled) {
        return resourceQueryService.getResources(tenantId, page, size, keyword, type, category, kind, parentId, enabled, trackingEnabled);
    }
    
    /**
     * PR-04C: 리소스 생성 (운영 수준)
     */
    public ResourceSummary createResource(Long tenantId, Long actorUserId, CreateResourceRequest request,
                                         HttpServletRequest httpRequest) {
        return resourceCommandService.createResource(tenantId, actorUserId, request, httpRequest);
    }
    
    /**
     * PR-04D: 리소스 수정 (운영 수준)
     */
    public ResourceSummary updateResource(Long tenantId, Long actorUserId, Long resourceId,
                                         UpdateResourceRequest request, HttpServletRequest httpRequest) {
        return resourceCommandService.updateResource(tenantId, actorUserId, resourceId, request, httpRequest);
    }
    
    /**
     * PR-04E: 리소스 삭제 (Soft Delete)
     */
    public void deleteResource(Long tenantId, Long actorUserId, Long resourceId, HttpServletRequest httpRequest) {
        resourceCommandService.deleteResource(tenantId, actorUserId, resourceId, httpRequest);
    }
}
