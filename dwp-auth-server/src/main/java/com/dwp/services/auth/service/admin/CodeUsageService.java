package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.entity.CodeUsage;
import com.dwp.services.auth.repository.CodeGroupRepository;
import com.dwp.services.auth.repository.CodeRepository;
import com.dwp.services.auth.repository.CodeUsageRepository;
import com.dwp.services.auth.repository.MenuRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.service.rbac.AdminGuardService;
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
    private final CodeGroupRepository codeGroupRepository;
    private final ResourceRepository resourceRepository;
    private final MenuRepository menuRepository;
    private final AdminGuardService adminGuardService;
    private final AuditLogService auditLogService;
    
    /**
     * 인메모리 캐시 (resourceKey 기준)
     * 
     * 캐시 key 규칙: tenantId + ":" + resourceKey (예: "1:menu.admin.users")
     * 캐시 무효화: invalidateCache(tenantId, resourceKey) 호출 시 해당 키만 제거
     */
    private final Map<String, Map<String, List<CodeUsageResponse.CodeItem>>> codeCache = new HashMap<>();
    
    /**
     * PR-06D: 메뉴별 코드 조회 (보안 강화)
     * 
     * 보안 검증:
     * - ADMIN 권한 필수
     * - resourceKey 접근 권한 (VIEW) 체크
     * - enabled된 code group만 반환
     * 
     * @param tenantId 테넌트 ID
     * @param userId 사용자 ID
     * @param resourceKey 리소스 키 (예: menu.admin.users)
     * @return 코드 그룹 키별 코드 목록 맵
     */
    @Transactional(readOnly = true)
    public CodeUsageResponse getCodesByResourceKey(Long tenantId, Long userId, String resourceKey) {
        // PR-06D: ADMIN 권한 체크
        adminGuardService.requireAdminRole(tenantId, userId);
        
        // PR-06D: resourceKey 접근 권한 체크 (VIEW)
        if (!adminGuardService.canAccess(userId, tenantId, resourceKey, "VIEW")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "해당 리소스에 대한 접근 권한이 없습니다.");
        }
        
        // 캐시 확인
        String cacheKey = tenantId + ":" + resourceKey;
        Map<String, List<CodeUsageResponse.CodeItem>> cached = codeCache.get(cacheKey);
        if (cached != null) {
            return CodeUsageResponse.builder().codes(cached).build();
        }
        
        // PR-06D: 활성화된 코드 그룹 키 목록 조회 (enabled=true만)
        List<String> codeGroupKeys = codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(
                tenantId, resourceKey);
        
        if (codeGroupKeys.isEmpty()) {
            return CodeUsageResponse.builder().codes(new HashMap<>()).build();
        }
        
        // PR-07C: 각 그룹의 활성화된 코드 조회 (tenant 우선 정책)
        Map<String, List<CodeUsageResponse.CodeItem>> codesMap = new HashMap<>();
        for (String groupKey : codeGroupKeys) {
            // PR-07C: tenant 우선 정책 - tenant 전용 코드가 있으면 그것을 우선 사용, 없으면 common 코드 사용
            List<Code> tenantCodes = codeRepository.findByGroupKeyAndTenantIdOrderBySortOrderAsc(groupKey, tenantId);
            
            // tenant 전용 코드 필터링 (tenantId = tenantId)
            List<Code> tenantSpecificCodes = tenantCodes.stream()
                    .filter(code -> code.getTenantId() != null && code.getTenantId().equals(tenantId))
                    .filter(code -> code.getIsActive())
                    .collect(Collectors.toList());
            
            // common 코드 필터링 (tenantId = null)
            List<Code> commonCodes = tenantCodes.stream()
                    .filter(code -> code.getTenantId() == null)
                    .filter(code -> code.getIsActive())
                    .collect(Collectors.toList());
            
            // PR-07C: tenant 전용 코드가 있으면 그것을 우선 사용, 없으면 common 코드 사용
            List<Code> finalCodes = tenantSpecificCodes.isEmpty() ? commonCodes : tenantSpecificCodes;
            
            List<CodeUsageResponse.CodeItem> codeItems = finalCodes.stream()
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
     * 하위 호환성: 기존 메서드 유지 (보안 검증 없음, 내부 사용)
     */
    @Transactional(readOnly = true)
    public CodeUsageResponse getCodesByResourceKey(Long tenantId, String resourceKey) {
        // 보안 검증 없이 조회 (내부 사용)
        String cacheKey = tenantId + ":" + resourceKey;
        Map<String, List<CodeUsageResponse.CodeItem>> cached = codeCache.get(cacheKey);
        if (cached != null) {
            return CodeUsageResponse.builder().codes(cached).build();
        }
        
        List<String> codeGroupKeys = codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(
                tenantId, resourceKey);
        
        if (codeGroupKeys.isEmpty()) {
            return CodeUsageResponse.builder().codes(new HashMap<>()).build();
        }
        
        // PR-07C: tenant 우선 정책 적용 (하위 호환성 메서드)
        Map<String, List<CodeUsageResponse.CodeItem>> codesMap = new HashMap<>();
        for (String groupKey : codeGroupKeys) {
            List<Code> tenantCodes = codeRepository.findByGroupKeyAndTenantIdOrderBySortOrderAsc(groupKey, tenantId);
            
            List<Code> tenantSpecificCodes = tenantCodes.stream()
                    .filter(code -> code.getTenantId() != null && code.getTenantId().equals(tenantId))
                    .filter(code -> code.getIsActive())
                    .collect(Collectors.toList());
            
            List<Code> commonCodes = tenantCodes.stream()
                    .filter(code -> code.getTenantId() == null)
                    .filter(code -> code.getIsActive())
                    .collect(Collectors.toList());
            
            List<Code> finalCodes = tenantSpecificCodes.isEmpty() ? commonCodes : tenantSpecificCodes;
            
            List<CodeUsageResponse.CodeItem> codeItems = finalCodes.stream()
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
     * PR-07A: 코드 사용 정의 목록 조회 고도화
     * 
     * 필터:
     * - keyword: resourceKey 또는 groupKey 검색
     * - enabled: 활성화 여부 필터
     * - resourceKey: 특정 리소스 키 필터
     */
    @Transactional(readOnly = true)
    public PageResponse<CodeUsageSummary> getCodeUsages(Long tenantId, int page, int size,
                                                         String resourceKey, String keyword, Boolean enabled) {
        // 페이징 크기 제한 (최대 200)
        if (size > 200) {
            size = 200;
        }
        if (size < 1) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<CodeUsage> codeUsagePage = codeUsageRepository.findByTenantIdAndFilters(
                tenantId, resourceKey, keyword, enabled, pageable);
        
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
     * PR-07B: 코드 사용 정의 생성 (검증 강화)
     */
    @Transactional
    public CodeUsageSummary createCodeUsage(Long tenantId, Long actorUserId, CreateCodeUsageRequest request,
                                           HttpServletRequest httpRequest) {
        // PR-07B: resourceKey 존재 검증 (com_resources 또는 sys_menus)
        boolean resourceExists = resourceRepository.findByTenantIdAndKey(tenantId, request.getResourceKey())
                .stream()
                .anyMatch(r -> r.getTenantId() != null && r.getTenantId().equals(tenantId));
        
        if (!resourceExists) {
            // sys_menus에서도 확인
            resourceExists = menuRepository.findByTenantIdAndMenuKey(tenantId, request.getResourceKey()).isPresent();
        }
        
        if (!resourceExists) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                    String.format("리소스 키를 찾을 수 없습니다: %s", request.getResourceKey()));
        }
        
        // PR-07B: groupKey 존재 검증 (sys_code_groups)
        codeGroupRepository.findByGroupKey(request.getCodeGroupKey())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                        String.format("코드 그룹 키를 찾을 수 없습니다: %s", request.getCodeGroupKey())));
        
        // PR-07B: 중복 매핑 체크 (409)
        codeUsageRepository.findByTenantIdAndResourceKeyOrderBySortOrderAsc(tenantId, request.getResourceKey())
                .stream()
                .filter(cu -> cu.getCodeGroupKey().equals(request.getCodeGroupKey()))
                .findFirst()
                .ifPresent(cu -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY,
                            String.format("이미 존재하는 코드 사용 정의입니다: resourceKey=%s, groupKey=%s",
                                    request.getResourceKey(), request.getCodeGroupKey()));
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
        
        // PR-07D: 캐시 무효화
        clearCache(tenantId, request.getResourceKey());
        
        // PR-07E: 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_USAGE_CREATE", "CODE_USAGE", codeUsage.getSysCodeUsageId(),
                null, codeUsage, httpRequest);
        
        return toCodeUsageSummary(codeUsage);
    }
    
    /**
     * PR-07B: 코드 사용 정의 수정 (검증 강화)
     */
    @Transactional
    public CodeUsageSummary updateCodeUsage(Long tenantId, Long actorUserId, Long sysCodeUsageId,
                                            UpdateCodeUsageRequest request, HttpServletRequest httpRequest) {
        CodeUsage codeUsage = codeUsageRepository.findByTenantIdAndSysCodeUsageId(tenantId, sysCodeUsageId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드 사용 정의를 찾을 수 없습니다."));
        
        // PR-07B: tenantId 일치 검증
        if (!codeUsage.getTenantId().equals(tenantId)) {
            throw new BaseException(ErrorCode.TENANT_MISMATCH, "다른 테넌트의 코드 사용 정의는 수정할 수 없습니다.");
        }
        
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
        
        // PR-07D: 캐시 무효화
        clearCache(tenantId, codeUsage.getResourceKey());
        
        // PR-07E: 감사 로그
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
        log.info("Code usage cache cleared: tenantId={}, resourceKey={}", tenantId, resourceKey);
    }
    
    /**
     * 전체 캐시 무효화
     */
    public void clearAllCache() {
        codeCache.clear();
        log.info("All code usage cache cleared");
    }
}
