package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunk, Long>, RagChunkRepositoryCustom {

    List<RagChunk> findByTenantIdAndDocIdOrderByChunkIndexAscChunkIdAsc(Long tenantId, Long docId);

    List<RagChunk> findByTenantIdAndDocIdOrderByPageNoAscChunkIdAsc(Long tenantId, Long docId);

    long countByTenantIdAndDocId(Long tenantId, Long docId);

    /** JPQL DELETE만 수행(엔티티 로드 없음). embedding(pgvector) 컬럼 역직렬화 오류 회피. */
    @Modifying
    @Query("DELETE FROM RagChunk c WHERE c.tenantId = :tenantId AND c.docId = :docId")
    void deleteByTenantIdAndDocId(@Param("tenantId") Long tenantId, @Param("docId") Long docId);
}
