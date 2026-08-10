package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    @Query("""
            select account
            from UserAccount account
            where account.tenantId = :tenantId
                and account.providerType = 'LOCAL'
                and account.providerId = 'local'
                and account.principal = :email
            """)
    Optional<UserAccount> findLocalForAuthentication(
            @Param("tenantId") Long tenantId,
            @Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.userAccountId = :accountId")
    Optional<UserAccount> findByIdForUpdate(@Param("accountId") Long accountId);

    Optional<UserAccount> findByTenantIdAndProviderTypeAndIssuerUriAndPrincipal(
            Long tenantId,
            String providerType,
            String issuerUri,
            String principal);

    Optional<UserAccount> findByTenantIdAndUserIdAndProviderTypeAndProviderId(
            Long tenantId,
            Long userId,
            String providerType,
            String providerId);

    List<UserAccount> findByTenantIdAndUserId(Long tenantId, Long userId);
}
