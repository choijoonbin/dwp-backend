package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "reason_text", columnDefinition = "TEXT")
    private String reasonText;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "violation_clause", columnDefinition = "TEXT")
    private String violationClause;

    @Column(name = "reasoning_summary", columnDefinition = "TEXT")
    private String reasoningSummary;

    @Column(name = "recommended_action", columnDefinition = "TEXT")
    private String recommendedAction;

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

    /** 사실-규정 매핑: fi_doc_item(docId, itemId) ↔ rag_chunk(chunk_id) 1:1. API 응답 필드명: evidenceMapJson(camelCase) */
    @JsonProperty("evidenceMapJson")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_map_json", columnDefinition = "jsonb")
    private JsonNode evidenceMapJson;

    /** 문장별 근거 매핑 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sentence_citation_map", columnDefinition = "jsonb")
    private JsonNode sentenceCitationMap;

    /** 분석 점수 상세 분해 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_score_breakdown", columnDefinition = "jsonb")
    private JsonNode analysisScoreBreakdown;

    /** 품질 게이트 코드 목록(JSON 배열) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_gate_codes", columnDefinition = "jsonb")
    private JsonNode qualityGateCodes;

    /** 사용자 노출용 분석 신뢰 신호 목록(JSON 배열) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_quality_signals", columnDefinition = "jsonb")
    private JsonNode analysisQualitySignals;

    /** 근거 커버리지 비율(0~1) */
    @Column(name = "grounding_coverage_ratio", precision = 5, scale = 4)
    private BigDecimal groundingCoverageRatio;

    /** 근거 미연결 claim 문장 수 */
    @Column(name = "ungrounded_claim_sentences")
    private Integer ungroundedClaimSentences;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
