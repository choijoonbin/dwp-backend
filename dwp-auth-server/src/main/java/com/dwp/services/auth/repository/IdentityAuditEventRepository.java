package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.IdentityAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IdentityAuditEventRepository extends JpaRepository<IdentityAuditEvent, UUID> {

    Page<IdentityAuditEvent> findByTenantId(Long tenantId, Pageable pageable);
}
