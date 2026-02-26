package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RagEvalRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RagEvalRunRepository extends JpaRepository<RagEvalRun, Long> {

    Optional<RagEvalRun> findFirstByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
