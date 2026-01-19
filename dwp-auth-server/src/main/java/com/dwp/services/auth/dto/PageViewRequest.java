package com.dwp.services.auth.dto;

import lombok.*;

import java.util.Map;

/**
 * 페이지뷰 수집 요청
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageViewRequest {
    private String pageKey; // 추가
    private String routeName;
    private String path;
    private String language;
    private String visitorId; // session_id or uuid
    private String deviceType;
    private String referrer;
    private Map<String, Object> metadata;
}
