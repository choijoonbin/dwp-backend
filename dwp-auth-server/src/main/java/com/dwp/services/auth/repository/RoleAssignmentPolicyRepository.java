package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.RoleAssignmentPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoleAssignmentPolicyRepository
        extends JpaRepository<RoleAssignmentPolicy, Long> {

    List<RoleAssignmentPolicy>
            findByGrantorRoleCodeInAndAssignmentModeAndLifecycleState(
                    Collection<String> grantorRoleCodes,
                    String assignmentMode,
                    String lifecycleState);
}
