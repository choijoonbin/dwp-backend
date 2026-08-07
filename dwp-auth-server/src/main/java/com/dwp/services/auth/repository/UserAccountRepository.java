package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
            Long tenantId,
            String providerType,
            String providerId,
            String principal);
}
