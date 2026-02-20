package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * rag_chunk — RAG 청크 (백엔드 단일 소유. Aura는 벡터화 결과만 반환, INSERT는 BE에서 수행)
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

    /** 문서 내 순서 (Aura 요구사항, 추론 시 문맥 파악용) */
    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "page_no")
    @Builder.Default
    private Integer pageNo = 1;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    /** OpenAI embedding 1536차원 (pgvector). Aura 벡터화 결과 저장. JdbcTypeCode(VECTOR)로 DB 역직렬화 오류 방지. */
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1536)
    private float[] embedding;

    /** 페이지 번호, 파일 경로 등 부가 메타데이터 (Aura 요구사항) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private Map<String, Object> metadataJson;

    @Column(name = "embedding_id")
    private String embeddingId;

    /** 규정 조항 (예: "제11조", "제3장") */
    @Column(name = "regulation_article", length = 100)
    private String regulationArticle;

    /** 규정 항목 (예: "2항", "제1호") */
    @Column(name = "regulation_clause", length = 100)
    private String regulationClause;

    /** prefix 제거된 정제 본문 (BM25/임베딩 검색용) */
    @Column(name = "search_text", columnDefinition = "TEXT")
    private String searchText;

    /** 부모 청크 ID (조문-항/호 Parent-Child 관계) */
    @Column(name = "parent_id")
    private Long parentId;

    /** 노드 유형: ARTICLE(조문), CLAUSE(항/호), PARAGRAPH(문단) */
    @Column(name = "node_type", length = 20)
    private String nodeType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
