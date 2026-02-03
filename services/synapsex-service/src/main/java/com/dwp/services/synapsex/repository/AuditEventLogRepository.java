package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AuditEventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AuditEventLogRepository extends JpaRepository<AuditEventLog, Long>, JpaSpecificationExecutor<AuditEventLog> {

    Page<AuditEventLog> findByTenantId(Long tenantId, Pageable pageable);

    List<AuditEventLog> findByTenantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
            Long tenantId, String resourceType, String resourceId, Pageable pageable);
}
