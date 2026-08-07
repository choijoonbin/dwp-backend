package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByRoleIdIn(Collection<Long> roleIds);
}
