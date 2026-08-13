package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.ActivePrivilegedGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivePrivilegedGrantRepository
        extends JpaRepository<ActivePrivilegedGrant, UUID> {

    Optional<ActivePrivilegedGrant>
            findByPrivilegedAccessRequestIdAndTenantId(UUID requestId, Long tenantId);

    @Query("""
            select grant from ActivePrivilegedGrant grant
             where grant.tenantId = :tenantId
               and grant.userId = :userId
               and grant.revokedAt is null
               and grant.expiresAt > :now
             order by grant.expiresAt asc
            """)
    List<ActivePrivilegedGrant> findActiveForUser(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("now") Instant now);

    @Query("""
            select grant from ActivePrivilegedGrant grant
             where grant.tenantId = :tenantId
               and grant.roleId = :roleId
               and grant.revokedAt is null
               and grant.expiresAt > :now
            """)
    List<ActivePrivilegedGrant> findActiveForRole(
            @Param("tenantId") Long tenantId,
            @Param("roleId") Long roleId,
            @Param("now") Instant now);

    List<ActivePrivilegedGrant> findByTenantIdAndRevokedAtIsNullAndExpiresAtLessThanEqual(
            Long tenantId, Instant now);

    @Query("""
            select grant from ActivePrivilegedGrant grant
             where grant.revokedAt is null
               and grant.expiresAt <= :now
             order by grant.expiresAt asc
            """)
    List<ActivePrivilegedGrant> findExpired(
            @Param("now") Instant now,
            Pageable pageable);
}
