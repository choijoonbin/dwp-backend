package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.GroupRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupRoleAssignmentRepository extends JpaRepository<GroupRoleAssignment, Long> {

    List<GroupRoleAssignment> findByTenantIdOrderByGroupRoleAssignmentIdAsc(Long tenantId);

    List<GroupRoleAssignment> findByTenantIdAndGroupIdInAndLifecycleState(
            Long tenantId, List<Long> groupIds, String lifecycleState);

    List<GroupRoleAssignment> findByTenantIdAndRoleIdAndLifecycleState(
            Long tenantId, Long roleId, String lifecycleState);

    Optional<GroupRoleAssignment> findByGroupRoleAssignmentIdAndTenantId(
            Long assignmentId, Long tenantId);
}
