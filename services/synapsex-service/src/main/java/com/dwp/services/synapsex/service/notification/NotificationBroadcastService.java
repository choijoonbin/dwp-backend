package com.dwp.services.synapsex.service.notification;

import com.dwp.services.synapsex.dto.notification.NotificationDto;
import com.dwp.services.synapsex.entity.SysNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 알림 DB 저장 + WebSocket 브로드캐스트.
 * Redis 수신 이벤트를 sys_notifications에 저장하고 /topic/notifications 로 전송.
 * FE 구독 경로와 동일해야 함: client.subscribe('/topic/notifications', callback).
 * 테넌트 격리 경로(/topic/notifications/{tenantId}) 미사용 — payload.tenantId로 FE에서 필터링.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationBroadcastService {

    private final NotificationCommandService notificationCommandService;
    private final SimpMessagingTemplate messagingTemplate;

    /** FE 구독 경로와 일치. 규격: docs/integration/NOTIFICATION_WEBSOCKET_SPEC.md */
    private static final String WS_TOPIC = "/topic/notifications";

    /**
     * 알림 저장 후 접속 중인 클라이언트에게 브로드캐스트.
     * tenant_id는 payload에 포함되어 FE에서 필터링 가능.
     */
    public void saveAndBroadcast(Long tenantId, Long userId, String title, String content,
                                  String type, String channel, java.time.Instant occurredAt,
                                  Map<String, Object> payloadJson) {
        SysNotification saved = notificationCommandService.save(
                tenantId, userId, title, content, type, channel, occurredAt, payloadJson);
        NotificationDto dto = toDto(saved);
        try {
            messagingTemplate.convertAndSend(WS_TOPIC, dto);
            log.info("Notification broadcast succeeded destination={} type={} tenantId={} id={} (Redis→WS bridge, e.g. workbench:case:action)", WS_TOPIC, type, tenantId, saved.getId());
            log.debug("Notification broadcast destination={} type={} tenantId={} id={}", WS_TOPIC, type, tenantId, saved.getId());
        } catch (Exception e) {
            log.warn("Notification WebSocket send failed destination={} type={}: {}", WS_TOPIC, type, e.getMessage());
        }
    }

    private static NotificationDto toDto(SysNotification n) {
        Map<String, Object> payload = n.getPayloadJson() != null ? new HashMap<>(n.getPayloadJson()) : null;
        String link = null;
        if (payload != null && payload.get("link") instanceof String) {
            link = (String) payload.get("link");
        } else if (payload != null && payload.get("case_id") != null) {
            link = "/synapse/cases/" + payload.get("case_id");
        } else if (payload != null && payload.get("docId") != null) {
            link = "/synapse/rag/documents/" + payload.get("docId");
        }
        return NotificationDto.builder()
                .id(n.getId())
                .tenantId(n.getTenantId())
                .userId(n.getUserId())
                .title(n.getTitle())
                .content(n.getContent())
                .type(n.getType())
                .channel(n.getChannel())
                .link(link)
                .occurredAt(n.getOccurredAt())
                .createdAt(n.getCreatedAt())
                .readAt(n.getReadAt())
                .payload(payload)
                .build();
    }
}
