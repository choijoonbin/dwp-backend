package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenId(String tokenId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.tokenId = :tokenId")
    Optional<AuthSession> findByTokenIdForUpdate(@Param("tokenId") String tokenId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE sys_auth_sessions current_session
               SET last_seen_at = :now,
                   idle_expires_at = LEAST(current_session.expires_at, :idleExpiresAt),
                   updated_at = CURRENT_TIMESTAMP
              FROM sys_auth_sessions presented_session
             WHERE presented_session.token_id = :tokenId
               AND presented_session.revoked_at IS NULL
               AND presented_session.expires_at > :now
               AND presented_session.idle_expires_at > :now
               AND (
                    presented_session.superseded_at IS NULL
                    OR presented_session.superseded_expires_at > :now
               )
               AND current_session.session_family_id = presented_session.session_family_id
               AND current_session.revoked_at IS NULL
               AND current_session.superseded_at IS NULL
               AND current_session.expires_at > :now
               AND current_session.idle_expires_at > :now
               AND current_session.last_seen_at <= :touchBefore
            """, nativeQuery = true)
    int touchCurrentByPresentedToken(
            @Param("tokenId") String tokenId,
            @Param("now") Instant now,
            @Param("touchBefore") Instant touchBefore,
            @Param("idleExpiresAt") Instant idleExpiresAt);

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

    List<AuthSession> findByTenantIdAndUserIdInAndRevokedAtIsNull(
            Long tenantId,
            Collection<Long> userIds);

    Optional<AuthSession>
            findFirstBySessionFamilyIdAndRevokedAtIsNullAndSupersededAtIsNullOrderByIssuedAtDesc(
                    UUID sessionFamilyId);
}
