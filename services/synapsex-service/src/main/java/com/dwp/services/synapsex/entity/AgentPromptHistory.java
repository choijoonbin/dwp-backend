package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * agent_prompt_history — 에이전트 스튜디오: 시스템 프롬프트 버전 이력
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_prompt_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentPromptHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prompt_id")
    private Long promptId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "system_instruction", nullable = false, columnDefinition = "TEXT")
    private String systemInstruction;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private Boolean isCurrent = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", insertable = false, updatable = false)
    private AgentMaster agent;
}
