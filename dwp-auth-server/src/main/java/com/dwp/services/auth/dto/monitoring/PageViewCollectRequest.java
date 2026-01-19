package com.dwp.services.auth.dto.monitoring;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 페이지뷰 수집 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageViewCollectRequest {
    
    @NotBlank(message = "path는 필수입니다")
    private String path;
    
    private String menuKey;
    private String title;
    private String visitorId;
    private String device;
    private String referrer;
    private String userId;
    private Map<String, Object> metadata;
}
