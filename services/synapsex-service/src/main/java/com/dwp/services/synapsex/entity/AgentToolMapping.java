package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * agent_tool_mapping — 에이전트–도구 M:N 매핑 (tenant는 agent_master 기준 격리)
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_tool_mapping")
@IdClass(AgentToolMappingId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentToolMapping {

    @Id
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Id
    @Column(name = "tool_id", nullable = false)
    private Long toolId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", insertable = false, updatable = false)
    private AgentMaster agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tool_id", insertable = false, updatable = false)
    private AgentToolInventory tool;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
