package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.DetectRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface DetectRunRepository extends JpaRepository<DetectRun, Long>, JpaSpecificationExecutor<DetectRun> {

    List<DetectRun> findByTenantIdOrderByStartedAtDesc(Long tenantId);

    Page<DetectRun> findByTenantIdOrderByStartedAtDesc(Long tenantId, Pageable pageable);

    /** 현재 RUNNING(STARTED) run (락 미획득 시 원인 파악용) */
    Optional<DetectRun> findTopByTenantIdAndStatusOrderByStartedAtDesc(Long tenantId, String status);
}
