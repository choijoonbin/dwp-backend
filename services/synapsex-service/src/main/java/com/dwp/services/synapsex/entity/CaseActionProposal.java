package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase2: case_action_proposal — AI 권고 조치
 */
@Entity
@Table(schema = "dwp_aura", name = "case_action_proposal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseActionProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "proposal_id")
    private UUID proposalId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "dedup_key", nullable = false, length = 64)
    private String dedupKey;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private JsonNode payloadJson;

    @Column(name = "requires_approval")
    private Boolean requiresApproval;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_FAILED = "FAILED";
}
