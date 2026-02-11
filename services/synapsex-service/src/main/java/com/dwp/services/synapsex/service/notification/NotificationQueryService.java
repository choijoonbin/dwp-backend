package com.dwp.services.synapsex.service.notification;

import com.dwp.services.synapsex.dto.notification.NotificationDto;
import com.dwp.services.synapsex.entity.SysNotification;
import com.dwp.services.synapsex.repository.SysNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 알림 센터 조회 — 저장된 알림 목록 (나중에 다시 보기).
 */
@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    private final SysNotificationRepository repository;

    @Transactional(readOnly = true)
    public Page<NotificationDto> findByTenant(Long tenantId, Pageable pageable) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable).map(NotificationQueryService::toDto);
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
