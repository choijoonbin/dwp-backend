package com.dwp.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 설정
 * 
 * 환경 변수 CORS_ALLOWED_ORIGINS를 통해 허용할 Origin을 설정할 수 있습니다.
 * 여러 Origin을 허용하려면 콤마(,)로 구분하여 설정하세요.
 * 
 * 예시:
 * - 단일 Origin: CORS_ALLOWED_ORIGINS=http://localhost:4200
 * - 다중 Origin: CORS_ALLOWED_ORIGINS=http://localhost:4200,https://example.com,https://app.example.com
 */
@Configuration
@ConfigurationProperties(prefix = "cors")
@Getter
@Setter
public class CorsConfig {

    private String allowedOrigins = "http://localhost:4200";
    private String allowedMethods = "GET,POST,PUT,DELETE,PATCH,OPTIONS";
    private String allowedHeaders = "*";
    private boolean allowCredentials = true;
    private long maxAge = 3600;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        
        // 허용할 Origin 설정 (환경 변수에서 읽어옴)
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        corsConfig.setAllowedOrigins(origins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        
        // 허용할 HTTP 메서드 설정
        List<String> methods = Arrays.asList(allowedMethods.split(","));
        corsConfig.setAllowedMethods(methods.stream()
                .map(String::trim)
                .filter(method -> !method.isEmpty())
                .toList());
        
        // 허용할 헤더 설정
        if ("*".equals(allowedHeaders)) {
            corsConfig.addAllowedHeader("*");
        } else {
            List<String> headers = Arrays.asList(allowedHeaders.split(","));
            corsConfig.setAllowedHeaders(headers.stream()
                    .map(String::trim)
                    .filter(header -> !header.isEmpty())
                    .toList());
        }
        
        // Credentials 허용 여부
        corsConfig.setAllowCredentials(allowCredentials);
        
        // Preflight 요청 캐시 시간 (초)
        corsConfig.setMaxAge(maxAge);
        
        // 모든 경로에 CORS 설정 적용
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
        
        return new CorsWebFilter(source);
    }
}
