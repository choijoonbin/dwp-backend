package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * agent_case — AI 에이전트 케이스
 */
@Entity
@Table(schema = "dwp_aura", name = "agent_case")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "bukrs", length = 4)
    private String bukrs;

    @Column(name = "belnr", length = 10)
    private String belnr;

    @Column(name = "gjahr", length = 4)
    private String gjahr;

    @Column(name = "buzei", length = 3)
    private String buzei;

    @Column(name = "case_type", nullable = false, length = 50)
    private String caseType;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "score", precision = 6, scale = 4)
    private BigDecimal score;

    @Column(name = "reason_text", columnDefinition = "TEXT")
    private String reasonText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private JsonNode evidenceJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rag_refs_json", columnDefinition = "jsonb")
    private JsonNode ragRefsJson;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "owner_user", length = 80)
    private String ownerUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
