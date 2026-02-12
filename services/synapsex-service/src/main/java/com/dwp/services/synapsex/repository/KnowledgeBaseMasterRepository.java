package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.KnowledgeBaseMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseMasterRepository extends JpaRepository<KnowledgeBaseMaster, Long> {

    List<KnowledgeBaseMaster> findByTenantIdOrderByKnowledgeIdAsc(Long tenantId);

    Optional<KnowledgeBaseMaster> findByTenantIdAndKnowledgeId(Long tenantId, Long knowledgeId);

    List<KnowledgeBaseMaster> findByTenantIdAndOwnerDomainOrderByNameAsc(Long tenantId, String ownerDomain);
}
