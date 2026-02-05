package com.dwp.services.auth.service.admin.codeusages;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.util.LocaleUtil;
import com.dwp.services.auth.dto.admin.CodeUsageDetail;
import com.dwp.services.auth.dto.admin.CodeUsageResponse;
import com.dwp.services.auth.dto.admin.CodeUsageSummary;
import com.dwp.services.auth.dto.admin.PageResponse;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.entity.CodeUsage;
import com.dwp.services.auth.repository.CodeRepository;
import com.dwp.services.auth.repository.CodeUsageRepository;
import com.dwp.services.auth.service.rbac.AdminGuardService;
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
 * 코드 사용 정의 조회 서비스 (CQRS: Query 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@SuppressWarnings("null")
public class CodeUsageQueryService {
    
    private final CodeUsageRepository codeUsageRepository;
    private final CodeRepository codeRepository;
    private final AdminGuardService adminGuardService;
    
    /**
     * 인메모리 캐시 (resourceKey 기준)
     * 
     * 캐시 key 규칙: tenantId + ":" + resourceKey (예: "1:menu.admin.users")
     * 캐시 무효화: CodeUsageCommandService에서 처리
     */
    private final Map<String, Map<String, List<CodeUsageResponse.CodeItem>>> codeCache = new HashMap<>();
    
    /**
     * PR-06D: 메뉴별 코드 조회 (보안 강화)
     * 
     * 보안 검증:
     * - ADMIN 권한 필수
     * - resourceKey 접근 권한 (VIEW) 체크
     * - enabled된 code group만 반환
     */
    public CodeUsageResponse getCodesByResourceKey(Long tenantId, Long userId, String resourceKey) {
        // PR-06D: ADMIN 권한 체크
        adminGuardService.requireAdminRole(tenantId, userId);
        
        // PR-06D: resourceKey 접근 권한 체크 (VIEW)
        if (!adminGuardService.canAccess(userId, tenantId, resourceKey, "VIEW")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "해당 리소스에 대한 접근 권한이 없습니다.");
        }
        
        return getCodesByResourceKeyInternal(tenantId, resourceKey);
    }
    
    /**
     * 하위 호환성: 기존 메서드 유지 (보안 검증 없음, 내부 사용)
     */
    public CodeUsageResponse getCodesByResourceKey(Long tenantId, String resourceKey) {
        return getCodesByResourceKeyInternal(tenantId, resourceKey);
    }
    
    private CodeUsageResponse getCodesByResourceKeyInternal(Long tenantId, String resourceKey) {
        // 캐시 확인 (locale 포함: i18n 지원)
        String cacheKey = tenantId + ":" + resourceKey + ":" + LocaleUtil.getLang();
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
                    .map(code -> {
                        String resolvedName = LocaleUtil.resolveLabel(code.getNameKo(), code.getNameEn(), code.getName());
                        return CodeUsageResponse.CodeItem.builder()
                                .sysCodeId(code.getSysCodeId())
                                .code(code.getCode())
                                .name(resolvedName)
                                .description(code.getDescription())
                                .sortOrder(code.getSortOrder())
                                .enabled(code.getIsActive())
                                .ext1(code.getExt1())
                                .ext2(code.getExt2())
                                .ext3(code.getExt3())
                                .build();
                    })
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
    public List<String> getCodeGroupKeysByResourceKey(Long tenantId, String resourceKey) {
        return codeUsageRepository.findEnabledCodeGroupKeysByTenantIdAndResourceKey(tenantId, resourceKey);
    }
    
    /**
     * PR-07A: 코드 사용 정의 목록 조회 고도화
     * P1-2: codeGroupKey 필터 추가
     * 
     * 필터: resourceKey, codeGroupKey, keyword, enabled
     */
    public PageResponse<CodeUsageSummary> getCodeUsages(Long tenantId, int page, int size,
                                                         String resourceKey, String codeGroupKey, String keyword, Boolean enabled) {
        if (size > 200) size = 200;
        if (size < 1) size = 20;
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<CodeUsage> codeUsagePage = codeUsageRepository.findByTenantIdAndFilters(
                tenantId, resourceKey, codeGroupKey, keyword, enabled, pageable);
        
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
     * 코드 사용 정의 조회 (내부용)
     */
    public CodeUsage findCodeUsage(Long tenantId, Long sysCodeUsageId) {
        return codeUsageRepository.findByTenantIdAndSysCodeUsageId(tenantId, sysCodeUsageId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드 사용 정의를 찾을 수 없습니다."));
    }
    
    /**
     * P1-5: 코드 사용 정의 상세 조회 (CodeUsageSummary + createdBy, updatedBy)
     */
    public CodeUsageDetail getCodeUsageDetail(Long tenantId, Long sysCodeUsageId) {
        CodeUsage cu = findCodeUsage(tenantId, sysCodeUsageId);
        return CodeUsageDetail.builder()
                .sysCodeUsageId(cu.getSysCodeUsageId())
                .tenantId(cu.getTenantId())
                .resourceKey(cu.getResourceKey())
                .codeGroupKey(cu.getCodeGroupKey())
                .scope(cu.getScope())
                .enabled(cu.getEnabled())
                .sortOrder(cu.getSortOrder())
                .remark(cu.getRemark())
                .createdAt(cu.getCreatedAt())
                .updatedAt(cu.getUpdatedAt())
                .createdBy(cu.getCreatedBy())
                .updatedBy(cu.getUpdatedBy())
                .build();
    }
    
    /**
     * 캐시 접근 (CommandService에서 사용)
     */
    Map<String, Map<String, List<CodeUsageResponse.CodeItem>>> getCodeCache() {
        return codeCache;
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
}
