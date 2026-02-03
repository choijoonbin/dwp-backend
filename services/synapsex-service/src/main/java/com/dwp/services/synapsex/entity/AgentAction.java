package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * agent_action — AI 에이전트 조치 (Phase 2 확장)
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_action")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "action_id")
    private Long actionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_payload", columnDefinition = "jsonb")
    private JsonNode actionPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private JsonNode payloadJson;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_by_actor_type", length = 20)
    @Builder.Default
    private String requestedByActorType = "USER";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "simulation_before", columnDefinition = "jsonb")
    private JsonNode simulationBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "simulation_after", columnDefinition = "jsonb")
    private JsonNode simulationAfter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_json", columnDefinition = "jsonb")
    private JsonNode diffJson;

    @Column(name = "planned_at", nullable = false)
    private Instant plannedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PROPOSED";

    @Column(name = "executed_by", length = 50)
    @Builder.Default
    private String executedBy = "PENDING";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (plannedAt == null) plannedAt = now;
    }
}
