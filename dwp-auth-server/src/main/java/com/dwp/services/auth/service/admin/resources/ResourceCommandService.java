package com.dwp.services.auth.service.admin.resources;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.CreateResourceRequest;
import com.dwp.services.auth.dto.admin.ResourceSummary;
import com.dwp.services.auth.dto.admin.UpdateResourceRequest;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 리소스 변경 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class ResourceCommandService {
    
    private final ResourceRepository resourceRepository;
    private final ResourceQueryService resourceQueryService;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    
    /**
     * PR-04C: 리소스 생성 (운영 수준)
     */
    public ResourceSummary createResource(Long tenantId, Long actorUserId, CreateResourceRequest request,
                                         HttpServletRequest httpRequest) {
        // PR-04C: resourceCategory 코드 기반 검증
        codeResolver.require("RESOURCE_CATEGORY", request.getResourceCategory());
        
        // PR-04C: resourceKind 코드 기반 검증
        codeResolver.require("RESOURCE_KIND", request.getResourceKind());
        
        // 하위 호환성: resourceType이 있으면 resourceCategory로 매핑
        String resourceCategory = request.getResourceCategory();
        String resourceType = request.getResourceType();
        if (resourceCategory == null && resourceType != null) {
            resourceCategory = resourceType;
            codeResolver.require("RESOURCE_TYPE", resourceType);
        }
        
        // PR-04C: 리소스 키 중복 체크
        if (resourceQueryService.existsByKey(tenantId, request.getResourceKey())) {
            throw new BaseException(ErrorCode.RESOURCE_KEY_DUPLICATED, "이미 존재하는 리소스 키입니다.");
        }
        
        // PR-04C: 부모 리소스 확인 및 tenant 일치 검증
        Long parentResourceId = request.getParentResourceId();
        if (parentResourceId != null) {
            resourceQueryService.findResource(tenantId, parentResourceId);
        } else if (request.getParentResourceKey() != null && !request.getParentResourceKey().isEmpty()) {
            List<Resource> parents = resourceRepository.findByTenantIdAndKey(tenantId, request.getParentResourceKey());
            Resource parent = parents.stream()
                    .filter(r -> r.getTenantId() != null && r.getTenantId().equals(tenantId))
                    .findFirst()
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 리소스를 찾을 수 없습니다."));
            parentResourceId = parent.getResourceId();
        }
        
        // PR-04C: eventActions 코드 기반 검증
        String eventActionsJson = null;
        if (request.getEventActions() != null && !request.getEventActions().isEmpty()) {
            for (String action : request.getEventActions()) {
                codeResolver.require("UI_ACTION", action);
            }
            try {
                eventActionsJson = objectMapper.writeValueAsString(request.getEventActions());
            } catch (Exception e) {
                log.warn("Failed to serialize eventActions", e);
            }
        }
        
        // metadata 생성
        String metadataJson = null;
        Map<String, Object> metadata = request.getMeta() != null ? request.getMeta() : 
                (request.getMetadata() != null ? request.getMetadata() : new java.util.HashMap<>());
        if (request.getPath() != null) {
            metadata.put("path", request.getPath());
        }
        if (request.getSortOrder() != null) {
            metadata.put("sortOrder", request.getSortOrder());
        }
        if (!metadata.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("Failed to serialize metadata", e);
            }
        }
        
        // eventKey 자동 생성
        String eventKey = null;
        if (request.getEventActions() != null && !request.getEventActions().isEmpty()) {
            String primaryAction = request.getEventActions().get(0).toLowerCase();
            eventKey = request.getResourceKey() + ":" + primaryAction;
        }
        
        // type 필드 설정
        String type = resourceCategory;
        if (resourceType != null) {
            type = resourceType;
        }
        
        Resource resource = Resource.builder()
                .tenantId(tenantId)
                .type(type)
                .key(request.getResourceKey())
                .name(request.getResourceName())
                .resourceCategory(resourceCategory)
                .resourceKind(request.getResourceKind())
                .parentResourceId(parentResourceId)
                .metadataJson(metadataJson)
                .eventKey(eventKey)
                .eventActions(eventActionsJson)
                .trackingEnabled(request.getTrackingEnabled() != null ? request.getTrackingEnabled() : true)
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .build();
        resource = resourceRepository.save(resource);
        
        // PR-04F: 캐시 무효화
        invalidateResourceCache(tenantId);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_CREATE", "RESOURCE", resource.getResourceId(),
                null, resource, httpRequest);
        
        return resourceQueryService.toResourceSummary(tenantId, resource);
    }
    
    /**
     * PR-04D: 리소스 수정 (운영 수준)
     */
    public ResourceSummary updateResource(Long tenantId, Long actorUserId, Long resourceId,
                                         UpdateResourceRequest request, HttpServletRequest httpRequest) {
        Resource resource = resourceQueryService.findResource(tenantId, resourceId);
        Resource before = copyResource(resource);
        
        // PR-04D: resourceKey 변경 금지
        if (request.getResourceKey() != null && !request.getResourceKey().equals(resource.getKey())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "리소스 키 변경은 허용되지 않습니다. 별도 API를 사용해주세요.");
        }
        
        if (request.getResourceName() != null) {
            resource.setName(request.getResourceName());
        }
        
        // PR-04D: resourceCategory/resourceKind 수정 가능
        if (request.getResourceCategory() != null) {
            codeResolver.require("RESOURCE_CATEGORY", request.getResourceCategory());
            resource.setResourceCategory(request.getResourceCategory());
        }
        if (request.getResourceKind() != null) {
            codeResolver.require("RESOURCE_KIND", request.getResourceKind());
            resource.setResourceKind(request.getResourceKind());
        }
        
        // PR-04D: parentResourceId 수정 가능
        if (request.getParentResourceId() != null) {
            if (request.getParentResourceId().equals(resourceId)) {
                throw new BaseException(ErrorCode.INVALID_STATE, "자기 자신을 부모로 설정할 수 없습니다.");
            }
            resourceQueryService.findResource(tenantId, request.getParentResourceId());
            resource.setParentResourceId(request.getParentResourceId());
        }
        
        // PR-04D: eventActions 수정 가능
        if (request.getEventActions() != null) {
            for (String action : request.getEventActions()) {
                codeResolver.require("UI_ACTION", action);
            }
            try {
                resource.setEventActions(objectMapper.writeValueAsString(request.getEventActions()));
            } catch (Exception e) {
                log.warn("Failed to serialize eventActions", e);
            }
            if (!request.getEventActions().isEmpty()) {
                String primaryAction = request.getEventActions().get(0).toLowerCase();
                resource.setEventKey(resource.getKey() + ":" + primaryAction);
            }
        }
        
        if (request.getMeta() != null || request.getMetadata() != null || request.getPath() != null || request.getSortOrder() != null) {
            Map<String, Object> metadata = request.getMeta() != null ? request.getMeta() : 
                    (request.getMetadata() != null ? request.getMetadata() : resourceQueryService.parseMetadata(resource.getMetadataJson()));
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
        
        if (request.getTrackingEnabled() != null) {
            resource.setTrackingEnabled(request.getTrackingEnabled());
        }
        
        if (request.getEnabled() != null) {
            resource.setEnabled(request.getEnabled());
        }
        
        resource = resourceRepository.save(resource);
        
        // PR-04F: 캐시 무효화
        invalidateResourceCache(tenantId);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_UPDATE", "RESOURCE", resourceId,
                before, resource, httpRequest);
        
        return resourceQueryService.toResourceSummary(tenantId, resource);
    }
    
    /**
     * PR-04E: 리소스 삭제 (Soft Delete)
     */
    public void deleteResource(Long tenantId, Long actorUserId, Long resourceId, HttpServletRequest httpRequest) {
        Resource resource = resourceQueryService.findResource(tenantId, resourceId);
        
        // PR-04E: 하위 리소스 존재 확인
        long childCount = resourceQueryService.countChildren(tenantId, resourceId);
        if (childCount > 0) {
            throw new BaseException(ErrorCode.RESOURCE_HAS_CHILDREN, 
                    String.format("하위 리소스가 존재합니다 (%d개). 하위 리소스를 먼저 제거해주세요.", childCount));
        }
        
        Resource before = copyResource(resource);
        
        // PR-04E: Soft delete (enabled = false)
        resource.setEnabled(false);
        resourceRepository.save(resource);
        
        // PR-04F: 캐시 무효화
        invalidateResourceCache(tenantId);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_DELETE", "RESOURCE", resourceId,
                before, resource, httpRequest);
    }
    
    /**
     * PR-04F: 리소스 변경 시 캐시 무효화
     */
    private void invalidateResourceCache(Long tenantId) {
        log.info("Resource cache invalidation requested: tenantId={}", tenantId);
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
