package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.dto.rag.RagHybridSearchRequest;

import java.util.List;

/**
 * RAG Hybrid Search Custom Repository
 * pgvector cosine distance + tsvector ts_rank_cd 연산은 JPQL/QueryDSL로 지원되지 않아
 * Native SQL 사용 (ADR: docs/adr/RAG_HYBRID_NATIVE_QUERY.md 참조)
 */
public interface RagChunkRepositoryCustom {

    /**
     * BM25 검색 (tsvector + ts_rank_cd)
     * @param tenantId 테넌트 ID
     * @param queryText 검색어
     * @param topK 결과 수
     * @param filters 필터 조건
     * @return 청크 ID + 순위 목록
     */
    List<ChunkRank> searchKeyword(Long tenantId, String queryText, int topK, RagHybridSearchRequest.SearchFilters filters);

    /**
     * Vector 검색 (pgvector cosine distance)
     * @param tenantId 테넌트 ID
     * @param embedding 쿼리 임베딩 벡터
     * @param topK 결과 수
     * @param filters 필터 조건
     * @return 청크 ID + 순위 목록
     */
    List<ChunkRank> searchVector(Long tenantId, float[] embedding, int topK, RagHybridSearchRequest.SearchFilters filters);

    /**
     * Parent 청크 조회 (조문 확장용)
     * @param tenantId 테넌트 ID
     * @param parentIds 부모 청크 ID 목록
     * @return 부모 청크 정보 목록
     */
    List<ParentChunkInfo> findParentChunks(Long tenantId, List<Long> parentIds);

    record ChunkRank(Long chunkId, int rank, Double score) {}

    record ParentChunkInfo(
            Long chunkId,
            Long docId,
            String nodeType,
            String chunkText,
            String regulationArticle,
            Integer chunkIndex
    ) {}
}
