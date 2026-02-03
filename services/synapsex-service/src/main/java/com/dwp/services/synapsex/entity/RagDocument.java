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

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "url")
    private String url;

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
