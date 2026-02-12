package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentToolInventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentToolInventoryRepository extends JpaRepository<AgentToolInventory, Long> {

    Optional<AgentToolInventory> findByToolName(String toolName);

    List<AgentToolInventory> findAllByOrderByToolNameAsc();

    boolean existsByToolName(String toolName);
}
