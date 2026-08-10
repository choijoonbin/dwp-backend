package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    @Query("""
            select resource from Resource resource
            where (resource.tenantId is null or resource.tenantId = :tenantId)
              and resource.enabled = true
            order by resource.type, resource.key
            """)
    List<Resource> findAvailable(@Param("tenantId") Long tenantId);

    @Query("""
            select resource from Resource resource
            where resource.resourceId = :resourceId
              and (resource.tenantId is null or resource.tenantId = :tenantId)
            """)
    Optional<Resource> findAvailableById(
            @Param("resourceId") Long resourceId,
            @Param("tenantId") Long tenantId);

    Optional<Resource> findByTenantIdAndTypeAndKey(Long tenantId, String type, String key);
}
