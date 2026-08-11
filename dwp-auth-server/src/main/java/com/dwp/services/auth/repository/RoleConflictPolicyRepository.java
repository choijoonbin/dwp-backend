package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.RoleConflictPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleConflictPolicyRepository extends JpaRepository<RoleConflictPolicy, Long> {

    List<RoleConflictPolicy> findByLifecycleStateOrderByLeftRoleCodeAscRightRoleCodeAsc(
            String lifecycleState);
}
