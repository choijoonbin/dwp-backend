package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.OrganizationUnit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrganizationUnitRepository
        extends JpaRepository<OrganizationUnit, Long>, JpaSpecificationExecutor<OrganizationUnit> {

    Optional<OrganizationUnit> findByOrgUnitIdAndTenantId(Long orgUnitId, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select organization
            from OrganizationUnit organization
            where organization.tenantId = :tenantId
            order by organization.orgUnitId
            """)
    List<OrganizationUnit> findByTenantIdForUpdate(@Param("tenantId") Long tenantId);

    List<OrganizationUnit> findByTenantIdAndOrgUnitIdIn(
            Long tenantId,
            Collection<Long> orgUnitIds);

    long countByTenantIdAndParentOrgUnitIdAndStatus(
            Long tenantId,
            Long parentOrgUnitId,
            String status);
}
