package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * rag_document — RAG 문서 메타데이터
 */
@Entity
@Table(schema = "dwp_aura", name = "rag_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "source_type", nullable = false, length = 50)
    @Builder.Default
    private String sourceType = "UPLOAD";

    /** 문서 성격: REGULATION, MANUAL, POLICY 등. Aura ingest 시 메타데이터 인덱싱용 */
    @Column(name = "doc_type", length = 30)
    private String docType;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "url")
    private String url;

    /** 로컬 파일 저장 시 절대 경로. Aura document_path 전달용 */
    @Column(name = "file_path")
    private String filePath;

    @Column(name = "checksum", length = 64)
    private String checksum;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** 운영 라이프사이클 상태(ACTIVE/INACTIVE/DEPRECATED). 처리상태(status)와 분리. */
    @Column(name = "lifecycle_status", nullable = false, length = 20)
    @Builder.Default
    private String lifecycleStatus = "ACTIVE";

    @Column(name = "active_from")
    private Instant activeFrom;

    @Column(name = "active_to")
    private Instant activeTo;

    @Column(name = "quality_gate_passed", nullable = false)
    @Builder.Default
    private Boolean qualityGatePassed = false;

    @Column(name = "last_quality_score", precision = 5, scale = 4)
    private BigDecimal lastQualityScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_quality_report_json", columnDefinition = "jsonb")
    private com.fasterxml.jackson.databind.JsonNode lastQualityReportJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
