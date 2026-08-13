package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.PrivilegedAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrivilegedAccessRequestRepository
        extends JpaRepository<PrivilegedAccessRequest, UUID> {

    List<PrivilegedAccessRequest> findByTenantIdOrderByRequestedAtDesc(Long tenantId);

    List<PrivilegedAccessRequest>
            findByTenantIdAndRequesterUserIdOrderByRequestedAtDesc(Long tenantId, Long userId);

    Optional<PrivilegedAccessRequest>
            findByPrivilegedAccessRequestIdAndTenantId(UUID requestId, Long tenantId);

    List<PrivilegedAccessRequest> findByTenantIdAndEligibilityIdAndLifecycleState(
            Long tenantId, UUID eligibilityId, String lifecycleState);

    List<PrivilegedAccessRequest> findByTenantIdAndLifecycleStateAndExpiresAtLessThanEqual(
            Long tenantId, String lifecycleState, java.time.Instant now);

    @Query("""
            select request from PrivilegedAccessRequest request
             where request.lifecycleState = 'ACTIVE'
               and request.expiresAt <= :now
             order by request.expiresAt asc
            """)
    List<PrivilegedAccessRequest> findExpired(
            @Param("now") java.time.Instant now,
            Pageable pageable);
}
