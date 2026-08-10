package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.DirectoryGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DirectoryGroupRepository
        extends JpaRepository<DirectoryGroup, Long>, JpaSpecificationExecutor<DirectoryGroup> {

    Optional<DirectoryGroup> findByGroupIdAndTenantId(Long groupId, Long tenantId);

    Optional<DirectoryGroup> findByPublicIdAndTenantId(UUID publicId, Long tenantId);

    Optional<DirectoryGroup> findByTenantIdAndSourceTypeAndExternalId(
            Long tenantId, String sourceType, String externalId);

    Optional<DirectoryGroup> findByTenantIdAndSourceTypeAndDisplayName(
            Long tenantId, String sourceType, String displayName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select directoryGroup
            from DirectoryGroup directoryGroup
            where directoryGroup.groupId = :groupId
                and directoryGroup.tenantId = :tenantId
            """)
    Optional<DirectoryGroup> findByGroupIdAndTenantIdForUpdate(
            @Param("groupId") Long groupId,
            @Param("tenantId") Long tenantId);
}
