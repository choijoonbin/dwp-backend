package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AuthSessionResponse;
import com.dwp.services.auth.dto.SessionRotationResponse;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.repository.AuthSessionRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuthSessionService {

    private final AuthSessionRepository authSessionRepository;
    private final String jwtSecret;
    private final long absoluteLifetimeSeconds;
    private final long idleTimeoutSeconds;
    private final long rotationMinimumAgeSeconds;
    private final long previousTokenGraceSeconds;
    private final long activityTouchIntervalSeconds;

    public AuthSessionService(
            AuthSessionRepository authSessionRepository,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.expiration-seconds:28800}") long absoluteLifetimeSeconds,
            @Value("${dwp.security.session.idle-timeout-seconds:1800}") long idleTimeoutSeconds,
            @Value("${dwp.security.session.rotation-minimum-age-seconds:600}")
                    long rotationMinimumAgeSeconds,
            @Value("${dwp.security.session.previous-token-grace-seconds:30}")
                    long previousTokenGraceSeconds,
            @Value("${dwp.security.session.activity-touch-interval-seconds:60}")
                    long activityTouchIntervalSeconds) {
        this.authSessionRepository = authSessionRepository;
        this.jwtSecret = jwtSecret;
        this.absoluteLifetimeSeconds = absoluteLifetimeSeconds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.rotationMinimumAgeSeconds = rotationMinimumAgeSeconds;
        this.previousTokenGraceSeconds = previousTokenGraceSeconds;
        this.activityTouchIntervalSeconds = activityTouchIntervalSeconds;
    }

    @Transactional
    public IssuedSession create(
            Long userId,
            Long tenantId,
            List<String> roles,
            HttpServletRequest request) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(absoluteLifetimeSeconds);
        Instant idleExpiresAt = earlier(now.plusSeconds(idleTimeoutSeconds), expiresAt);
        UUID familyId = UUID.randomUUID();
        String tokenId = UUID.randomUUID().toString();

        AuthSession session = AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .sessionFamilyId(familyId)
                .tokenId(tokenId)
                .tenantId(tenantId)
                .userId(userId)
                .sessionStartedAt(now)
                .issuedAt(now)
                .lastSeenAt(now)
                .idleExpiresAt(idleExpiresAt)
                .expiresAt(expiresAt)
                .ipAddress(clientIp(request))
                .userAgent(userAgent(request))
                .build();
        authSessionRepository.save(session);

        String token = createToken(userId, tenantId, roles, tokenId, familyId, now, expiresAt);
        return new IssuedSession(token, secondsUntil(now, expiresAt));
    }

    @Transactional
    public RotationResult rotate(
            Jwt jwt,
            Long userId,
            Long tenantId,
            List<String> roles,
            HttpServletRequest request) {
        Instant now = Instant.now();
        AuthSession previous = authSessionRepository.findByTokenIdForUpdate(jwt.getId())
                .orElseThrow(() -> new BaseException(ErrorCode.TOKEN_INVALID));
        requireActiveIdentity(previous, jwt, userId, tenantId, now);

        if (previous.getSupersededAt() != null
                || previous.getIssuedAt().plusSeconds(rotationMinimumAgeSeconds).isAfter(now)) {
            return RotationResult.unchanged(previous.getIdleExpiresAt(), previous.getExpiresAt());
        }

        previous.setSupersededAt(now);
        previous.setSupersededExpiresAt(earlier(
                now.plusSeconds(previousTokenGraceSeconds),
                earlier(previous.getIdleExpiresAt(), previous.getExpiresAt())));
        authSessionRepository.saveAndFlush(previous);

        String tokenId = UUID.randomUUID().toString();
        AuthSession current = AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .sessionFamilyId(previous.getSessionFamilyId())
                .tokenId(tokenId)
                .tenantId(tenantId)
                .userId(userId)
                .sessionStartedAt(previous.getSessionStartedAt())
                .issuedAt(now)
                .lastSeenAt(previous.getLastSeenAt())
                .idleExpiresAt(previous.getIdleExpiresAt())
                .expiresAt(previous.getExpiresAt())
                .ipAddress(firstNonBlank(clientIp(request), previous.getIpAddress()))
                .userAgent(firstNonBlank(userAgent(request), previous.getUserAgent()))
                .build();
        authSessionRepository.save(current);

        String token = createToken(
                userId,
                tenantId,
                roles,
                tokenId,
                previous.getSessionFamilyId(),
                now,
                previous.getExpiresAt());
        return RotationResult.rotated(
                token,
                secondsUntil(now, previous.getExpiresAt()),
                current.getIdleExpiresAt(),
                current.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<AuthSessionResponse> list(
            String tokenId,
            Long userId,
            Long tenantId) {
        Instant now = Instant.now();
        UUID currentFamilyId = requireOwnedSession(tokenId, userId, tenantId).getSessionFamilyId();
        return authSessionRepository
                .findByTenantIdAndUserIdAndRevokedAtIsNullAndSupersededAtIsNullAndExpiresAtAfterAndIdleExpiresAtAfterOrderByLastSeenAtDesc(
                        tenantId,
                        userId,
                        now,
                        now)
                .stream()
                .map(session -> new AuthSessionResponse(
                        session.getSessionFamilyId(),
                        currentFamilyId.equals(session.getSessionFamilyId()),
                        session.getIpAddress(),
                        session.getUserAgent(),
                        session.getSessionStartedAt(),
                        session.getLastSeenAt(),
                        session.getIdleExpiresAt(),
                        session.getExpiresAt()))
                .toList();
    }

    @Transactional
    public boolean revokeFamily(
            UUID familyId,
            String currentTokenId,
            Long userId,
            Long tenantId) {
        AuthSession current = requireOwnedSession(currentTokenId, userId, tenantId);
        List<AuthSession> sessions = authSessionRepository
                .findBySessionFamilyIdAndTenantIdAndUserIdAndRevokedAtIsNull(
                        familyId,
                        tenantId,
                        userId);
        if (sessions.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);

        Instant now = Instant.now();
        sessions.forEach(session -> session.setRevokedAt(now));
        return current.getSessionFamilyId().equals(familyId);
    }

    @Transactional
    public void revokeOthers(String currentTokenId, Long userId, Long tenantId) {
        UUID currentFamilyId = requireOwnedSession(currentTokenId, userId, tenantId)
                .getSessionFamilyId();
        Instant now = Instant.now();
        authSessionRepository.findByTenantIdAndUserIdAndRevokedAtIsNull(tenantId, userId)
                .stream()
                .filter(session -> !currentFamilyId.equals(session.getSessionFamilyId()))
                .forEach(session -> session.setRevokedAt(now));
    }

    @Transactional
    public void revokeCurrent(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) return;
        authSessionRepository.findByTokenId(tokenId).ifPresent(current -> {
            Instant now = Instant.now();
            authSessionRepository
                    .findBySessionFamilyIdAndTenantIdAndUserIdAndRevokedAtIsNull(
                            current.getSessionFamilyId(),
                            current.getTenantId(),
                            current.getUserId())
                    .forEach(session -> session.setRevokedAt(now));
        });
    }

    @Transactional
    public void touch(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) return;
        Instant now = Instant.now();
        authSessionRepository.touchCurrentByPresentedToken(
                tokenId,
                now,
                now.minusSeconds(activityTouchIntervalSeconds),
                now.plusSeconds(idleTimeoutSeconds));
    }

    private AuthSession requireOwnedSession(String tokenId, Long userId, Long tenantId) {
        return authSessionRepository.findByTokenId(tokenId)
                .filter(session -> Objects.equals(session.getUserId(), userId))
                .filter(session -> Objects.equals(session.getTenantId(), tenantId))
                .orElseThrow(() -> new BaseException(ErrorCode.TOKEN_INVALID));
    }

    private void requireActiveIdentity(
            AuthSession session,
            Jwt jwt,
            Long userId,
            Long tenantId,
            Instant now) {
        String familyClaim = jwt.getClaimAsString("sid");
        if (!session.isActiveAt(now)
                || !Objects.equals(session.getUserId(), userId)
                || !Objects.equals(session.getTenantId(), tenantId)
                || !session.getSessionFamilyId().toString().equals(familyClaim)) {
            throw new BaseException(ErrorCode.TOKEN_INVALID);
        }
    }

    private String createToken(
            Long userId,
            Long tenantId,
            List<String> roles,
            String tokenId,
            UUID familyId,
            Instant issuedAt,
            Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .id(tokenId)
                .subject(String.valueOf(userId))
                .claim("tenant_id", String.valueOf(tenantId))
                .claim("roles", roles)
                .claim("sid", familyId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static long secondsUntil(Instant now, Instant expiresAt) {
        return Math.max(1, Duration.between(now, expiresAt).getSeconds());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return truncate(value, 50);
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : truncate(request.getHeader("User-Agent"), 2048);
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }

    public record IssuedSession(String accessToken, long expiresIn) {
    }

    public record RotationResult(
            String accessToken,
            long cookieMaxAgeSeconds,
            SessionRotationResponse response) {

        private static RotationResult unchanged(Instant idleExpiresAt, Instant expiresAt) {
            return new RotationResult(
                    null,
                    0,
                    new SessionRotationResponse(false, idleExpiresAt, expiresAt));
        }

        private static RotationResult rotated(
                String token,
                long maxAgeSeconds,
                Instant idleExpiresAt,
                Instant expiresAt) {
            return new RotationResult(
                    token,
                    maxAgeSeconds,
                    new SessionRotationResponse(true, idleExpiresAt, expiresAt));
        }
    }
}
