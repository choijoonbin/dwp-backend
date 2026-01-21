package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Department;
import com.dwp.services.auth.repository.DepartmentRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
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
 * 부서 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DepartmentManagementService {
    
    private final DepartmentRepository departmentRepository;
    private final AuditLogService auditLogService;
    
    /**
     * 부서 목록 조회
     */
    @Transactional(readOnly = true)
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
     * 부서 생성
     */
    @Transactional
    public DepartmentSummary createDepartment(Long tenantId, Long actorUserId, CreateDepartmentRequest request,
                                              HttpServletRequest httpRequest) {
        // 코드 중복 체크
        departmentRepository.findByTenantIdAndCode(tenantId, request.getCode())
                .ifPresent(d -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 부서 코드입니다.");
                });
        
        // 부모 부서 존재 확인
        if (request.getParentDepartmentId() != null) {
            departmentRepository.findByTenantIdAndDepartmentId(tenantId, request.getParentDepartmentId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 부서를 찾을 수 없습니다."));
        }
        
        Department department = Department.builder()
                .tenantId(tenantId)
                .code(request.getCode())
                .name(request.getName())
                .parentDepartmentId(request.getParentDepartmentId())
                .status(request.getStatus() != null ? request.getStatus() : "ACTIVE")
                .build();
        department = departmentRepository.save(department);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "DEPARTMENT_CREATE", "DEPARTMENT", department.getDepartmentId(),
                null, department, httpRequest);
        
        return toDepartmentSummary(tenantId, department);
    }
    
    /**
     * 부서 수정
     */
    @Transactional
    public DepartmentSummary updateDepartment(Long tenantId, Long actorUserId, Long departmentId,
                                             UpdateDepartmentRequest request, HttpServletRequest httpRequest) {
        Department department = departmentRepository.findByTenantIdAndDepartmentId(tenantId, departmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
        
        Department before = copyDepartment(department);
        
        if (request.getCode() != null) {
            // 코드 중복 체크 (본인 제외)
            departmentRepository.findByTenantIdAndCode(tenantId, request.getCode())
                    .filter(d -> !d.getDepartmentId().equals(departmentId))
                    .ifPresent(d -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 부서 코드입니다.");
                    });
            department.setCode(request.getCode());
        }
        if (request.getName() != null) {
            department.setName(request.getName());
        }
        if (request.getParentDepartmentId() != null) {
            departmentRepository.findByTenantIdAndDepartmentId(tenantId, request.getParentDepartmentId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 부서를 찾을 수 없습니다."));
            department.setParentDepartmentId(request.getParentDepartmentId());
        }
        if (request.getStatus() != null) {
            department.setStatus(request.getStatus());
        }
        
        department = departmentRepository.save(department);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "DEPARTMENT_UPDATE", "DEPARTMENT", departmentId,
                before, department, httpRequest);
        
        return toDepartmentSummary(tenantId, department);
    }
    
    /**
     * 부서 삭제 (soft delete)
     */
    @Transactional
    public void deleteDepartment(Long tenantId, Long actorUserId, Long departmentId, HttpServletRequest httpRequest) {
        Department department = departmentRepository.findByTenantIdAndDepartmentId(tenantId, departmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
        
        Department before = copyDepartment(department);
        
        // Soft delete (status = INACTIVE)
        department.setStatus("INACTIVE");
        departmentRepository.save(department);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "DEPARTMENT_DELETE", "DEPARTMENT", departmentId,
                before, department, httpRequest);
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
    
    private Department copyDepartment(Department department) {
        return Department.builder()
                .departmentId(department.getDepartmentId())
                .tenantId(department.getTenantId())
                .code(department.getCode())
                .name(department.getName())
                .parentDepartmentId(department.getParentDepartmentId())
                .status(department.getStatus())
                .build();
    }
}
