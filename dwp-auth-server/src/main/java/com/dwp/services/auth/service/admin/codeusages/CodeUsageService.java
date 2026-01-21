package com.dwp.services.auth.service.admin.codeusages;

import com.dwp.services.auth.dto.admin.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 코드 사용 정의 서비스 (Facade)
 * 
 * 기존 API 호환성을 유지하기 위한 Facade 패턴 적용
 * 실제 로직은 CodeUsageQueryService, CodeUsageCommandService로 위임
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CodeUsageService {
    
    private final CodeUsageQueryService codeUsageQueryService;
    private final CodeUsageCommandService codeUsageCommandService;
    
    /**
     * PR-06D: 메뉴별 코드 조회 (보안 강화)
     */
    public CodeUsageResponse getCodesByResourceKey(Long tenantId, Long userId, String resourceKey) {
        return codeUsageQueryService.getCodesByResourceKey(tenantId, userId, resourceKey);
    }
    
    /**
     * 하위 호환성: 기존 메서드 유지 (보안 검증 없음, 내부 사용)
     */
    public CodeUsageResponse getCodesByResourceKey(Long tenantId, String resourceKey) {
        return codeUsageQueryService.getCodesByResourceKey(tenantId, resourceKey);
    }
    
    /**
     * 메뉴별 사용 코드 그룹 목록 조회
     */
    public List<String> getCodeGroupKeysByResourceKey(Long tenantId, String resourceKey) {
        return codeUsageQueryService.getCodeGroupKeysByResourceKey(tenantId, resourceKey);
    }
    
    /**
     * PR-07A: 코드 사용 정의 목록 조회 고도화
     */
    public PageResponse<CodeUsageSummary> getCodeUsages(Long tenantId, int page, int size,
                                                         String resourceKey, String keyword, Boolean enabled) {
        return codeUsageQueryService.getCodeUsages(tenantId, page, size, resourceKey, keyword, enabled);
    }
    
    /**
     * PR-07B: 코드 사용 정의 생성 (검증 강화)
     */
    public CodeUsageSummary createCodeUsage(Long tenantId, Long actorUserId, CreateCodeUsageRequest request,
                                           HttpServletRequest httpRequest) {
        return codeUsageCommandService.createCodeUsage(tenantId, actorUserId, request, httpRequest);
    }
    
    /**
     * PR-07B: 코드 사용 정의 수정 (검증 강화)
     */
    public CodeUsageSummary updateCodeUsage(Long tenantId, Long actorUserId, Long sysCodeUsageId,
                                            UpdateCodeUsageRequest request, HttpServletRequest httpRequest) {
        return codeUsageCommandService.updateCodeUsage(tenantId, actorUserId, sysCodeUsageId, request, httpRequest);
    }
    
    /**
     * 코드 사용 정의 삭제
     */
    public void deleteCodeUsage(Long tenantId, Long actorUserId, Long sysCodeUsageId, HttpServletRequest httpRequest) {
        codeUsageCommandService.deleteCodeUsage(tenantId, actorUserId, sysCodeUsageId, httpRequest);
    }
    
    /**
     * 캐시 무효화 (특정 리소스 키)
     */
    public void clearCache(Long tenantId, String resourceKey) {
        codeUsageCommandService.clearCache(tenantId, resourceKey);
    }
    
    /**
     * 전체 캐시 무효화
     */
    public void clearAllCache() {
        codeUsageCommandService.clearAllCache();
    }
}
