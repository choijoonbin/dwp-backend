package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거버넌스 설정 값 업데이트 요청 (프론트에서 항목 클릭 시).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGovernanceConfigRequest {

    @NotBlank(message = "value는 필수입니다.")
    private String value;  // 코드 값 (예: RBAC, ENFORCED)
}
