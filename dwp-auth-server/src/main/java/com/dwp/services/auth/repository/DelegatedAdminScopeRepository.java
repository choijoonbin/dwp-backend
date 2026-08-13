package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.DelegatedAdminScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DelegatedAdminScopeRepository extends JpaRepository<DelegatedAdminScope, UUID> {

    List<DelegatedAdminScope> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<DelegatedAdminScope>
            findByDelegatedAdminScopeIdAndTenantId(UUID scopeId, Long tenantId);

    @Query("""
            select scope from DelegatedAdminScope scope
             where scope.tenantId = :tenantId
               and scope.administratorUserId = :administratorUserId
               and scope.lifecycleState = 'ACTIVE'
               and (scope.validFrom is null or scope.validFrom <= :now)
               and (scope.validTo is null or scope.validTo > :now)
             order by scope.actionCode asc, scope.scopeType asc
            """)
    List<DelegatedAdminScope> findActiveForAdministrator(
            @Param("tenantId") Long tenantId,
            @Param("administratorUserId") Long administratorUserId,
            @Param("now") Instant now);
}
