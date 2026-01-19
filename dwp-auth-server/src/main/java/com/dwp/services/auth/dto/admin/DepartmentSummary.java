package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 부서 요약 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentSummary {
    
    private Long departmentId;
    private String code;
    private String name;
    private Long parentDepartmentId;
    private String parentDepartmentName;
    private String status;
    private LocalDateTime createdAt;
}
