package com.dwp.services.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Security 설정
 * 
 * BCryptPasswordEncoder 등 Security 관련 빈을 제공합니다.
 */
@Configuration
public class SecurityConfig {
    
    /**
     * BCrypt Password Encoder
     * 
     * LOCAL 계정의 비밀번호 해싱/검증에 사용됩니다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
