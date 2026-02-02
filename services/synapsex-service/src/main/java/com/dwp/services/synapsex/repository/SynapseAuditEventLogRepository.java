package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.SynapseAuditEventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;

public interface SynapseAuditEventLogRepository extends JpaRepository<SynapseAuditEventLog, Long>, JpaSpecificationExecutor<SynapseAuditEventLog> {

    Page<SynapseAuditEventLog> findByTenantId(Long tenantId, Pageable pageable);

    Page<SynapseAuditEventLog> findByTenantIdAndCreatedAtBetween(
            Long tenantId, Instant from, Instant to, Pageable pageable);
}
