package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUserIdAndTenantId(Long userId, Long tenantId);
}
