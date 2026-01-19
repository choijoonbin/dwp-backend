package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 페이지뷰 및 이벤트 로그 엔티티 (sys_page_view_events)
 */
@Entity
@Table(name = "sys_page_view_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageViewEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "page_view_event_id")
    private Long pageViewEventId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "page_key", nullable = false, length = 255)
    private String pageKey;

    @Column(name = "referrer", length = 500)
    private String referrer;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    // P0-3 확장 컬럼 (V3 migration으로 추가 예정)
    @Column(name = "event_type", length = 50)
    private String eventType; // PAGE_VIEW, CLICK, etc.

    @Column(name = "event_name", length = 100)
    private String eventName;

    @Column(name = "target_key", length = 255)
    private String targetKey;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;
}
