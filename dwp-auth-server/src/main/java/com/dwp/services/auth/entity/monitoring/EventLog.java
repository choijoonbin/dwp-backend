package com.dwp.services.auth.entity.monitoring;

import com.dwp.services.auth.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 이벤트 로그 엔티티 (sys_event_logs)
 * 
 * 클릭, 실행 등 사용자 액션을 추적하는 이벤트 로그
 */
@Entity
@Table(name = "sys_event_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventLog extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sys_event_log_id")
    private Long sysEventLogId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
    
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;
    
    @Column(name = "resource_key", nullable = false, length = 255)
    private String resourceKey;
    
    @Column(name = "action", nullable = false, length = 100)
    private String action;
    
    @Column(name = "label", length = 200)
    private String label;
    
    @Column(name = "visitor_id", length = 255)
    private String visitorId;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "path", length = 500)
    private String path;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    
    @Column(name = "ip_address", length = 50)
    private String ipAddress;
    
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;
}
