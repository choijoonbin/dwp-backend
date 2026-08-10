package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.DirectoryGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DirectoryGroupMemberRepository
        extends JpaRepository<DirectoryGroupMember, Long> {

    List<DirectoryGroupMember> findByTenantIdAndGroupId(Long tenantId, Long groupId);

    List<DirectoryGroupMember> findByTenantIdAndUserId(Long tenantId, Long userId);

    void deleteByTenantIdAndGroupIdAndSourceType(Long tenantId, Long groupId, String sourceType);

    List<DirectoryGroupMember> findByTenantIdAndGroupIdIn(
            Long tenantId,
            Collection<Long> groupIds);

    long countByTenantIdAndGroupId(Long tenantId, Long groupId);

    @Query("""
            select member.groupId as groupId, count(member) as memberCount
            from DirectoryGroupMember member
            where member.tenantId = :tenantId and member.groupId in :groupIds
            group by member.groupId
            """)
    List<GroupMemberCount> countMembersByGroupIds(
            @Param("tenantId") Long tenantId,
            @Param("groupIds") Collection<Long> groupIds);

    interface GroupMemberCount {
        Long getGroupId();

        long getMemberCount();
    }
}
