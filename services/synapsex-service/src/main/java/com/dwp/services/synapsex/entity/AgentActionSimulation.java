package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * agent_action_simulation — Agent Tool simulate API 전용
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_action_simulation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentActionSimulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "simulation_id")
    private Long simulationId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private JsonNode payloadJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    private JsonNode beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    private JsonNode afterJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_json", columnDefinition = "jsonb")
    private JsonNode validationJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_actor", length = 20)
    private String createdByActor;

    @Column(name = "created_by_id")
    private Long createdById;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
