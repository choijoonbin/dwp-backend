package com.dwp.services.auth.dto.monitoring;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 이벤트 수집 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventCollectRequest {
    
    @NotBlank(message = "eventType은 필수입니다")
    private String eventType;
    
    @NotBlank(message = "resourceKey는 필수입니다")
    private String resourceKey;
    
    @NotBlank(message = "action은 필수입니다")
    private String action;
    
    private String label;
    private String visitorId;
    private String path;
    private String userId;
    private Map<String, Object> metadata;
}
