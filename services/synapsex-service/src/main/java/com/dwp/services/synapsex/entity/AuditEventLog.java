package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Synapse 감사 이벤트 SoT. dwp_aura.audit_event_log
 */
@Entity
@Table(schema = "dwp_aura", name = "audit_event_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "event_category", nullable = false)
    private String eventCategory;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "actor_type")
    private String actorType; // HUMAN | AGENT | SYSTEM

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_agent_id")
    private String actorAgentId;

    @Column(name = "actor_display_name")
    private String actorDisplayName;

    @Column(name = "channel")
    private String channel; // WEB_UI | API | AGENT | INGESTION | INTEGRATION

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "outcome")
    private String outcome; // SUCCESS | FAILED | DENIED | NOOP

    @Column(name = "severity", nullable = false)
    @Builder.Default
    private String severity = "INFO";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    private Map<String, Object> beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    private Map<String, Object> afterJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_json", columnDefinition = "jsonb")
    private Map<String, Object> diffJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private Map<String, Object> evidenceJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private Map<String, Object> tags;

    @Column(name = "gateway_request_id")
    private String gatewayRequestId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "span_id")
    private String spanId;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (severity == null) severity = "INFO";
    }
}
