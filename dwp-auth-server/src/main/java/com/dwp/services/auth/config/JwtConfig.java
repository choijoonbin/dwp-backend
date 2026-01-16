package com.dwp.services.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT 설정
 * 
 * Python (jose)에서 생성한 JWT 토큰을 검증할 수 있도록 설정합니다.
 */
@Configuration
@EnableWebSecurity
public class JwtConfig {
    
    @Value("${jwt.secret:your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256}")
    private String jwtSecret;
    
    /**
     * JWT Decoder 설정
     * 
     * Python의 jose 라이브러리와 호환되도록 HS256 알고리즘을 사용합니다.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        // HS256 알고리즘을 위한 SecretKey 생성
        SecretKey secretKey = new SecretKeySpec(
            jwtSecret.getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"
        );
        
        return NimbusJwtDecoder.withSecretKey(secretKey).build();
    }
    
    /**
     * Security Filter Chain 설정
     * 
     * JWT 토큰 검증을 활성화하고, 헬스체크 엔드포인트는 인증 없이 접근 가능하도록 설정합니다.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/health", "/auth/info").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
            );
        
        return http.build();
    }
}
