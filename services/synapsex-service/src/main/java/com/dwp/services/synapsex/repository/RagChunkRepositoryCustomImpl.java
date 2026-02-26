package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.dto.rag.RagHybridSearchRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG Hybrid Search Custom Repository 구현
 * Native SQL 사용 사유: pgvector <=> 연산자, tsvector ts_rank_cd 함수는 JPQL/QueryDSL 미지원
 */
@Slf4j
@Repository
public class RagChunkRepositoryCustomImpl implements RagChunkRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<ChunkRank> searchKeyword(Long tenantId, String queryText, int topK, RagHybridSearchRequest.SearchFilters filters) {
        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT c.chunk_id, ts_rank_cd(c.search_tsv, websearch_to_tsquery('simple', :query)) as score
            FROM dwp_aura.rag_chunk c
            WHERE c.tenant_id = :tenantId
              AND c.is_active = true
              AND c.search_tsv @@ websearch_to_tsquery('simple', :query)
            """);

        appendFilters(sql, filters);
        sql.append(" ORDER BY score DESC LIMIT :topK");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        query.setParameter("query", queryText);
        query.setParameter("topK", topK);
        setFilterParams(query, filters);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        
        AtomicInteger rankCounter = new AtomicInteger(1);
        return rows.stream()
                .map(r -> new ChunkRank(
                        ((Number) r[0]).longValue(),
                        rankCounter.getAndIncrement(),
                        ((Number) r[1]).doubleValue()))
                .toList();
    }

    @Override
    public List<ChunkRank> searchVector(Long tenantId, float[] embedding, int topK, RagHybridSearchRequest.SearchFilters filters) {
        if (embedding == null || embedding.length == 0) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT c.chunk_id, 1 - (c.embedding <=> :embedding::vector) as score
            FROM dwp_aura.rag_chunk c
            WHERE c.tenant_id = :tenantId
              AND c.is_active = true
              AND c.embedding IS NOT NULL
            """);

        appendFilters(sql, filters);
        sql.append(" ORDER BY c.embedding <=> :embedding::vector LIMIT :topK");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        query.setParameter("embedding", vectorToString(embedding));
        query.setParameter("topK", topK);
        setFilterParams(query, filters);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        
        AtomicInteger rankCounter = new AtomicInteger(1);
        return rows.stream()
                .map(r -> new ChunkRank(
                        ((Number) r[0]).longValue(),
                        rankCounter.getAndIncrement(),
                        ((Number) r[1]).doubleValue()))
                .toList();
    }

    @Override
    public List<ParentChunkInfo> findParentChunks(Long tenantId, List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }

        String sql = """
            SELECT c.chunk_id, c.doc_id, c.node_type, c.chunk_text, c.regulation_article, c.chunk_index
            FROM dwp_aura.rag_chunk c
            WHERE c.tenant_id = :tenantId
              AND c.is_active = true
              AND c.chunk_id IN (:parentIds)
            ORDER BY c.doc_id, c.chunk_index
            """;

        Query query = em.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("parentIds", parentIds);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(r -> new ParentChunkInfo(
                        ((Number) r[0]).longValue(),
                        ((Number) r[1]).longValue(),
                        (String) r[2],
                        (String) r[3],
                        (String) r[4],
                        r[5] != null ? ((Number) r[5]).intValue() : null
                ))
                .toList();
    }

    private void appendFilters(StringBuilder sql, RagHybridSearchRequest.SearchFilters filters) {
        if (filters == null) return;
        if (filters.getDocId() != null && !filters.getDocId().isBlank()) {
            sql.append(" AND c.doc_id = :docId");
        }
        if (filters.getArticle() != null && !filters.getArticle().isBlank()) {
            sql.append(" AND c.regulation_article = :article");
        }
        if (filters.getClause() != null && !filters.getClause().isBlank()) {
            sql.append(" AND c.regulation_clause = :clause");
        }
        if (filters.getNodeType() != null && !filters.getNodeType().isBlank()) {
            sql.append(" AND c.node_type = :nodeType");
        }
    }

    private void setFilterParams(Query query, RagHybridSearchRequest.SearchFilters filters) {
        if (filters == null) return;
        if (filters.getDocId() != null && !filters.getDocId().isBlank()) {
            query.setParameter("docId", Long.parseLong(filters.getDocId()));
        }
        if (filters.getArticle() != null && !filters.getArticle().isBlank()) {
            query.setParameter("article", filters.getArticle());
        }
        if (filters.getClause() != null && !filters.getClause().isBlank()) {
            query.setParameter("clause", filters.getClause());
        }
        if (filters.getNodeType() != null && !filters.getNodeType().isBlank()) {
            query.setParameter("nodeType", filters.getNodeType());
        }
    }

    private String vectorToString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
