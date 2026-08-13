package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findByCodeIn(Collection<String> codes);

    Optional<Permission> findByCode(String code);
}
