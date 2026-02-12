package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * agent_master — 에이전트 스튜디오: 에이전트 마스터 (tenant 격리)
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Aura 호출 시 사용하는 키. Snake Case 권장 (예: finance_aura, hr_aura). tenant 내 unique */
    @Column(name = "agent_key", nullable = false, length = 100)
    private String agentKey;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "domain", length = 100)
    private String domain;

    @Column(name = "model_name", length = 255)
    private String modelName;

    @Column(name = "temperature", precision = 5, scale = 4)
    private BigDecimal temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
