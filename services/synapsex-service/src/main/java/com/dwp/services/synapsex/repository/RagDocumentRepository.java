package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    List<RagDocument> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<RagDocument> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);

    java.util.Optional<RagDocument> findByDocIdAndTenantId(Long docId, Long tenantId);

    /** 콜백에서 상태만 확실히 반영 (엔티티 캐시 없이 직접 UPDATE). */
    @Modifying
    @Query("UPDATE RagDocument d SET d.status = :status, d.updatedAt = CURRENT_TIMESTAMP WHERE d.docId = :docId")
    int updateStatusByDocId(@Param("docId") Long docId, @Param("status") String status);

    /** 추적용: docId 기준으로 DB에 저장된 status만 조회 */
    @Query("SELECT d.status FROM RagDocument d WHERE d.docId = :docId")
    java.util.Optional<String> findStatusByDocId(@Param("docId") Long docId);
}
