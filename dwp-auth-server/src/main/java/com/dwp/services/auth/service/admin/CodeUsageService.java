package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.entity.CodeUsage;
import com.dwp.services.auth.repository.CodeRepository;
import com.dwp.services.auth.repository.CodeUsageRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 코드 사용 정의 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CodeUsageService {
    
    private final CodeUsageRepository codeUsageRepository;
    private final CodeRepository codeRepository;
    private final AuditLogService auditLogService;
    
    /**
     * 인메모리 캐시 (resourceKey 기준)
     * 
     * 캐시 key 규칙: tenantId + ":" + resourceKey (예: "1:menu.admin.users")
     * 캐시 무효화: invalidateCache(tenantId, resourceKey) 호출 시 해당 키만 제거
     */
    private final Map<String, Map<String, List<CodeUsageResponse.CodeItem>>> codeCache = new HashMap<>();
    
    /**
     * 메뉴별 코드 조회 (핵심 API)
     * 
     * @param tenantId 테넌트 ID
     * @param resourceKey 리소스 키 (예: menu.admin.users)
     * @return 코드 그룹 키별 코드 목록 맵
     */
    @Transactional(readOnly = true)
    public CodeUsageResponse getCodesByResourceKey(Long tenantId, String resourceKey) {
        // 캐시 확인
        String cacheKey = tenantId + ":" + resourceKey;
        Map<String, List<CodeUsageResponse.CodeItem>> cached = codeCache.get(cacheKey);
        if (cached != null) {
            return CodeUsageResponse.builder().codes(cached).build();
        }
        
        // 활성화된 코드 그룹 키 목록 조회
        List<String> codeGroupKeys = codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(
                tenantId, resourceKey);
        
        if (codeGroupKeys.isEmpty()) {
            return CodeUsageResponse.builder().codes(new HashMap<>()).build();
        }
        
        // 각 그룹의 활성화된 코드 조회 (tenant_id 고려, BE P1-5)
        Map<String, List<CodeUsageResponse.CodeItem>> codesMap = new HashMap<>();
        for (String groupKey : codeGroupKeys) {
            // tenant_id를 고려한 코드 조회 (전사 공통 코드 + 테넌트별 커스텀 코드)
            List<Code> codes = codeRepository.findByGroupKeyAndTenantIdOrderBySortOrderAsc(groupKey, tenantId);
            List<CodeUsageResponse.CodeItem> codeItems = codes.stream()
                    .filter(code -> code.getIsActive())  // enabled=true만 필터링
                    .map(code -> CodeUsageResponse.CodeItem.builder()
                            .sysCodeId(code.getSysCodeId())
                            .code(code.getCode())
                            .name(code.getName())
                            .description(code.getDescription())
                            .sortOrder(code.getSortOrder())
                            .enabled(code.getIsActive())
                            .ext1(code.getExt1())
                            .ext2(code.getExt2())
                            .ext3(code.getExt3())
                            .build())
                    .collect(Collectors.toList());
            codesMap.put(groupKey, codeItems);
        }
        
        // 캐시 저장
        codeCache.put(cacheKey, codesMap);
        
        return CodeUsageResponse.builder().codes(codesMap).build();
    }
    
    /**
     * 메뉴별 사용 코드 그룹 목록 조회
     */
    @Transactional(readOnly = true)
    public List<String> getCodeGroupKeysByResourceKey(Long tenantId, String resourceKey) {
        return codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey);
    }
    
    /**
     * 코드 사용 정의 목록 조회
     */
    @Transactional(readOnly = true)
    public PageResponse<CodeUsageSummary> getCodeUsages(Long tenantId, int page, int size,
                                                         String resourceKey, String keyword) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<CodeUsage> codeUsagePage = codeUsageRepository.findByTenantIdAndFilters(
                tenantId, resourceKey, keyword, pageable);
        
        List<CodeUsageSummary> summaries = codeUsagePage.getContent().stream()
                .map(this::toCodeUsageSummary)
                .collect(Collectors.toList());
        
        return PageResponse.<CodeUsageSummary>builder()
                .items(summaries)
                .page(page)
                .size(size)
                .totalItems(codeUsagePage.getTotalElements())
                .totalPages(codeUsagePage.getTotalPages())
                .build();
    }
    
    /**
     * 코드 사용 정의 생성
     */
    @Transactional
    public CodeUsageSummary createCodeUsage(Long tenantId, Long actorUserId, CreateCodeUsageRequest request,
                                           HttpServletRequest httpRequest) {
        // 중복 체크
        codeUsageRepository.findByTenantIdAndResourceKeyOrderBySortOrderAsc(tenantId, request.getResourceKey())
                .stream()
                .filter(cu -> cu.getCodeGroupKey().equals(request.getCodeGroupKey()))
                .findFirst()
                .ifPresent(cu -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 코드 사용 정의입니다.");
                });
        
        CodeUsage codeUsage = CodeUsage.builder()
                .tenantId(tenantId)
                .resourceKey(request.getResourceKey())
                .codeGroupKey(request.getCodeGroupKey())
                .scope(request.getScope() != null ? request.getScope() : "MENU")
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .sortOrder(request.getSortOrder())
                .remark(request.getRemark())
                .build();
        codeUsage = codeUsageRepository.save(codeUsage);
        
        // 캐시 무효화
        clearCache(tenantId, request.getResourceKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_USAGE_CREATE", "CODE_USAGE", codeUsage.getSysCodeUsageId(),
                null, codeUsage, httpRequest);
        
        return toCodeUsageSummary(codeUsage);
    }
    
    /**
     * 코드 사용 정의 수정
     */
    @Transactional
    public CodeUsageSummary updateCodeUsage(Long tenantId, Long actorUserId, Long sysCodeUsageId,
                                            UpdateCodeUsageRequest request, HttpServletRequest httpRequest) {
        CodeUsage codeUsage = codeUsageRepository.findByTenantIdAndSysCodeUsageId(tenantId, sysCodeUsageId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드 사용 정의를 찾을 수 없습니다."));
        
        CodeUsage before = copyCodeUsage(codeUsage);
        
        if (request.getScope() != null) {
            codeUsage.setScope(request.getScope());
        }
        if (request.getEnabled() != null) {
            codeUsage.setEnabled(request.getEnabled());
        }
        if (request.getSortOrder() != null) {
            codeUsage.setSortOrder(request.getSortOrder());
        }
        if (request.getRemark() != null) {
            codeUsage.setRemark(request.getRemark());
        }
        
        codeUsage = codeUsageRepository.save(codeUsage);
        
        // 캐시 무효화
        clearCache(tenantId, codeUsage.getResourceKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_USAGE_UPDATE", "CODE_USAGE", sysCodeUsageId,
                before, codeUsage, httpRequest);
        
        return toCodeUsageSummary(codeUsage);
    }
    
    /**
     * 코드 사용 정의 삭제
     */
    @Transactional
    public void deleteCodeUsage(Long tenantId, Long actorUserId, Long sysCodeUsageId, HttpServletRequest httpRequest) {
        CodeUsage codeUsage = codeUsageRepository.findByTenantIdAndSysCodeUsageId(tenantId, sysCodeUsageId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드 사용 정의를 찾을 수 없습니다."));
        
        CodeUsage before = copyCodeUsage(codeUsage);
        String resourceKey = codeUsage.getResourceKey();
        
        codeUsageRepository.delete(codeUsage);
        
        // 캐시 무효화
        clearCache(tenantId, resourceKey);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_USAGE_DELETE", "CODE_USAGE", sysCodeUsageId,
                before, null, httpRequest);
    }
    
    private CodeUsageSummary toCodeUsageSummary(CodeUsage codeUsage) {
        return CodeUsageSummary.builder()
                .sysCodeUsageId(codeUsage.getSysCodeUsageId())
                .tenantId(codeUsage.getTenantId())
                .resourceKey(codeUsage.getResourceKey())
                .codeGroupKey(codeUsage.getCodeGroupKey())
                .scope(codeUsage.getScope())
                .enabled(codeUsage.getEnabled())
                .sortOrder(codeUsage.getSortOrder())
                .remark(codeUsage.getRemark())
                .createdAt(codeUsage.getCreatedAt())
                .updatedAt(codeUsage.getUpdatedAt())
                .build();
    }
    
    private CodeUsage copyCodeUsage(CodeUsage codeUsage) {
        return CodeUsage.builder()
                .sysCodeUsageId(codeUsage.getSysCodeUsageId())
                .tenantId(codeUsage.getTenantId())
                .resourceKey(codeUsage.getResourceKey())
                .codeGroupKey(codeUsage.getCodeGroupKey())
                .scope(codeUsage.getScope())
                .enabled(codeUsage.getEnabled())
                .sortOrder(codeUsage.getSortOrder())
                .remark(codeUsage.getRemark())
                .build();
    }
    
    /**
     * 캐시 무효화 (특정 리소스 키)
     */
    public void clearCache(Long tenantId, String resourceKey) {
        String cacheKey = tenantId + ":" + resourceKey;
        codeCache.remove(cacheKey);
        log.debug("Code usage cache cleared: {}", cacheKey);
    }
    
    /**
     * 전체 캐시 무효화
     */
    public void clearAllCache() {
        codeCache.clear();
        log.debug("All code usage cache cleared");
    }
}
