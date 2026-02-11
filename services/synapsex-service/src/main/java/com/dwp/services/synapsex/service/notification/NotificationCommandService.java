package com.dwp.services.synapsex.service.notification;

import com.dwp.services.synapsex.entity.SysNotification;
import com.dwp.services.synapsex.repository.SysNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 알림 저장 — Redis 수신 이벤트를 sys_notifications에 기록.
 */
@Service
@RequiredArgsConstructor
public class NotificationCommandService {

    private final SysNotificationRepository repository;

    @Transactional
    public SysNotification save(Long tenantId, Long userId, String title, String content,
                                 String type, String channel, Instant occurredAt, Map<String, Object> payloadJson) {
        Instant now = Instant.now();
        SysNotification n = SysNotification.builder()
                .tenantId(tenantId)
                .userId(userId)
                .title(title != null ? title : "")
                .content(content)
                .type(type != null ? type : "UNKNOWN")
                .channel(channel != null ? channel : "")
                .occurredAt(occurredAt != null ? occurredAt : now)
                .createdAt(now)
                .payloadJson(payloadJson)
                .build();
        return repository.save(n);
    }
}
