package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.IngestRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface IngestRunRepository extends JpaRepository<IngestRun, Long>, JpaSpecificationExecutor<IngestRun> {

    List<IngestRun> findByTenantIdOrderByStartedAtDesc(Long tenantId);

    Page<IngestRun> findByTenantIdOrderByStartedAtDesc(Long tenantId, Pageable pageable);
}
