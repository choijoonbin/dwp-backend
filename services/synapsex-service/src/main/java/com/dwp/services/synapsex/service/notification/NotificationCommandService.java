package com.dwp.services.synapsex.service.notification;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.entity.SysNotification;
import com.dwp.services.synapsex.repository.SysNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 알림 저장·읽음 처리 — Redis 수신 이벤트를 sys_notifications에 기록, PATCH 읽음/전체 읽음.
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

    /** 단건 읽음 처리. tenant 일치 시에만 갱신. */
    @Transactional
    public void markAsRead(Long tenantId, Long id) {
        SysNotification n = repository.findById(id)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "알림을 찾을 수 없습니다."));
        if (!n.getTenantId().equals(tenantId)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "해당 알림에 대한 권한이 없습니다.");
        }
        n.setReadAt(Instant.now());
        repository.save(n);
    }

    /** 테넌트(및 선택적 userId) 기준 미읽음 전체 읽음 처리. */
    @Transactional
    public int markAllAsRead(Long tenantId, Long userId) {
        List<SysNotification> list = userId != null
                ? repository.findByTenantIdAndUserIdAndReadAtIsNull(tenantId, userId)
                : repository.findByTenantIdAndReadAtIsNull(tenantId);
        Instant now = Instant.now();
        list.forEach(n -> n.setReadAt(now));
        repository.saveAll(list);
        return list.size();
    }
}
