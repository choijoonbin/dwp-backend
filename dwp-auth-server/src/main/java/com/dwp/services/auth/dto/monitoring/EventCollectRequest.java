package com.dwp.services.auth.dto.monitoring;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 이벤트 수집 요청 DTO
 * 
 * action (권장, 표준): UI_ACTION 코드 기준 (VIEW, CLICK, EXECUTE 등)
 * eventType (deprecated): action이 없을 때만 사용, 향후 제거 예정
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventCollectRequest {
    
    /**
     * 이벤트 타입 (deprecated)
     * action이 없을 때만 사용되며, 향후 제거 예정
     */
    private String eventType;
    
    @NotBlank(message = "resourceKey는 필수입니다")
    private String resourceKey;
    
    /**
     * 액션 코드 (권장, 표준)
     * UI_ACTION 코드 기준 (VIEW, CLICK, EXECUTE, SCROLL, SEARCH, FILTER, DOWNLOAD, OPEN, CLOSE, SUBMIT)
     * 소문자/혼용 입력도 대문자로 정규화됨
     */
    private String action;
    
    private String label;
    private String visitorId;
    private String path;
    private String userId;
    private Map<String, Object> metadata;
}
