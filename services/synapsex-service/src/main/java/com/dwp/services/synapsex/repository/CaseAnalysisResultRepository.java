package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.CaseAnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CaseAnalysisResultRepository extends JpaRepository<CaseAnalysisResult, UUID> {

    Optional<CaseAnalysisResult> findByRunId(UUID runId);
}
