package com.dwp.services.auth.service.admin.codeusages;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.CodeUsageSummary;
import com.dwp.services.auth.dto.admin.CreateCodeUsageRequest;
import com.dwp.services.auth.dto.admin.UpdateCodeUsageRequest;
import com.dwp.services.auth.entity.CodeUsage;
import com.dwp.services.auth.repository.CodeGroupRepository;
import com.dwp.services.auth.repository.CodeUsageRepository;
import com.dwp.services.auth.repository.MenuRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 코드 사용 정의 변경 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class CodeUsageCommandService {
    
    private final CodeUsageRepository codeUsageRepository;
    private final CodeGroupRepository codeGroupRepository;
    private final ResourceRepository resourceRepository;
    private final MenuRepository menuRepository;
    private final CodeUsageQueryService codeUsageQueryService;
    private final AuditLogService auditLogService;
    private final CodeResolver codeResolver;
    
    /**
     * PR-07B: 코드 사용 정의 생성 (검증 강화)
     */
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
        
        // scope 코드 검증
        String scope = request.getScope() != null ? request.getScope() : "MENU";
        codeResolver.validate("CODE_USAGE_SCOPE", scope);
        
        CodeUsage codeUsage = CodeUsage.builder()
                .tenantId(tenantId)
                .resourceKey(request.getResourceKey())
                .codeGroupKey(request.getCodeGroupKey())
                .scope(scope)
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
    public CodeUsageSummary updateCodeUsage(Long tenantId, Long actorUserId, Long sysCodeUsageId,
                                            UpdateCodeUsageRequest request, HttpServletRequest httpRequest) {
        CodeUsage codeUsage = codeUsageQueryService.findCodeUsage(tenantId, sysCodeUsageId);
        
        // PR-07B: tenantId 일치 검증
        if (!codeUsage.getTenantId().equals(tenantId)) {
            throw new BaseException(ErrorCode.TENANT_MISMATCH, "다른 테넌트의 코드 사용 정의는 수정할 수 없습니다.");
        }
        
        CodeUsage before = copyCodeUsage(codeUsage);
        
        if (request.getScope() != null) {
            codeResolver.validate("CODE_USAGE_SCOPE", request.getScope());
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
    public void deleteCodeUsage(Long tenantId, Long actorUserId, Long sysCodeUsageId, HttpServletRequest httpRequest) {
        CodeUsage codeUsage = codeUsageQueryService.findCodeUsage(tenantId, sysCodeUsageId);
        
        CodeUsage before = copyCodeUsage(codeUsage);
        String resourceKey = codeUsage.getResourceKey();
        
        codeUsageRepository.delete(codeUsage);
        
        // 캐시 무효화
        clearCache(tenantId, resourceKey);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_USAGE_DELETE", "CODE_USAGE", sysCodeUsageId,
                before, null, httpRequest);
    }
    
    /**
     * 캐시 무효화 (특정 리소스 키)
     */
    public void clearCache(Long tenantId, String resourceKey) {
        String cacheKey = tenantId + ":" + resourceKey;
        Map<String, Map<String, List<com.dwp.services.auth.dto.admin.CodeUsageResponse.CodeItem>>> codeCache = codeUsageQueryService.getCodeCache();
        codeCache.remove(cacheKey);
        log.info("Code usage cache cleared: tenantId={}, resourceKey={}", tenantId, resourceKey);
    }
    
    /**
     * 전체 캐시 무효화
     */
    public void clearAllCache() {
        Map<String, Map<String, List<com.dwp.services.auth.dto.admin.CodeUsageResponse.CodeItem>>> codeCache = codeUsageQueryService.getCodeCache();
        codeCache.clear();
        log.info("All code usage cache cleared");
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
}
