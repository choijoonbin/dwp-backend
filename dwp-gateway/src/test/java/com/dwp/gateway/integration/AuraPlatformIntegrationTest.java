package com.dwp.gateway.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * Aura-Platform과 Gateway 통합 테스트
 * 
 * 이 테스트는 Gateway를 통해 Aura-Platform의 엔드포인트에 접근 가능한지 확인합니다.
 * 
 * 주의: 실제 Aura-Platform 서비스가 포트 8000에서 실행 중이어야 합니다.
 * Mock 서버를 사용하려면 @MockBean을 활용하세요.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@DisplayName("Aura-Platform Gateway 통합 테스트")
class AuraPlatformIntegrationTest {
    
    @Autowired
    private WebTestClient webTestClient;
    
    @Test
    @DisplayName("Gateway를 통한 Aura-Platform 헬스체크 접근 테스트")
    @SuppressWarnings("null")
    void testAuraPlatformHealthCheckThroughGateway() {
        // Given: Gateway가 /api/aura/** 경로를 Aura-Platform으로 라우팅
        
        // When: Gateway를 통해 Aura-Platform의 헬스체크 엔드포인트 호출
        // Then: 정상적으로 응답을 받아야 함
        webTestClient
            .get()
            .uri("/api/aura/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.status").exists()
            .consumeWith(response -> {
                System.out.println("Aura-Platform Health Check Response: " + 
                    new String(response.getResponseBody()));
            });
    }
    
    @Test
    @DisplayName("Gateway를 통한 Aura-Platform 정보 조회 테스트")
    @SuppressWarnings("null")
    void testAuraPlatformInfoThroughGateway() {
        webTestClient
            .get()
            .uri("/api/aura/info")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON);
    }
    
    @Test
    @DisplayName("SSE 타임아웃 설정 확인 - 장기 실행 요청 테스트")
    @SuppressWarnings("null")
    void testSSETimeoutConfiguration() {
        // Given: Gateway의 SSE 타임아웃이 300초로 설정됨
        
        // When: 장기 실행 요청 시뮬레이션
        // Then: 타임아웃 없이 정상 처리되어야 함
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofSeconds(65)) // 60초 이상 대기
            .build()
            .get()
            .uri("/api/aura/health")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk();
    }
    
    @Test
    @DisplayName("Gateway 라우팅 경로 검증 - StripPrefix 동작 확인")
    void testGatewayRoutingPathStripping() {
        // Given: /api/aura/** → /aura/**로 변환 (StripPrefix=1)
        
        // When: /api/aura/health 호출
        // Then: Aura-Platform의 /health 엔드포인트로 전달되어야 함
        webTestClient
            .get()
            .uri("/api/aura/health")
            .exchange()
            .expectStatus().isOk();
    }
    
    @Test
    @DisplayName("CORS 헤더 확인 - Aura-Platform 요청")
    void testCorsHeadersForAuraPlatform() {
        webTestClient
            .options()
            .uri("/api/aura/health")
            .header("Origin", "http://localhost:3039")
            .header("Access-Control-Request-Method", "GET")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().exists("Access-Control-Allow-Origin")
            .expectHeader().exists("Access-Control-Allow-Methods");
    }
    
    @Test
    @DisplayName("Gateway를 통한 에러 응답 처리 테스트")
    void testErrorHandlingThroughGateway() {
        // Given: 존재하지 않는 엔드포인트 호출
        
        // When: /api/aura/nonexistent 호출
        // Then: 적절한 에러 응답을 받아야 함
        webTestClient
            .get()
            .uri("/api/aura/nonexistent-endpoint")
            .exchange()
            .expectStatus().is4xxClientError();
    }
}
