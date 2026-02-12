package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentPromptHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentPromptHistoryRepository extends JpaRepository<AgentPromptHistory, Long> {

    List<AgentPromptHistory> findByAgentIdOrderByVersionDesc(Long agentId);

    @Query("SELECT p FROM AgentPromptHistory p WHERE p.agentId = :agentId AND p.isCurrent = true")
    Optional<AgentPromptHistory> findCurrentByAgentId(@Param("agentId") Long agentId);

    Optional<AgentPromptHistory> findByAgentIdAndVersion(Long agentId, Integer version);

    @Modifying
    @Query("UPDATE AgentPromptHistory p SET p.isCurrent = false WHERE p.agentId = :agentId")
    int clearCurrentByAgentId(@Param("agentId") Long agentId);
}
