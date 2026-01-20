package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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
 * 리소스 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ResourceManagementService {
    
    private final ResourceRepository resourceRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    
    /**
     * 리소스 트리 조회 (관리용)
     */
    @Transactional(readOnly = true)
    public List<ResourceSummary> getResourceTree(Long tenantId) {
        List<Resource> resources = resourceRepository.findByTenantIdOrderByParentResourceIdAscKeyAsc(tenantId);
        return resources.stream()
                .map(r -> toResourceSummary(tenantId, r))
                .collect(Collectors.toList());
    }
    
    /**
     * 리소스 목록 조회 (보강: category, kind, enabled 필터 추가)
     */
    @Transactional(readOnly = true)
    public PageResponse<ResourceSummary> getResources(Long tenantId, int page, int size,
                                                      String keyword, String type, String category, 
                                                      String kind, Long parentId, Boolean enabled) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Resource> resourcePage = resourceRepository.findByTenantIdAndFilters(
                tenantId, keyword, type, category, kind, parentId, enabled, pageable);
        
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
     * 리소스 생성
     */
    @Transactional
    public ResourceSummary createResource(Long tenantId, Long actorUserId, CreateResourceRequest request,
                                         HttpServletRequest httpRequest) {
        // RESOURCE_TYPE 검증
        codeResolver.require("RESOURCE_TYPE", request.getResourceType());
        
        // 리소스 키 중복 체크
        resourceRepository.findByTenantIdAndTypeAndKey(tenantId, request.getResourceType(), request.getResourceKey())
                .ifPresent(r -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 리소스 키입니다.");
                });
        
        // 부모 리소스 확인
        Long parentResourceId = null;
        if (request.getParentResourceKey() != null && !request.getParentResourceKey().isEmpty()) {
            Resource parent = resourceRepository.findByTenantIdAndTypeAndKey(tenantId, request.getResourceType(), request.getParentResourceKey())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 리소스를 찾을 수 없습니다."));
            parentResourceId = parent.getResourceId();
        }
        
        // metadata 생성
        String metadataJson = null;
        if (request.getMetadata() != null || request.getPath() != null || request.getSortOrder() != null) {
            Map<String, Object> metadata = request.getMetadata() != null ? request.getMetadata() : new java.util.HashMap<>();
            if (request.getPath() != null) {
                metadata.put("path", request.getPath());
            }
            if (request.getSortOrder() != null) {
                metadata.put("sortOrder", request.getSortOrder());
            }
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("Failed to serialize metadata", e);
            }
        }
        
        Resource resource = Resource.builder()
                .tenantId(tenantId)
                .type(request.getResourceType())
                .key(request.getResourceKey())
                .name(request.getResourceName())
                .parentResourceId(parentResourceId)
                .metadataJson(metadataJson)
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .build();
        resource = resourceRepository.save(resource);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_CREATE", "RESOURCE", resource.getResourceId(),
                null, resource, httpRequest);
        
        return toResourceSummary(tenantId, resource);
    }
    
    /**
     * 리소스 수정
     */
    @Transactional
    public ResourceSummary updateResource(Long tenantId, Long actorUserId, Long resourceId,
                                         UpdateResourceRequest request, HttpServletRequest httpRequest) {
        Resource resource = resourceRepository.findByTenantIdAndResourceId(tenantId, resourceId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "리소스를 찾을 수 없습니다."));
        
        Resource before = copyResource(resource);
        
        if (request.getResourceType() != null) {
            codeResolver.require("RESOURCE_TYPE", request.getResourceType());
            resource.setType(request.getResourceType());
        }
        if (request.getResourceKey() != null) {
            // 리소스 키 중복 체크 (본인 제외)
            resourceRepository.findByTenantIdAndTypeAndKey(tenantId, resource.getType(), request.getResourceKey())
                    .filter(r -> !r.getResourceId().equals(resourceId))
                    .ifPresent(r -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 리소스 키입니다.");
                    });
            resource.setKey(request.getResourceKey());
        }
        if (request.getResourceName() != null) {
            resource.setName(request.getResourceName());
        }
        if (request.getParentResourceKey() != null) {
            Resource parent = resourceRepository.findByTenantIdAndTypeAndKey(tenantId, resource.getType(), request.getParentResourceKey())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 리소스를 찾을 수 없습니다."));
            resource.setParentResourceId(parent.getResourceId());
        }
        if (request.getMetadata() != null || request.getPath() != null || request.getSortOrder() != null) {
            Map<String, Object> metadata = request.getMetadata() != null ? request.getMetadata() : parseMetadata(resource.getMetadataJson());
            if (request.getPath() != null) {
                metadata.put("path", request.getPath());
            }
            if (request.getSortOrder() != null) {
                metadata.put("sortOrder", request.getSortOrder());
            }
            try {
                resource.setMetadataJson(objectMapper.writeValueAsString(metadata));
            } catch (Exception e) {
                log.warn("Failed to serialize metadata", e);
            }
        }
        if (request.getEnabled() != null) {
            resource.setEnabled(request.getEnabled());
        }
        
        resource = resourceRepository.save(resource);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_UPDATE", "RESOURCE", resourceId,
                before, resource, httpRequest);
        
        return toResourceSummary(tenantId, resource);
    }
    
    /**
     * 리소스 삭제
     */
    @Transactional
    public void deleteResource(Long tenantId, Long actorUserId, Long resourceId, HttpServletRequest httpRequest) {
        Resource resource = resourceRepository.findByTenantIdAndResourceId(tenantId, resourceId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "리소스를 찾을 수 없습니다."));
        
        Resource before = copyResource(resource);
        
        // Soft delete (enabled = false)
        resource.setEnabled(false);
        resourceRepository.save(resource);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_DELETE", "RESOURCE", resourceId,
                before, resource, httpRequest);
    }
    
    private ResourceSummary toResourceSummary(Long tenantId, Resource resource) {
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
    private Map<String, Object> parseMetadata(String metadataJson) {
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
    
    private Resource copyResource(Resource resource) {
        return Resource.builder()
                .resourceId(resource.getResourceId())
                .tenantId(resource.getTenantId())
                .type(resource.getType())
                .key(resource.getKey())
                .name(resource.getName())
                .parentResourceId(resource.getParentResourceId())
                .metadataJson(resource.getMetadataJson())
                .enabled(resource.getEnabled())
                .build();
    }
}
