package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.ReconRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconRunRepository extends JpaRepository<ReconRun, Long> {

    List<ReconRun> findByTenantIdOrderByStartedAtDesc(Long tenantId);

    List<ReconRun> findByTenantIdAndRunTypeOrderByStartedAtDesc(Long tenantId, String runType);

    Page<ReconRun> findByTenantIdOrderByStartedAtDesc(Long tenantId, Pageable pageable);

    Page<ReconRun> findByTenantIdAndRunTypeOrderByStartedAtDesc(Long tenantId, String runType, Pageable pageable);
}
