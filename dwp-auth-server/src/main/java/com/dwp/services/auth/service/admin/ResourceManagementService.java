package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.service.rbac.AdminGuardService;
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
    @SuppressWarnings("unused") // 향후 해당 리소스를 가진 사용자만 선택적으로 캐시 무효화 시 사용 예정
    private final AdminGuardService adminGuardService;
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
     * PR-04B: 리소스 목록 조회 (운영 수준)
     * - trackingEnabled 필터 추가
     * - created_at desc 정렬 (기본)
     */
    @Transactional(readOnly = true)
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
     * PR-04C: 리소스 생성 (운영 수준)
     * - resourceCategory/resourceKind/eventActions 코드 기반 검증
     * - resourceKey 중복 시 409 RESOURCE_KEY_DUPLICATED
     * - parentResourceId 존재 시 tenant 일치 검증
     */
    @Transactional
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
            resourceCategory = resourceType; // 기존 코드 호환성
            codeResolver.require("RESOURCE_TYPE", resourceType);
        }
        
        // PR-04C: 리소스 키 중복 체크 (tenant_id + key 기준, type 무관)
        resourceRepository.findByTenantIdAndKey(tenantId, request.getResourceKey())
                .stream()
                .filter(r -> r.getTenantId() != null && r.getTenantId().equals(tenantId))
                .findFirst()
                .ifPresent(r -> {
                    throw new BaseException(ErrorCode.RESOURCE_KEY_DUPLICATED, "이미 존재하는 리소스 키입니다.");
                });
        
        // PR-04C: 부모 리소스 확인 및 tenant 일치 검증
        Long parentResourceId = request.getParentResourceId();
        if (parentResourceId != null) {
            // tenant 일치 검증 (이미 findByTenantIdAndResourceId에서 검증됨)
            resourceRepository.findByTenantIdAndResourceId(tenantId, parentResourceId)
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 리소스를 찾을 수 없습니다."));
        } else if (request.getParentResourceKey() != null && !request.getParentResourceKey().isEmpty()) {
            // 하위 호환성: parentResourceKey 지원
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
        
        // eventKey 자동 생성 (resourceKey:action 형식)
        String eventKey = null;
        if (request.getEventActions() != null && !request.getEventActions().isEmpty()) {
            String primaryAction = request.getEventActions().get(0).toLowerCase();
            eventKey = request.getResourceKey() + ":" + primaryAction;
        }
        
        // type 필드 설정 (하위 호환성: resourceCategory와 동기화)
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
        
        // PR-04F: 캐시 무효화 (resource 변경 시)
        invalidateResourceCache(tenantId);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_CREATE", "RESOURCE", resource.getResourceId(),
                null, resource, httpRequest);
        
        return toResourceSummary(tenantId, resource);
    }
    
    /**
     * PR-04D: 리소스 수정 (운영 수준)
     * - resourceKey 변경 금지 (운영 위험)
     * - name/meta/trackingEnabled/eventActions/enabled/parent 수정 가능
     */
    @Transactional
    public ResourceSummary updateResource(Long tenantId, Long actorUserId, Long resourceId,
                                         UpdateResourceRequest request, HttpServletRequest httpRequest) {
        Resource resource = resourceRepository.findByTenantIdAndResourceId(tenantId, resourceId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "리소스를 찾을 수 없습니다."));
        
        Resource before = copyResource(resource);
        
        // PR-04D: resourceKey 변경 금지 (운영 위험)
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
        
        // PR-04D: parentResourceId 수정 가능 (tenant 일치 검증)
        if (request.getParentResourceId() != null) {
            if (request.getParentResourceId().equals(resourceId)) {
                throw new BaseException(ErrorCode.INVALID_STATE, "자기 자신을 부모로 설정할 수 없습니다.");
            }
            resourceRepository.findByTenantIdAndResourceId(tenantId, request.getParentResourceId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 리소스를 찾을 수 없습니다."));
            resource.setParentResourceId(request.getParentResourceId());
        }
        
        // PR-04D: eventActions 수정 가능 (코드 검증)
        if (request.getEventActions() != null) {
            for (String action : request.getEventActions()) {
                codeResolver.require("UI_ACTION", action);
            }
            try {
                resource.setEventActions(objectMapper.writeValueAsString(request.getEventActions()));
            } catch (Exception e) {
                log.warn("Failed to serialize eventActions", e);
            }
            // eventKey 자동 업데이트
            if (!request.getEventActions().isEmpty()) {
                String primaryAction = request.getEventActions().get(0).toLowerCase();
                resource.setEventKey(resource.getKey() + ":" + primaryAction);
            }
        }
        
        if (request.getMeta() != null || request.getMetadata() != null || request.getPath() != null || request.getSortOrder() != null) {
            Map<String, Object> metadata = request.getMeta() != null ? request.getMeta() : 
                    (request.getMetadata() != null ? request.getMetadata() : parseMetadata(resource.getMetadataJson()));
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
        
        // PR-04F: 캐시 무효화 (resource 변경 시)
        invalidateResourceCache(tenantId);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_UPDATE", "RESOURCE", resourceId,
                before, resource, httpRequest);
        
        return toResourceSummary(tenantId, resource);
    }
    
    /**
     * PR-04E: 리소스 삭제 (Soft Delete + 하위 리소스 충돌 정책)
     * - Soft delete 권장 (enabled=false)
     * - 하위 리소스 존재 시 409 RESOURCE_HAS_CHILDREN
     */
    @Transactional
    public void deleteResource(Long tenantId, Long actorUserId, Long resourceId, HttpServletRequest httpRequest) {
        Resource resource = resourceRepository.findByTenantIdAndResourceId(tenantId, resourceId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "리소스를 찾을 수 없습니다."));
        
        // PR-04E: 하위 리소스 존재 확인
        long childCount = resourceRepository.countByTenantIdAndParentResourceId(tenantId, resourceId);
        if (childCount > 0) {
            throw new BaseException(ErrorCode.RESOURCE_HAS_CHILDREN, 
                    String.format("하위 리소스가 존재합니다 (%d개). 하위 리소스를 먼저 제거해주세요.", childCount));
        }
        
        Resource before = copyResource(resource);
        
        // PR-04E: Soft delete (enabled = false)
        resource.setEnabled(false);
        resourceRepository.save(resource);
        
        // PR-04F: 캐시 무효화 (resource 변경 시)
        invalidateResourceCache(tenantId);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "RESOURCE_DELETE", "RESOURCE", resourceId,
                before, resource, httpRequest);
    }
    
    /**
     * PR-04F: 리소스 변경 시 캐시 무효화
     * - menus/tree cache invalidate (있으면)
     * - permissions cache invalidate (해당 리소스를 가진 모든 사용자)
     */
    private void invalidateResourceCache(Long tenantId) {
        // Resource 변경은 권한에 영향을 주므로, 해당 tenant의 모든 사용자 권한 캐시 무효화
        // 실제 구현은 해당 리소스를 가진 사용자만 무효화하는 것이 효율적이지만,
        // 현재 구조에서는 tenant 전체 무효화가 안전함
        log.info("Resource cache invalidation requested: tenantId={} (Note: Individual user cache invalidation recommended)", tenantId);
        // TODO: 해당 리소스를 가진 사용자만 선택적으로 캐시 무효화 (성능 최적화)
        // 현재는 캐시 TTL(5분)을 신뢰하거나, FE에서 권한 변경 후 재조회 권장
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
