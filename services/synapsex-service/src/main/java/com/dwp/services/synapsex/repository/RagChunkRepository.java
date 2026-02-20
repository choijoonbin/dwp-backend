package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunk, Long>, RagChunkRepositoryCustom {

    List<RagChunk> findByTenantIdAndDocIdOrderByChunkIndexAscChunkIdAsc(Long tenantId, Long docId);

    List<RagChunk> findByTenantIdAndDocIdOrderByPageNoAscChunkIdAsc(Long tenantId, Long docId);

    long countByTenantIdAndDocId(Long tenantId, Long docId);

    void deleteByTenantIdAndDocId(Long tenantId, Long docId);
}
