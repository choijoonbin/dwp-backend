package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentDocumentMapping;
import com.dwp.services.synapsex.entity.AgentDocumentMappingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * agent_document_mapping Repository
 */
public interface AgentDocumentMappingRepository extends JpaRepository<AgentDocumentMapping, AgentDocumentMappingId> {

    /** 테넌트 내 에이전트에 연결된 문서 ID 목록 조회 */
    @Query("SELECT adm.docId FROM AgentDocumentMapping adm WHERE adm.tenantId = :tenantId AND adm.agentId = :agentId")
    List<Long> findDocIdsByTenantIdAndAgentId(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId);

    /** 테넌트 내 에이전트의 특정 문서 연결 여부 확인 */
    boolean existsByTenantIdAndAgentIdAndDocId(Long tenantId, Long agentId, Long docId);

    /** 테넌트 내 에이전트의 모든 문서 연결 삭제 */
    @Modifying
    @Query("DELETE FROM AgentDocumentMapping adm WHERE adm.tenantId = :tenantId AND adm.agentId = :agentId")
    void deleteByTenantIdAndAgentId(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId);

    /** 테넌트 내 특정 문서 연결 삭제 */
    @Modifying
    @Query("DELETE FROM AgentDocumentMapping adm WHERE adm.tenantId = :tenantId AND adm.agentId = :agentId AND adm.docId = :docId")
    void deleteByTenantIdAndAgentIdAndDocId(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId, @Param("docId") Long docId);

    /** 테넌트별 에이전트의 문서 연결 조회 */
    @Query("SELECT adm FROM AgentDocumentMapping adm WHERE adm.tenantId = :tenantId AND adm.agentId = :agentId")
    List<AgentDocumentMapping> findByTenantIdAndAgentId(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId);

    /** 문서별 참조(매핑) 수 */
    long countByTenantIdAndDocId(Long tenantId, Long docId);

    /** 목록 조회용: doc_id별 참조(매핑) 수 집계 */
    @Query("SELECT adm.docId, COUNT(adm) FROM AgentDocumentMapping adm WHERE adm.tenantId = :tenantId AND adm.docId IN :docIds GROUP BY adm.docId")
    List<Object[]> countByTenantIdAndDocIds(@Param("tenantId") Long tenantId, @Param("docIds") List<Long> docIds);
}
