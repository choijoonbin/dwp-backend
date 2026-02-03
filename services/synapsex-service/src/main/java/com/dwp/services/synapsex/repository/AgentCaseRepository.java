package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentCaseRepository extends JpaRepository<AgentCase, Long> {

    Optional<AgentCase> findByCaseIdAndTenantId(Long caseId, Long tenantId);

    List<AgentCase> findByTenantIdAndBukrsAndBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);

    List<AgentCase> findByTenantIdAndBukrsAndBelnrAndGjahrAndBuzei(
            Long tenantId, String bukrs, String belnr, String gjahr, String buzei);
}
