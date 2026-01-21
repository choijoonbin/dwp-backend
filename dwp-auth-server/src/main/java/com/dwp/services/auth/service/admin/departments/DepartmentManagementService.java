package com.dwp.services.auth.service.admin.departments;

import com.dwp.services.auth.dto.admin.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 부서 관리 서비스 (Facade)
 * 
 * 기존 API 호환성을 유지하기 위한 Facade 패턴 적용
 * 실제 로직은 DepartmentQueryService, DepartmentCommandService로 위임
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DepartmentManagementService {
    
    private final DepartmentQueryService departmentQueryService;
    private final DepartmentCommandService departmentCommandService;
    
    /**
     * 부서 목록 조회
     */
    public PageResponse<DepartmentSummary> getDepartments(Long tenantId, int page, int size, String keyword) {
        return departmentQueryService.getDepartments(tenantId, page, size, keyword);
    }
    
    /**
     * 부서 생성
     */
    public DepartmentSummary createDepartment(Long tenantId, Long actorUserId, CreateDepartmentRequest request,
                                              HttpServletRequest httpRequest) {
        return departmentCommandService.createDepartment(tenantId, actorUserId, request, httpRequest);
    }
    
    /**
     * 부서 수정
     */
    public DepartmentSummary updateDepartment(Long tenantId, Long actorUserId, Long departmentId,
                                             UpdateDepartmentRequest request, HttpServletRequest httpRequest) {
        return departmentCommandService.updateDepartment(tenantId, actorUserId, departmentId, request, httpRequest);
    }
    
    /**
     * 부서 삭제 (soft delete)
     */
    public void deleteDepartment(Long tenantId, Long actorUserId, Long departmentId, HttpServletRequest httpRequest) {
        departmentCommandService.deleteDepartment(tenantId, actorUserId, departmentId, httpRequest);
    }
}
