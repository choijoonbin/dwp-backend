package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.ThoughtChainLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ThoughtChainLogRepository extends JpaRepository<ThoughtChainLog, Long> {

    List<ThoughtChainLog> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
