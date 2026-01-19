package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 인증 서비스
 * 
 * 로그인 및 JWT 토큰 발급을 담당합니다.
 */
@Slf4j
@Service
public class AuthService {
    
    private final SecretKey secretKey;
    private final Long tokenExpirationSeconds;
    
    public AuthService(
            @Value("${jwt.secret:your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256}") String jwtSecret,
            @Value("${jwt.expiration-seconds:3600}") Long tokenExpirationSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.tokenExpirationSeconds = tokenExpirationSeconds;
    }
    
    /**
     * 로그인 및 JWT 토큰 발급
     * 
     * <p><strong>주의:</strong> 현재는 임시 구현으로, 실제 DB 조회 및 비밀번호 검증은 다음 단계(P1)에서 구현 예정입니다.</p>
     * 
     * @param request 로그인 요청
     * @return 로그인 응답 (JWT 토큰 포함)
     * @throws BaseException 인증 실패 시
     */
    public LoginResponse login(LoginRequest request) {
        // TODO(P1): 실제 사용자 인증 로직 구현
        // - UserRepository를 통한 DB 조회
        // - BCryptPasswordEncoder를 사용한 비밀번호 검증
        // - 테넌트별 사용자 조회 및 권한 확인
        // 현재는 간단한 검증만 수행 (운영 환경에서는 반드시 실제 인증 로직으로 교체)
        
        // 사용자명/비밀번호 검증 (임시 구현)
        if (!validateCredentials(request.getUsername(), request.getPassword())) {
            log.warn("Login failed: invalid credentials for username={}, tenantId={}", 
                    request.getUsername(), request.getTenantId());
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS, "잘못된 사용자명 또는 비밀번호입니다.");
        }
        
        // 사용자 ID 결정 (현재는 username을 userId로 사용)
        // TODO(P1): 실제 DB에서 사용자 정보 조회하여 userId 결정
        // 예: User user = userRepository.findByUsernameAndTenantId(request.getUsername(), request.getTenantId());
        //     String userId = user.getId();
        String userId = request.getUsername();
        
        // JWT 토큰 발급
        String accessToken = generateJwtToken(userId, request.getTenantId());
        
        log.info("Login successful: userId={}, tenantId={}", userId, request.getTenantId());
        
        return LoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(tokenExpirationSeconds)
                .userId(userId)
                .tenantId(request.getTenantId())
                .build();
    }
    
    /**
     * 자격 증명 검증 (임시 구현)
     * 
     * <p><strong>주의:</strong> 현재는 임시 구현으로, 실제 DB 조회 및 비밀번호 검증은 다음 단계(P1)에서 구현 예정입니다.</p>
     * 
     * @param username 사용자명
     * @param password 비밀번호
     * @return 검증 성공 여부
     */
    private boolean validateCredentials(String username, String password) {
        // 임시 구현: username과 password가 모두 비어있지 않으면 통과
        // 운영 환경에서는 반드시 실제 인증 로직으로 교체해야 합니다.
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        if (password == null || password.trim().isEmpty()) {
            return false;
        }
        
        // TODO(P1): 실제 사용자 DB 조회 및 비밀번호 검증
        // 예시:
        //   User user = userRepository.findByUsernameAndTenantId(username, tenantId);
        //   if (user == null) return false;
        //   return passwordEncoder.matches(password, user.getPassword());
        
        return true;
    }
    
    /**
     * JWT 토큰 생성
     * 
     * Python (jose)와 호환되도록 HS256 알고리즘을 사용합니다.
     * 
     * @param userId 사용자 ID (JWT의 sub 클레임)
     * @param tenantId 테넌트 ID (JWT의 tenant_id 클레임)
     * @return JWT 토큰 문자열
     */
    private String generateJwtToken(String userId, String tenantId) {
        Instant now = Instant.now();
        Instant expiration = now.plus(tokenExpirationSeconds, ChronoUnit.SECONDS);
        
        // JWT Payload 구성 (Python jose와 호환)
        Claims claims = Jwts.claims()
                .subject(userId)  // sub: 사용자 ID
                .add("tenant_id", tenantId)  // tenant_id: 테넌트 ID (필수)
                .issuedAt(Date.from(now))  // iat: 발행 시간
                .expiration(Date.from(expiration))  // exp: 만료 시간
                .build();
        
        return Jwts.builder()
                .claims(claims)
                .signWith(secretKey)
                .compact();
    }
}
