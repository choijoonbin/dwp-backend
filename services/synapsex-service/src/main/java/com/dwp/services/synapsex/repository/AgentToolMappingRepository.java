package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentToolMapping;
import com.dwp.services.synapsex.entity.AgentToolMappingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentToolMappingRepository extends JpaRepository<AgentToolMapping, AgentToolMappingId> {

    List<AgentToolMapping> findByAgentId(Long agentId);

    @Query("SELECT m.toolId FROM AgentToolMapping m WHERE m.agentId = :agentId")
    List<Long> findToolIdsByAgentId(@Param("agentId") Long agentId);

    void deleteByAgentId(Long agentId);
}
