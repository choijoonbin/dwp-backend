package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenIdAndRevokedAtIsNull(String tokenId);

    boolean existsByTokenIdAndRevokedAtIsNullAndExpiresAtAfter(
            String tokenId,
            Instant now);
}
