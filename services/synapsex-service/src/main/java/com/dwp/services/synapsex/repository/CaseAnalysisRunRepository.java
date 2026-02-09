package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.CaseAnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseAnalysisRunRepository extends JpaRepository<CaseAnalysisRun, UUID> {

    Optional<CaseAnalysisRun> findByRunIdAndTenantId(UUID runId, Long tenantId);

    List<CaseAnalysisRun> findByTenantIdAndCaseIdOrderByStartedAtDesc(Long tenantId, Long caseId);
}
