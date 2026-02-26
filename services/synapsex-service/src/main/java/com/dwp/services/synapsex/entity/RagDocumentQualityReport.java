package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "dwp_aura", name = "rag_document_quality_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagDocumentQualityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "quality_gate_passed", nullable = false)
    private Boolean qualityGatePassed;

    @Column(name = "input_chunks", nullable = false)
    private Integer inputChunks;

    @Column(name = "final_chunks", nullable = false)
    private Integer finalChunks;

    @Column(name = "article_coverage", nullable = false, precision = 5, scale = 4)
    private BigDecimal articleCoverage;

    @Column(name = "noise_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal noiseRate;

    @Column(name = "duplicate_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal duplicateRate;

    @Column(name = "short_chunk_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal shortChunkRate;

    @Column(name = "removed_empty", nullable = false)
    @Builder.Default
    private Integer removedEmpty = 0;

    @Column(name = "removed_heading_only", nullable = false)
    @Builder.Default
    private Integer removedHeadingOnly = 0;

    @Column(name = "removed_duplicate_exact", nullable = false)
    @Builder.Default
    private Integer removedDuplicateExact = 0;

    @Column(name = "removed_duplicate_near", nullable = false)
    @Builder.Default
    private Integer removedDuplicateNear = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_required", columnDefinition = "jsonb")
    private JsonNode missingRequired;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors", columnDefinition = "jsonb")
    private JsonNode errors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_report_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode rawReportJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
