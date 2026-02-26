package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AnalysisReplayGateRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisReplayGateRunRepository extends JpaRepository<AnalysisReplayGateRun, Long> {

    Optional<AnalysisReplayGateRun> findFirstByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
