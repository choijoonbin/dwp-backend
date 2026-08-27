package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByUserIdAndTenantId(Long userId, Long tenantId);

    @Query("""
            select user
            from User user
            where user.userId = :userId
              and user.tenantId = :tenantId
              and user.identityPlane = 'TENANT'
            """)
    Optional<User> findTenantIdentityByUserIdAndTenantId(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId);

    Optional<User> findByPublicIdAndTenantId(UUID publicId, Long tenantId);

    Optional<User> findByTenantIdAndSourceTypeAndExternalId(
            Long tenantId, String sourceType, String externalId);

    Optional<User> findByTenantIdAndScimUserName(Long tenantId, String scimUserName);

    Optional<User> findByTenantIdAndEmailNormalized(Long tenantId, String emailNormalized);

    Optional<User> findByTenantIdAndPersonPublicId(Long tenantId, UUID personPublicId);

    List<User> findByTenantIdAndPublicIdIn(Long tenantId, Collection<UUID> publicIds);

    List<User> findByTenantIdAndPrimaryOrgUnitIdOrderByDisplayNameAscUserIdAsc(
            Long tenantId,
            Long primaryOrgUnitId);

    long countByTenantIdAndPrimaryOrgUnitId(Long tenantId, Long primaryOrgUnitId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select user
            from User user
            where user.tenantId = :tenantId and user.userId in :userIds
            order by user.userId
            """)
    List<User> findByTenantIdAndUserIdInForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("userIds") Collection<Long> userIds);

    List<User> findByTenantIdAndUserIdIn(Long tenantId, Collection<Long> userIds);

    @Query("""
            select user
            from User user
            where user.tenantId = :tenantId
              and user.identityPlane = 'TENANT'
              and (
                    (:activeOnly = true and user.status = 'ACTIVE')
                    or (:activeOnly = false and user.status in ('ACTIVE', 'INACTIVE'))
                  )
              and (
                    :query = ''
                    or lower(user.displayName) like lower(concat('%', :query, '%'))
                    or lower(coalesce(user.email, '')) like lower(concat('%', :query, '%'))
                  )
            order by
              case
                when user.status = 'INACTIVE' then 0
                when user.status = 'ACTIVE' then 1
                else 2
              end,
              user.displayName,
              user.userId
            """)
    List<User> searchTenantDirectoryUsers(
            @Param("tenantId") Long tenantId,
            @Param("query") String query,
            @Param("activeOnly") boolean activeOnly,
            Pageable pageable);

    @Query("""
            select user.primaryOrgUnitId as orgUnitId, count(user) as memberCount
            from User user
            where user.tenantId = :tenantId
                and user.primaryOrgUnitId in :orgUnitIds
            group by user.primaryOrgUnitId
            """)
    List<OrganizationMemberCount> countMembersByOrganizationIds(
            @Param("tenantId") Long tenantId,
            @Param("orgUnitIds") Collection<Long> orgUnitIds);

    interface OrganizationMemberCount {
        Long getOrgUnitId();

        long getMemberCount();
    }
}
