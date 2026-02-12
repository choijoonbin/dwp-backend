package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMasterRepository extends JpaRepository<AgentMaster, Long> {

    List<AgentMaster> findByTenantIdAndIsActiveTrueOrderByAgentIdAsc(Long tenantId);

    Optional<AgentMaster> findByTenantIdAndAgentId(Long tenantId, Long agentId);

    Optional<AgentMaster> findByTenantIdAndAgentKey(Long tenantId, String agentKey);

    boolean existsByTenantIdAndAgentId(Long tenantId, Long agentId);

    boolean existsByTenantIdAndAgentKey(Long tenantId, String agentKey);
}
