package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.EmergencyAccessPrincipal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface EmergencyAccessPrincipalRepository
        extends JpaRepository<EmergencyAccessPrincipal, UUID> {

    Optional<EmergencyAccessPrincipal> findByTenantIdAndUserId(Long tenantId, Long userId);

    List<EmergencyAccessPrincipal> findByTenantIdOrderByReviewDueAtAsc(Long tenantId);
}
