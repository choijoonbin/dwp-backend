package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.LoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 로그인 이력 Repository
 */
@Repository
public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {
    
    /**
     * 사용자의 마지막 로그인 시간 조회 (성공한 로그인만)
     */
    @Query("SELECT MAX(lh.createdAt) FROM LoginHistory lh " +
           "WHERE lh.userId = :userId " +
           "AND lh.success = true")
    Optional<LocalDateTime> findLastLoginAtByUserId(@Param("userId") Long userId);
    
    /**
     * 계정의 마지막 로그인 시간 조회 (principal 기준)
     */
    @Query("SELECT MAX(lh.createdAt) FROM LoginHistory lh " +
           "WHERE lh.tenantId = :tenantId " +
           "AND lh.providerType = :providerType " +
           "AND lh.providerId = :providerId " +
           "AND lh.principal = :principal " +
           "AND lh.success = true")
    Optional<LocalDateTime> findLastLoginAtByAccount(
            @Param("tenantId") Long tenantId,
            @Param("providerType") String providerType,
            @Param("providerId") String providerId,
            @Param("principal") String principal);
}
