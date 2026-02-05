package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Aura 에이전트 활동 스트림. dwp_aura.agent_activity_log
 * event_type → stage 매핑 적용 후 저장.
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_activity_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "activity_id")
    private Long activityId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "stage", nullable = false, length = 50)
    private String stage;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_agent_id", length = 100)
    private String actorAgentId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_display_name", length = 200)
    private String actorDisplayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private Map<String, Object> metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
