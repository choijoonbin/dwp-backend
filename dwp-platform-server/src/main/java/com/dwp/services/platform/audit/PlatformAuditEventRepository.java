package com.dwp.services.platform.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlatformAuditEventRepository extends JpaRepository<PlatformAuditEvent, UUID> {

    Page<PlatformAuditEvent> findByTenantId(Long tenantId, Pageable pageable);
}
