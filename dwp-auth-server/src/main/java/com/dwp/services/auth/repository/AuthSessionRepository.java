package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenId(String tokenId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.tokenId = :tokenId")
    Optional<AuthSession> findByTokenIdForUpdate(@Param("tokenId") String tokenId);

    List<AuthSession>
            findByTenantIdAndUserIdAndRevokedAtIsNullAndSupersededAtIsNullAndExpiresAtAfterAndIdleExpiresAtAfterOrderByLastSeenAtDesc(
                    Long tenantId,
                    Long userId,
                    Instant absoluteNow,
                    Instant idleNow);

    List<AuthSession> findBySessionFamilyIdAndTenantIdAndUserIdAndRevokedAtIsNull(
            UUID sessionFamilyId,
            Long tenantId,
            Long userId);

    List<AuthSession> findByTenantIdAndUserIdAndRevokedAtIsNull(
            Long tenantId,
            Long userId);

    Optional<AuthSession>
            findFirstBySessionFamilyIdAndRevokedAtIsNullAndSupersededAtIsNullOrderByIssuedAtDesc(
                    UUID sessionFamilyId);
}
