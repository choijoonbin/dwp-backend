package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.GroupRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupRoleAssignmentRepository extends JpaRepository<GroupRoleAssignment, Long> {

    List<GroupRoleAssignment> findByTenantIdOrderByGroupRoleAssignmentIdAsc(Long tenantId);

    List<GroupRoleAssignment> findByTenantIdAndGroupIdInAndLifecycleState(
            Long tenantId, List<Long> groupIds, String lifecycleState);

    List<GroupRoleAssignment> findByTenantIdAndRoleIdAndLifecycleState(
            Long tenantId, Long roleId, String lifecycleState);

    @Query("""
            select assignment
            from GroupRoleAssignment assignment
            where assignment.tenantId = :tenantId
                and assignment.groupId = :groupId
                and assignment.lifecycleState = 'ACTIVE'
                and assignment.assignmentType = 'ACTIVE'
                and (assignment.validFrom is null or assignment.validFrom <= current_timestamp)
                and (assignment.validTo is null or assignment.validTo > current_timestamp)
            order by assignment.groupRoleAssignmentId
            """)
    List<GroupRoleAssignment> findEffectiveByTenantIdAndGroupId(
            @Param("tenantId") Long tenantId,
            @Param("groupId") Long groupId);

    Optional<GroupRoleAssignment> findByGroupRoleAssignmentIdAndTenantId(
            Long assignmentId, Long tenantId);
}
