package com.dwp.services.auth.dto;

import lombok.*;

import java.util.Map;

/**
 * 이벤트 수집 요청
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventRequest {
    private String eventType;
    private String eventName;
    private String path;
    private String routeName;
    private String targetKey;
    private String visitorId;
    private Map<String, Object> metadata;
}
