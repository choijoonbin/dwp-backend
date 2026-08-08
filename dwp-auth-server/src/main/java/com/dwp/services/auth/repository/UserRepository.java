package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByUserIdAndTenantId(Long userId, Long tenantId);

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
