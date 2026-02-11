package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
