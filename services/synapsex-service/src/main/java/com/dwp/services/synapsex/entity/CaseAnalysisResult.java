package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase2: case_analysis_result — 분석 결과 (run당 1건)
 */
@Entity
@Table(schema = "dwp_aura", name = "case_analysis_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseAnalysisResult {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "reason_text", columnDefinition = "TEXT")
    private String reasonText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confidence_json", columnDefinition = "jsonb")
    private JsonNode confidenceJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private JsonNode evidenceJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "similar_json", columnDefinition = "jsonb")
    private JsonNode similarJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rag_refs_json", columnDefinition = "jsonb")
    private JsonNode ragRefsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
