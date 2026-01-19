package com.dwp.services.auth.dto.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 이벤트 로그 항목 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLogItem {
    
    private Long sysEventLogId;
    private LocalDateTime occurredAt;
    private String eventType;
    private String resourceKey;
    private String action;
    private String label;
    private String visitorId;
    private Long userId;
    private String path;
    private Map<String, Object> metadata;
}
