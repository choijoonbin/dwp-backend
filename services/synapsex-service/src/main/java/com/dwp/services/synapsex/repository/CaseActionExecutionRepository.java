package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.CaseActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

import java.util.Optional;

public interface CaseActionExecutionRepository extends JpaRepository<CaseActionExecution, UUID> {

    List<CaseActionExecution> findByTenantIdAndCaseIdOrderByExecutedAtDesc(Long tenantId, Long caseId);

    List<CaseActionExecution> findByTenantIdAndProposalId(Long tenantId, UUID proposalId);

    Optional<CaseActionExecution> findByTenantIdAndGatewayRequestId(Long tenantId, String gatewayRequestId);
}
