package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.CaseExplanation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseExplanationRepository extends JpaRepository<CaseExplanation, Long> {
    List<CaseExplanation> findByTenantIdAndCaseIdOrderByCreatedAtDesc(Long tenantId, Long caseId);
}
