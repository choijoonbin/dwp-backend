package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * rag_chunk — RAG 청크
 */
@Entity
@Table(schema = "dwp_aura", name = "rag_chunk")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "page_no", nullable = false)
    @Builder.Default
    private Integer pageNo = 1;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "embedding_id")
    private String embeddingId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
