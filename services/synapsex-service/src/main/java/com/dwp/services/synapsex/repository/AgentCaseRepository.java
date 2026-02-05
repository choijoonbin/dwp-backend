package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentCaseRepository extends JpaRepository<AgentCase, Long> {

    Optional<AgentCase> findByCaseIdAndTenantId(Long caseId, Long tenantId);

    List<AgentCase> findByTenantIdAndBukrsAndBelnrAndGjahr(
            Long tenantId, String bukrs, String belnr, String gjahr);

    List<AgentCase> findByTenantIdAndBukrsAndBelnrAndGjahrAndBuzei(
            Long tenantId, String bukrs, String belnr, String gjahr, String buzei);

    List<AgentCase> findByTenantId(Long tenantId);

    List<AgentCase> findByTenantIdAndCreatedAtAfter(Long tenantId, Instant since);

    List<AgentCase> findByTenantIdAndCaseIdIn(Long tenantId, List<Long> caseIds);

    /** Phase B: dedup_key 기준 upsert */
    Optional<AgentCase> findByTenantIdAndDedupKey(Long tenantId, String dedupKey);
}
