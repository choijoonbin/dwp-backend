package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 6: 조치 이력 — 승인/거절 시 누가, 왜, 어떻게 기록 (감사 추적).
 * dwp_aura.agent_case_action_history
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_case_action_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCaseActionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "action_type", nullable = false, length = 20)
    private String actionType;

    @Column(name = "actor_id", nullable = false, length = 50)
    private String actorId;

    @Column(name = "comment_text", columnDefinition = "TEXT")
    private String commentText;

    @Column(name = "action_at", nullable = false)
    private Instant actionAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private Map<String, Object> metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (actionAt == null) actionAt = Instant.now();
        if (createdAt == null) createdAt = Instant.now();
    }
}
