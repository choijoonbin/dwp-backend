package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunk, Long>, RagChunkRepositoryCustom {

    List<RagChunk> findByTenantIdAndDocIdAndIsActiveTrueOrderByChunkIndexAscChunkIdAsc(Long tenantId, Long docId);

    List<RagChunk> findByTenantIdAndDocIdAndIsActiveTrueOrderByPageNoAscChunkIdAsc(Long tenantId, Long docId);

    long countByTenantIdAndDocIdAndIsActiveTrue(Long tenantId, Long docId);

    /** JPQL DELETE만 수행(엔티티 로드 없음). embedding(pgvector) 컬럼 역직렬화 오류 회피. */
    @Modifying
    @Query("DELETE FROM RagChunk c WHERE c.tenantId = :tenantId AND c.docId = :docId")
    void deleteByTenantIdAndDocId(@Param("tenantId") Long tenantId, @Param("docId") Long docId);

    @Modifying
    @Query("UPDATE RagChunk c SET c.isActive = false WHERE c.tenantId = :tenantId AND c.docId = :docId AND c.isActive = true")
    int deactivateActiveByTenantIdAndDocId(@Param("tenantId") Long tenantId, @Param("docId") Long docId);

    long countByTenantIdAndDocIdAndVersion(Long tenantId, Long docId, String version);

    @Modifying
    @Query("UPDATE RagChunk c SET c.isActive = true WHERE c.tenantId = :tenantId AND c.docId = :docId AND c.version = :version")
    int activateByTenantIdAndDocIdAndVersion(@Param("tenantId") Long tenantId,
                                             @Param("docId") Long docId,
                                             @Param("version") String version);
}
