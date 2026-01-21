package com.dwp.services.auth.service.admin.resources;

import com.dwp.services.auth.dto.admin.PageResponse;
import com.dwp.services.auth.dto.admin.ResourceSummary;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.repository.ResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 리소스 조회 서비스 (CQRS: Query 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@SuppressWarnings("null")
public class ResourceQueryService {
    
    private final ResourceRepository resourceRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * 리소스 트리 조회 (관리용)
     */
    public List<ResourceSummary> getResourceTree(Long tenantId) {
        List<Resource> resources = resourceRepository.findByTenantIdOrderByParentResourceIdAscKeyAsc(tenantId);
        return resources.stream()
                .map(r -> toResourceSummary(tenantId, r))
                .collect(Collectors.toList());
    }
    
    /**
     * PR-04B: 리소스 목록 조회 (운영 수준)
     * - trackingEnabled 필터 추가
     * - created_at desc 정렬 (기본)
     */
    public PageResponse<ResourceSummary> getResources(Long tenantId, int page, int size,
                                                      String keyword, String type, String category, 
                                                      String kind, Long parentId, Boolean enabled, Boolean trackingEnabled) {
        // 페이징 크기 제한 (최대 200)
        if (size > 200) {
            size = 200;
        }
        if (size < 1) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Resource> resourcePage = resourceRepository.findByTenantIdAndFilters(
                tenantId, keyword, type, category, kind, parentId, enabled, trackingEnabled, pageable);
        
        List<ResourceSummary> summaries = resourcePage.getContent().stream()
                .map(r -> toResourceSummary(tenantId, r))
                .collect(Collectors.toList());
        
        return PageResponse.<ResourceSummary>builder()
                .items(summaries)
                .page(page)
                .size(size)
                .totalItems(resourcePage.getTotalElements())
                .totalPages(resourcePage.getTotalPages())
                .build();
    }
    
    /**
     * 리소스 조회 (내부용)
     */
    public Resource findResource(Long tenantId, Long resourceId) {
        return resourceRepository.findByTenantIdAndResourceId(tenantId, resourceId)
                .orElseThrow(() -> new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.ENTITY_NOT_FOUND, "리소스를 찾을 수 없습니다."));
    }
    
    /**
     * 하위 리소스 개수 조회
     */
    public long countChildren(Long tenantId, Long resourceId) {
        return resourceRepository.countByTenantIdAndParentResourceId(tenantId, resourceId);
    }
    
    /**
     * 리소스 키로 조회 (중복 체크용)
     */
    public boolean existsByKey(Long tenantId, String resourceKey) {
        return resourceRepository.findByTenantIdAndKey(tenantId, resourceKey)
                .stream()
                .anyMatch(r -> r.getTenantId() != null && r.getTenantId().equals(tenantId));
    }
    
    public ResourceSummary toResourceSummary(Long tenantId, Resource resource) {
        final String[] parentResourceName = {null};
        if (resource.getParentResourceId() != null) {
            resourceRepository.findById(resource.getParentResourceId())
                    .ifPresent(parent -> parentResourceName[0] = parent.getName());
        }
        
        Map<String, Object> metadata = parseMetadata(resource.getMetadataJson());
        String path = metadata != null && metadata.containsKey("path") ? (String) metadata.get("path") : null;
        Integer sortOrder = metadata != null && metadata.containsKey("sortOrder") ? 
                ((Number) metadata.get("sortOrder")).intValue() : null;
        
        return ResourceSummary.builder()
                .comResourceId(resource.getResourceId())
                .resourceKey(resource.getKey())
                .resourceName(resource.getName())
                .type(resource.getType())
                .parentResourceId(resource.getParentResourceId())
                .parentResourceName(parentResourceName[0])
                .path(path)
                .sortOrder(sortOrder)
                .enabled(resource.getEnabled())
                .createdAt(resource.getCreatedAt())
                .build();
    }
    
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataJson, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse metadata", e);
            return null;
        }
    }
}
