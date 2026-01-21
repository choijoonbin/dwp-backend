package com.dwp.services.auth.service.admin.departments;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.CreateDepartmentRequest;
import com.dwp.services.auth.dto.admin.DepartmentSummary;
import com.dwp.services.auth.dto.admin.UpdateDepartmentRequest;
import com.dwp.services.auth.entity.Department;
import com.dwp.services.auth.repository.DepartmentRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부서 변경 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class DepartmentCommandService {
    
    private final DepartmentRepository departmentRepository;
    private final DepartmentQueryService departmentQueryService;
    private final AuditLogService auditLogService;
    private final CodeResolver codeResolver;
    
    /**
     * 부서 생성
     */
    public DepartmentSummary createDepartment(Long tenantId, Long actorUserId, CreateDepartmentRequest request,
                                              HttpServletRequest httpRequest) {
        // 코드 중복 체크
        if (departmentQueryService.existsByCode(tenantId, request.getCode())) {
            throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 부서 코드입니다.");
        }
        
        // 부모 부서 존재 확인
        if (request.getParentDepartmentId() != null) {
            departmentQueryService.findDepartment(tenantId, request.getParentDepartmentId());
        }
        
        // 상태 코드 검증
        String status = request.getStatus() != null ? request.getStatus() : "ACTIVE";
        codeResolver.validate("USER_STATUS", status); // USER_STATUS 코드 그룹 사용
        
        Department department = Department.builder()
                .tenantId(tenantId)
                .code(request.getCode())
                .name(request.getName())
                .parentDepartmentId(request.getParentDepartmentId())
                .status(status)
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
    public DepartmentSummary updateDepartment(Long tenantId, Long actorUserId, Long departmentId,
                                             UpdateDepartmentRequest request, HttpServletRequest httpRequest) {
        Department department = departmentQueryService.findDepartment(tenantId, departmentId);
        Department before = copyDepartment(department);
        
        if (request.getCode() != null) {
            // 코드 중복 체크 (본인 제외)
            if (departmentQueryService.existsByCodeExcludingId(tenantId, request.getCode(), departmentId)) {
                throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 부서 코드입니다.");
            }
            department.setCode(request.getCode());
        }
        if (request.getName() != null) {
            department.setName(request.getName());
        }
        if (request.getParentDepartmentId() != null) {
            departmentQueryService.findDepartment(tenantId, request.getParentDepartmentId());
            department.setParentDepartmentId(request.getParentDepartmentId());
        }
        if (request.getStatus() != null) {
            codeResolver.validate("USER_STATUS", request.getStatus());
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
    public void deleteDepartment(Long tenantId, Long actorUserId, Long departmentId, HttpServletRequest httpRequest) {
        Department department = departmentQueryService.findDepartment(tenantId, departmentId);
        Department before = copyDepartment(department);
        
        // Soft delete (status = INACTIVE)
        codeResolver.require("USER_STATUS", "INACTIVE");
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
