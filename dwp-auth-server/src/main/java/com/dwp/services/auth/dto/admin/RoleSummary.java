package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 역할 요약 DTO
 * 
 * 프론트엔드 계약: RoleSummary 타입과 일치
 * - id: string (필수)
 * - status: 'ACTIVE' | 'INACTIVE' (필수)
 * - memberCount: number (선택)
 * - comRoleId: 호환 유지를 위해 유지 (과도기)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleSummary {
    
    private String id; // role_id를 문자열로 변환 (프론트 타입: string)
    private Long comRoleId; // 호환 유지용 (과도기 1~2주)
    private String roleCode;
    private String roleName;
    private String status; // 'ACTIVE' | 'INACTIVE' (필수)
    private String description;
    private LocalDateTime createdAt;
    private Integer memberCount; // 선택 필드 (권장) - 전체 멤버 수 (USER + DEPARTMENT)
    private Integer userCount; // 선택 필드 - USER 멤버 수
    private Integer departmentCount; // 선택 필드 - DEPARTMENT 멤버 수
}
