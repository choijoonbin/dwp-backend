package com.dwp.services.synapsex.dto.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 실시간 알림 DTO — WebSocket 브로드캐스트 및 알림 센터 API 응답.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationDto {

    private Long id;
    private Long tenantId;
    private Long userId;
    private String title;
    private String content;
    private String type;       // CASE_ACTION, RAG_STATUS, etc.
    private String channel;   // source Redis channel
    private Instant occurredAt;
    private Instant createdAt;
    private Instant readAt;
    private Map<String, Object> payload;
}
