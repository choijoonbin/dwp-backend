package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.CaseActionProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseActionProposalRepository extends JpaRepository<CaseActionProposal, UUID> {

    Optional<CaseActionProposal> findByProposalIdAndTenantId(UUID proposalId, Long tenantId);

    List<CaseActionProposal> findByTenantIdAndCaseIdOrderByCreatedAtDesc(Long tenantId, Long caseId);

    List<CaseActionProposal> findByTenantIdAndCaseIdAndRunIdOrderByCreatedAtDesc(Long tenantId, Long caseId, UUID runId);

    boolean existsByCaseIdAndRunIdAndDedupKey(Long caseId, UUID runId, String dedupKey);
}
