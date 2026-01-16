package com.dwp.services.main.util;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 토큰 검증 유틸리티
 * 
 * dwp-auth-server와 동일한 시크릿 키를 사용하여 JWT 토큰을 검증합니다.
 * 
 * 주의: JWT 라이브러리가 클래스패스에 있을 때만 빈으로 등록됩니다.
 */
@Slf4j
@Component
@ConditionalOnClass(name = "io.jsonwebtoken.Jwts")
public class JwtTokenValidator {
    
    private final SecretKey secretKey;
    
    public JwtTokenValidator(@Value("${jwt.secret:your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256}") String jwtSecret) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * JWT 토큰 검증
     * 
     * @param token JWT 토큰 문자열
     * @return 검증된 Claims
     * @throws BaseException 토큰이 유효하지 않은 경우
     */
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("JWT token has expired: {}", e.getMessage());
            throw new BaseException(ErrorCode.TOKEN_EXPIRED, "JWT token has expired");
        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported JWT token: {}", e.getMessage());
            throw new BaseException(ErrorCode.TOKEN_INVALID, "Unsupported JWT token");
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT token: {}", e.getMessage());
            throw new BaseException(ErrorCode.TOKEN_INVALID, "Malformed JWT token");
        } catch (SecurityException e) {
            log.warn("JWT signature validation failed: {}", e.getMessage());
            throw new BaseException(ErrorCode.TOKEN_INVALID, "JWT signature validation failed");
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is empty or null: {}", e.getMessage());
            throw new BaseException(ErrorCode.TOKEN_INVALID, "JWT token is empty or null");
        } catch (Exception e) {
            log.error("Unexpected error while validating JWT token", e);
            throw new BaseException(ErrorCode.TOKEN_INVALID, "Failed to validate JWT token: " + e.getMessage());
        }
    }
    
    /**
     * JWT 토큰에서 테넌트 ID 추출
     * 
     * @param token JWT 토큰 문자열
     * @return 테넌트 ID
     */
    public String extractTenantId(String token) {
        Claims claims = validateToken(token);
        return claims.get("tenant_id", String.class);
    }
    
    /**
     * JWT 토큰에서 사용자 ID 추출
     * 
     * @param token JWT 토큰 문자열
     * @return 사용자 ID (sub 클레임)
     */
    public String extractUserId(String token) {
        Claims claims = validateToken(token);
        return claims.getSubject();
    }
}
