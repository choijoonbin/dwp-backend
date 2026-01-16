package com.dwp.services.main.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * HITL 승인 요청 DTO
 */
@Getter
@Setter
public class HitlApproveRequest {
    
    @NotBlank(message = "사용자 ID는 필수입니다")
    private String userId;
}
