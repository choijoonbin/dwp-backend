package com.dwp.services.main.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * HITL 거절 요청 DTO
 */
@Getter
@Setter
public class HitlRejectRequest {
    
    @NotBlank(message = "사용자 ID는 필수입니다")
    private String userId;
    
    private String reason;  // 거절 사유 (선택)
}
