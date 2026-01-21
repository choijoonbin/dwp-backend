package com.dwp.services.auth.service.admin.departments;

import com.dwp.services.auth.dto.admin.DepartmentSummary;
import com.dwp.services.auth.dto.admin.PageResponse;
import com.dwp.services.auth.entity.Department;
import com.dwp.services.auth.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 부서 조회 서비스 (CQRS: Query 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@SuppressWarnings("null")
public class DepartmentQueryService {
    
    private final DepartmentRepository departmentRepository;
    
    /**
     * 부서 목록 조회
     */
    public PageResponse<DepartmentSummary> getDepartments(Long tenantId, int page, int size, String keyword) {
        // 페이징 크기 제한 (최대 200)
        if (size > 200) {
            size = 200;
        }
        if (size < 1) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Department> departmentPage = departmentRepository.findByTenantIdAndKeyword(tenantId, keyword, pageable);
        
        List<DepartmentSummary> summaries = departmentPage.getContent().stream()
                .map(dept -> toDepartmentSummary(tenantId, dept))
                .collect(Collectors.toList());
        
        return PageResponse.<DepartmentSummary>builder()
                .items(summaries)
                .page(page)
                .size(size)
                .totalItems(departmentPage.getTotalElements())
                .totalPages(departmentPage.getTotalPages())
                .build();
    }
    
    /**
     * 부서 상세 조회 (내부용)
     */
    public Department findDepartment(Long tenantId, Long departmentId) {
        return departmentRepository.findByTenantIdAndDepartmentId(tenantId, departmentId)
                .orElseThrow(() -> new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
    }
    
    /**
     * 부서 코드로 조회 (내부용)
     */
    public boolean existsByCode(Long tenantId, String code) {
        return departmentRepository.findByTenantIdAndCode(tenantId, code).isPresent();
    }
    
    /**
     * 부서 코드로 조회 (중복 체크용)
     */
    public boolean existsByCodeExcludingId(Long tenantId, String code, Long excludeId) {
        return departmentRepository.findByTenantIdAndCode(tenantId, code)
                .filter(d -> !d.getDepartmentId().equals(excludeId))
                .isPresent();
    }
    
    private DepartmentSummary toDepartmentSummary(Long tenantId, Department department) {
        final String[] parentDepartmentName = {null};
        if (department.getParentDepartmentId() != null) {
            departmentRepository.findByTenantIdAndDepartmentId(tenantId, department.getParentDepartmentId())
                    .ifPresent(parent -> parentDepartmentName[0] = parent.getName());
        }
        
        return DepartmentSummary.builder()
                .departmentId(department.getDepartmentId())
                .code(department.getCode())
                .name(department.getName())
                .parentDepartmentId(department.getParentDepartmentId())
                .parentDepartmentName(parentDepartmentName[0])
                .status(department.getStatus())
                .createdAt(department.getCreatedAt())
                .build();
    }
}
