package com.dwp.services.synapsex.dto.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 실시간 알림 DTO — WebSocket 브로드캐스트 및 알림 센터 API 응답.
 * DB occurred_at/created_at → API occurredAt/createdAt (camelCase).
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
    private String type;       // CASE_ACTION, RAG_STATUS, AI_DETECT, TRAINING_COMPLETE, APPROVAL_COMPLETE 등
    private String channel;   // source Redis channel
    /** 딥링크 (예: /synapse/cases/3). payload.link 또는 백엔드 생성 */
    private String link;
    @JsonProperty("occurredAt")
    private Instant occurredAt;
    @JsonProperty("createdAt")
    private Instant createdAt;
    @JsonProperty("readAt")
    private Instant readAt;
    private Map<String, Object> payload;
}
