package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 로그인 계정 Repository
 */
@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    
    /**
     * 테넌트 ID, 제공자 타입, 제공자 ID, principal로 계정 조회
     * 
     * LOCAL 로그인: providerType=LOCAL, providerId=local, principal=username
     */
    @Query("SELECT ua FROM UserAccount ua WHERE ua.tenantId = :tenantId " +
           "AND ua.providerType = :providerType " +
           "AND ua.providerId = :providerId " +
           "AND ua.principal = :principal")
    Optional<UserAccount> findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
        @Param("tenantId") Long tenantId,
        @Param("providerType") String providerType,
        @Param("providerId") String providerId,
        @Param("principal") String principal
    );
    
    /**
     * 사용자 ID로 계정 목록 조회
     */
    java.util.List<UserAccount> findByUserId(Long userId);
}
