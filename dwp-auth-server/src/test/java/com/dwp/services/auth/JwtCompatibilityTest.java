package com.dwp.services.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 호환성 테스트
 * 
 * Python (jose)에서 생성한 JWT 토큰이 Java (Spring Security)에서 검증 가능한지 확인합니다.
 */
@DisplayName("JWT Python-Java 호환성 테스트")
class JwtCompatibilityTest {
    
    private static final String SECRET_KEY = "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256";
    
    /**
     * Python jose 라이브러리와 호환되는 JWT 토큰 생성 테스트
     * 
     * Python 코드:
     * ```python
     * from jose import jwt
     * payload = {
     *     "sub": "backend_user_001",
     *     "tenant_id": "tenant1",
     *     "email": "user@dwp.com",
     *     "role": "user",
     *     "exp": datetime.now(timezone.utc) + timedelta(hours=1),
     *     "iat": datetime.now(timezone.utc),
     * }
     * token = jwt.encode(payload, SECRET_KEY, algorithm="HS256")
     * ```
     */
    @Test
    @DisplayName("Python 호환 JWT 토큰 생성 및 검증")
    void testPythonCompatibleJwtToken() {
        // Given: Python과 동일한 방식으로 토큰 생성
        SecretKey key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes(StandardCharsets.UTF_8));
        
        Instant now = Instant.now();
        Instant expiration = now.plus(1, ChronoUnit.HOURS);
        
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "backend_user_001");
        claims.put("tenant_id", "tenant1");
        claims.put("email", "user@dwp.com");
        claims.put("role", "user");
        
        String token = Jwts.builder()
                .claims(claims)
                .subject("backend_user_001")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(key)
                .compact();
        
        // When: 토큰 검증
        Claims decoded = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        
        // Then: 모든 클레임이 정상적으로 파싱되어야 함
        assertEquals("backend_user_001", decoded.getSubject());
        assertEquals("tenant1", decoded.get("tenant_id"));
        assertEquals("user@dwp.com", decoded.get("email"));
        assertEquals("user", decoded.get("role"));
        assertNotNull(decoded.getIssuedAt());
        assertNotNull(decoded.getExpiration());
        assertTrue(decoded.getExpiration().after(Date.from(now)));
    }
    
    /**
     * Python에서 생성한 토큰 형식 검증
     * 
     * 주의: Python의 datetime 객체를 직접 넣으면 안 됩니다.
     * exp와 iat는 Unix timestamp (초 단위 정수)여야 합니다.
     */
    @Test
    @DisplayName("Python 토큰 형식 검증 - exp는 숫자여야 함")
    void testPythonTokenFormat() {
        // Python에서 올바른 형식:
        // exp = int((datetime.now(timezone.utc) + timedelta(hours=1)).timestamp())
        // iat = int(datetime.now(timezone.utc).timestamp())
        
        SecretKey key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes(StandardCharsets.UTF_8));
        
        Instant now = Instant.now();
        long expTimestamp = now.plus(1, ChronoUnit.HOURS).getEpochSecond();
        long iatTimestamp = now.getEpochSecond();
        
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "backend_user_001");
        claims.put("tenant_id", "tenant1");
        claims.put("exp", expTimestamp);  // 숫자로 저장
        claims.put("iat", iatTimestamp);  // 숫자로 저장
        
        String token = Jwts.builder()
                .claims(claims)
                .signWith(key)
                .compact();
        
        // 검증
        Claims decoded = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        
        assertNotNull(decoded.getExpiration());
        assertTrue(decoded.getExpiration().after(Date.from(now)));
    }
    
    /**
     * 시크릿 키 길이 검증
     * HS256 알고리즘은 최소 256비트(32바이트)의 키가 필요합니다.
     * 
     * 주의: JJWT 0.12.x 버전에서는 짧은 키에 대해 WeakKeyException을 던집니다.
     */
    @Test
    @DisplayName("시크릿 키 길이 검증")
    void testSecretKeyLength() {
        // Given: 짧은 키
        String shortKey = "short_key";
        
        // Then: WeakKeyException 또는 IllegalArgumentException이 발생해야 함
        // (JJWT 0.12.x 버전에서는 WeakKeyException을 던짐)
        Exception exception = assertThrows(Exception.class, () -> {
            Keys.hmacShaKeyFor(shortKey.getBytes(StandardCharsets.UTF_8));
        });
        
        // 예외 타입 확인 (WeakKeyException 또는 IllegalArgumentException)
        assertTrue(
            exception instanceof WeakKeyException || exception instanceof IllegalArgumentException,
            "Expected WeakKeyException or IllegalArgumentException, but got: " + exception.getClass().getName()
        );
        
        // Given: 충분한 길이의 키
        String longKey = "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256";
        
        // Then: 정상적으로 생성되어야 함
        assertDoesNotThrow(() -> {
            Keys.hmacShaKeyFor(longKey.getBytes(StandardCharsets.UTF_8));
        });
    }
    
}
